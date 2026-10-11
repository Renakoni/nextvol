package indi.renakoni.nextvol.ui.home.explore.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.explore.*
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryVersion
import indi.renakoni.nextvol.ui.home.discovery.version
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

data class SearchHubBook(
    val id: String, val source: Identifier, val sourceName: String,
    val preview: BookInformation?, val information: Flow<Result<BookInformation, WebRequestError>>,
)

data class SearchHubSource(
    val id: Identifier, val name: String, val category: SourceCategory?,
    val page: Int = 1, val nextPage: Int? = null, val pending: Boolean = true,
    val failure: SourceSearchFailure? = null,
    val query: String? = null,
    // Persist the current page's progress across cancellation and a cached-page replay.
    val receivedBooks: Boolean = false, val addedBooks: Boolean = false,
)

data class SearchHubState(
    val query: String = "", val submittedKeyword: String = "", val history: List<String> = emptyList(),
    val scope: SourceCategory? = null, val sources: List<SearchHubSource> = emptyList(),
    val selectedSource: Identifier? = null, val selectedSourceName: String = "",
    val books: List<SearchHubBook> = emptyList(), val searching: Boolean = false,
    val stopped: Boolean = false, val limited: Boolean = false,
    val completed: Int = 0, val total: Int = 0, val revision: Long = 0,
) {
    val scopedSources get() = sources.filter {
        if (selectedSource != null) it.id == selectedSource else scope == null || it.category == scope
    }
    val failures get() = scopedSources.filter { it.failure != null }
    val hasMore get() = !limited && scopedSources.any { it.nextPage != null }
}

@HiltViewModel
class SearchHubViewModel internal constructor(
    private val registry: WebSourceRegistry,
    private val coordinator: SearchCoordinator,
    private val books: BookRepository,
    users: UserDataRepository,
    private val accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
    browsing: SourceBrowseSettings,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject constructor(registry: WebSourceRegistry, coordinator: SearchCoordinator, books: BookRepository,
        users: UserDataRepository, accounts: SourceSessionManager, saved: SavedStateHandle,
        browsing: SourceBrowseSettings) : this(registry, coordinator, books, users, accounts, saved, browsing, Dispatchers.IO)

    private val history = users.stringListUserData(UserDataPath.Search.History.path)
    private val initialSource = saved.get<String>("sourceNamespace")?.let { namespace ->
        saved.get<String>("sourceId")?.let { Identifier(namespace, it) }
    }
    private val mutable = MutableStateFlow(SearchHubState(
        query = saved["search.query"] ?: "", submittedKeyword = saved["search.submitted"] ?: "",
        scope = if (saved.contains("search.scope")) SourceCategory.entries.find { it.name == saved.get<String>("search.scope") }
            else browsing.scope.value.takeIf { initialSource == null },
        selectedSource = initialSource, selectedSourceName = saved["sourceName"] ?: "",
    ))
    val state = mutable.asStateFlow()
    private var versions = emptyMap<Identifier, DiscoveryVersion>()
    private data class Candidate(val book: SearchHubBook, val rank: SearchRank, val evidence: SearchEvidence?,
        val complete: Boolean, val remoteId: String)
    private val results = linkedMapOf<String, Candidate>()
    private var ranking = SearchRanking(state.value.submittedKeyword)
    private val sourceOrder = linkedMapOf<Identifier, Int>()
    private var work: Job? = null
    // Identifies one result list; load-more pages reuse it and every reset starts a new one.
    private var query = java.util.UUID.randomUUID().toString()
    private var epoch = 0L
    private var active = false
    private val detailsGate = Semaphore(4)

    init {
        saved["search.scope"] = state.value.scope?.name.orEmpty()
        viewModelScope.launch {
            history.getFlow().collect { values ->
                mutable.value = state.value.copy(history = values.orEmpty().asReversed().filter(String::isNotBlank).distinct())
            }
        }
        viewModelScope.launch {
            combine(registry.sources, accounts.changes) { sources, generations ->
                sources.filter { SourceCapability.Search in it.metadata.capabilities }
                    .associate { it.metadata.id to it.version(generations) }
            }.distinctUntilChanged().collect { next ->
                cancelWork()
                val old = state.value.sources.associateBy { it.id }
                val unchanged = next.keys.filter { next[it] == versions[it] }.toSet()
                results.entries.removeAll { it.value.book.source !in unchanged }
                val sources = next.map { (id, version) ->
                    old[id]?.takeIf { id in unchanged } ?: SearchHubSource(id, version.metadata.item.name, version.metadata.category,
                        query = if (versions.isEmpty()) null else java.util.UUID.randomUUID().toString())
                }
                versions = next
                next.keys.forEach { if (it !in sourceOrder) sourceOrder[it] = sourceOrder.size }
                mutable.value = state.value.copy(sources = sources, books = rankedResults())
                loadPending()
            }
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) loadPending() else cancelWork()
    }

    fun selectScope(scope: SourceCategory?) {
        if (scope == state.value.scope && state.value.selectedSource == null) return
        saved["search.scope"] = scope?.name.orEmpty()
        saved["sourceNamespace"] = null
        saved["sourceId"] = null
        saved["sourceName"] = null
        reset(state.value.copy(scope = scope, selectedSource = null, selectedSourceName = ""))
        loadPending()
    }

    fun selectSource(id: Identifier) {
        if (id == state.value.selectedSource) return
        val source = state.value.sources.firstOrNull { it.id == id } ?: return
        saved["search.scope"] = ""
        saved["sourceNamespace"] = id.namespace
        saved["sourceId"] = id.id
        saved["sourceName"] = source.name
        reset(state.value.copy(scope = null, selectedSource = id, selectedSourceName = source.name))
        loadPending()
    }

    fun setQuery(value: String) {
        if (value == state.value.query) return
        saved["search.query"] = value
        saved["search.submitted"] = ""
        reset(state.value.copy(query = value, submittedKeyword = ""))
    }

    fun search(value: String = state.value.query) {
        val keyword = value.trim()
        if (keyword.isEmpty()) return
        saved["search.query"] = keyword
        saved["search.submitted"] = keyword
        reset(state.value.copy(query = keyword, submittedKeyword = keyword))
        viewModelScope.launch(io) { history.update { old -> old.filterNot { it == keyword } + keyword } }
        loadPending()
    }

    private fun reset(next: SearchHubState) {
        cancelWork()
        results.clear()
        ranking = SearchRanking(next.submittedKeyword)
        sourceOrder.clear()
        next.sources.forEach { sourceOrder[it.id] = sourceOrder.size }
        query = java.util.UUID.randomUUID().toString()
        mutable.value = next.copy(books = emptyList(), sources = next.sources.map { SearchHubSource(it.id, it.name, it.category) },
            searching = false, stopped = false, limited = false, completed = 0, total = 0, revision = next.revision + 1)
    }

    fun stop() {
        cancelWork()
        mutable.value = state.value.copy(stopped = true)
    }

    fun resume() {
        mutable.value = state.value.copy(stopped = false)
        loadPending()
    }

    fun loadMore() {
        if (state.value.searching || !state.value.hasMore || state.value.stopped) return
        mutable.value = state.value.copy(sources = state.value.sources.map {
            if (it.nextPage != null) it.copy(page = it.nextPage, nextPage = null, pending = true, failure = null,
                receivedBooks = false, addedBooks = false) else it
        })
        loadPending()
    }

    fun retryFailures() {
        if (state.value.searching) return
        mutable.value = state.value.copy(stopped = false, sources = state.value.sources.map {
            if (it.failure != null) it.copy(pending = true, failure = null) else it
        })
        loadPending()
    }

    private fun cancelWork() {
        epoch++
        work?.cancel()
        work = null
        mutable.value = state.value.copy(searching = false)
    }

    private fun loadPending() {
        val snapshot = state.value
        if (!active || snapshot.stopped || snapshot.submittedKeyword.isBlank() || work?.isActive == true) return
        val targets = snapshot.scopedSources.filter { it.pending }
        if (targets.isEmpty()) return
        val token = ++epoch
        val sourceVersions = versions
        mutable.value = snapshot.copy(searching = true,
            completed = snapshot.scopedSources.size - targets.size, total = snapshot.scopedSources.size)
        work = viewModelScope.launch {
            try {
                coordinator.search(snapshot.submittedKeyword, targets.map { SearchRequest(it.id, it.page, it.query) }, query).collect { batch ->
                    val id = batch.request.source
                    if (!active || token != epoch || registry.sources.value.firstOrNull { it.metadata.id == id }
                            ?.version(accounts.changes.value) != sourceVersions[id]) return@collect
                    accept(batch)
                }
            } finally {
                if (token == epoch) mutable.value = state.value.copy(searching = false)
            }
        }
    }

    private fun accept(batch: SearchBatch) {
        val source = state.value.sources.first { it.id == batch.request.source }
        var added = false
        var changed = false
        batch.books.forEach { item ->
            val old = results[item.bookId]
            val evidence = listOfNotNull(old?.evidence, batch.evidence[item.bookId]).minByOrNull { it.ordinal }
            val completeInfo = item.information?.takeUnless { item.bookId in batch.previewIds }
            val preview = completeInfo ?: if (old?.complete == true) old.book.preview else item.information ?: old?.book?.preview
            val complete = completeInfo != null || old?.complete == true
            if (old == null) {
                added = true
                val book = SearchHubBook(item.bookId, source.id, source.name, preview,
                    completeInfo?.let { flowOf(Ok(it)) }
                        ?: details(item.bookId, source.id))
                results[item.bookId] = Candidate(book, ranking.rank(preview, evidence), evidence, complete,
                    SourceBookId.fromStorageKey(item.bookId).remoteId)
                changed = true
            } else if (preview != old.book.preview || evidence != old.evidence || complete != old.complete) {
                results[item.bookId] = old.copy(book = old.book.copy(preview = preview,
                    information = completeInfo?.let { flowOf(Ok(it)) } ?: old.book.information),
                    rank = ranking.rank(preview, evidence), evidence = evidence, complete = complete)
                changed = true
            }
        }
        val ranked = if (changed) rankedResults() else state.value.books
        val receivedBooks = source.receivedBooks || batch.books.isNotEmpty()
        val addedBooks = source.addedBooks || added
        val limited = state.value.limited || batch.limited || ranked.size > SEARCH_RESULT_LIMIT
        if (ranked.size > SEARCH_RESULT_LIMIT) {
            val retained = ranked.take(SEARCH_RESULT_LIMIT).mapTo(hashSetOf()) { it.id }
            results.keys.retainAll(retained)
        }
        mutable.value = state.value.copy(books = ranked.take(SEARCH_RESULT_LIMIT), limited = limited,
            completed = state.value.completed + if (batch.complete) 1 else 0,
            sources = state.value.sources.map {
                if (it.id != source.id) it else it.copy(receivedBooks = receivedBooks, addedBooks = addedBooks,
                    pending = !batch.complete, failure = if (batch.complete) batch.failure else it.failure,
                    nextPage = if (batch.complete) batch.nextPage?.takeIf { (addedBooks || !receivedBooks) && !limited } else it.nextPage)
            })
    }

    private fun rankedResults() = results.values.sortedWith(compareBy<Candidate> { it.rank }
        .thenBy { sourceOrder[it.book.source] ?: Int.MAX_VALUE }
        .thenBy { it.book.source.namespace }.thenBy { it.book.source.id }.thenBy { it.remoteId }).map { it.book }

    private fun details(id: String, source: Identifier): Flow<Result<BookInformation, WebRequestError>> {
        val resultQuery = query
        val version = versions[source]
        val lock = Mutex()
        var cached: Result<BookInformation, WebRequestError>? = null
        var preview: BookInformation? = null
        lateinit var information: Flow<Result<BookInformation, WebRequestError>>
        // Read only on Main. A retained collector must still belong to this exact result list and source session.
        fun currentCandidate() = results[id]?.takeIf {
            resultQuery == query && it.book.information === information &&
                registry.sources.value.firstOrNull { it.metadata.id == source }
                    ?.version(accounts.changes.value) == version
        }
        information = flow {
            lock.withLock {
                val current = withContext(Dispatchers.Main.immediate) { currentCandidate() } ?: return@withLock
                preview = current.book.preview
                (cached ?: preview?.let { Ok(it) })?.let { emit(it) }
                if (cached?.isOk != true) detailsGate.withPermit {
                    if (withContext(Dispatchers.Main.immediate) { currentCandidate() } == null) return@withPermit
                    withTimeout(30_000) { books.getBookInformationFlow(id).collect { result ->
                        val valid = withContext(Dispatchers.Main.immediate) {
                            val old = currentCandidate() ?: return@withContext false
                            val info = result.get()
                            if (info != null && info.id != id) return@withContext false
                            if (info != null && (old.book.preview != info || !old.complete)) {
                                results[id] = old.copy(book = old.book.copy(preview = info),
                                    rank = ranking.rank(info, old.evidence), complete = true)
                                mutable.value = state.value.copy(books = rankedResults())
                            }
                            true
                        }
                        if (!valid) return@collect
                        if (result.isOk || cached?.isOk != true && preview == null) { cached = result; emit(result) }
                    } }
                }
            }
        }.catch { error ->
            currentCoroutineContext().ensureActive()
            // A local preview remains usable if its remote refresh times out or fails.
            if (cached?.isOk != true && preview == null &&
                withContext(Dispatchers.Main.immediate) { currentCandidate() } != null) {
                emit(com.github.michaelbull.result.Err(WebRequestError("Search", "Book information unavailable", error)))
            }
        }.flowOn(io)
        return information
    }

    fun deleteHistory(value: String) { viewModelScope.launch(io) { history.update { it.filterNot { item -> item == value } } } }
    fun clearHistory() { viewModelScope.launch(io) { history.update { emptyList() } } }
}
