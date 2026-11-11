package indi.renakoni.nextvol.ui.home.explore.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.github.michaelbull.result.*
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.explore.*
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryVersion
import indi.renakoni.nextvol.ui.home.discovery.version
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class SearchNavigation(val bookId: String, val epoch: Long)

/** A navigation entry owns its source, query, requests and queued navigation. */
@HiltViewModel
class ExploreSearchViewModel internal constructor(
    private val exploreRepository: ExploreRepository,
    bookshelfRepository: BookshelfRepository,
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository,
    private val registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
    route: Route.Main.Explore.Search,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    @Inject constructor(exploreRepository: ExploreRepository, bookshelfRepository: BookshelfRepository,
        bookRepository: BookRepository, userDataRepository: UserDataRepository, registry: WebSourceRegistry,
        accounts: SourceSessionManager, saved: SavedStateHandle) :
        this(exploreRepository, bookshelfRepository, bookRepository, userDataRepository, registry, accounts,
            saved, saved.toRoute<Route.Main.Explore.Search>())

    val sourceId = Identifier(route.namespace, route.sourceId)
    private val mutableState = MutableExploreSearchUiState { saved["search.expanded"] = it }.apply {
        query = saved["search.query"] ?: ""
        submittedKeyword = saved["search.submitted"] ?: ""
        searchType = saved["search.type"] ?: ""
        searchBarExpanded = saved["search.expanded"] ?: submittedKeyword.isBlank()
    }
    val uiState: ExploreSearchUiState = mutableState
    private val history = userDataRepository.stringListUserData(UserDataPath.Search.History.path)
    private var version: DiscoveryVersion? = null
    private var session: SourceSearch? = null
    private var active = false
    private val foreground = ForegroundSourceRequest()
    private var work: Job? = null
    private var suggestions: Job? = null
    // One generation owns both a search/open attempt and any SingleBook navigation it queues.
    // Only cancelWork() and a load() that passes its guards advance it. Query/type/source/account
    // changes, submissions, search retries and leaving the entry cancel that generation.
    // Commands expire on the next advance or while inactive; no-op load() calls must not retire them.
    // suggest() and suggestion-only retries use suggestionSerial without invalidating navigation.
    private var epoch = 0L
    private var suggestionSerial = 0L
    private var page = 1
    private var queryId = java.util.UUID.randomUUID().toString()
    private val scores = mutableMapOf<String, Int>()
    private val outgoing = Channel<SearchNavigation>(Channel.BUFFERED)
    val navigation = outgoing.receiveAsFlow()

    init {
        viewModelScope.launch { history.getFlow().collect { mutableState.historyList = it.orEmpty().reversed() } }
        viewModelScope.launch {
            bookshelfRepository.getAllBookshelfBookIdsFlow().collect { mutableState.allBookshelfBookIds = it }
        }
        viewModelScope.launch {
            combine(registry.restoredSources, accounts.changes) { sources, generations ->
                sources.firstOrNull { it.metadata.id == sourceId }?.version(generations)
            }.distinctUntilChanged().collect { next ->
                cancelWork()
                version = next
                session = null
                mutableState.sourceName = next?.metadata?.item?.name.orEmpty()
                mutableState.searchTypeIdList.clear()
                mutableState.searchTypeNameMap.clear()
                mutableState.searchResult.clear()
                resetPages()
                mutableState.suggestions = emptyList()
                mutableState.suggestionFailure = null
                mutableState.isLoadingComplete = false
                mutableState.failure = unavailableFailure()
                load()
            }
        }
    }

    fun setActive(value: Boolean, retainBrowser: Boolean = false) {
        active = value
        foreground.setActive(value, retainBrowser)
        if (value) {
            load()
            suggest()
        } else if (!retainBrowser || !foreground.verifying) cancelWork()
    }

    fun accepts(command: SearchNavigation) = active && command.epoch == epoch

    fun changeSearchType(id: String) {
        if (id == uiState.searchType || id !in uiState.searchTypeIdList) return
        cancelWork()
        chooseType(id)
        mutableState.searchResult.clear()
        resetPages()
        mutableState.submittedKeyword = ""
        saved["search.submitted"] = ""
        mutableState.isLoadingComplete = false
        mutableState.failure = null
        suggest()
    }

    private fun chooseType(id: String) {
        mutableState.searchType = id
        mutableState.searchTip = session?.types?.firstOrNull { it.type == id }?.tip ?: LocalString("")
        saved["search.type"] = id
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        cancelWork()
        mutableState.query = keyword
        mutableState.submittedKeyword = keyword
        saved["search.query"] = keyword
        saved["search.submitted"] = keyword
        mutableState.setSearchBarExpandedState(false)
        mutableState.searchResult.clear()
        resetPages()
        mutableState.failure = unavailableFailure()
        mutableState.isLoadingComplete = false
        load()
        viewModelScope.launch(io) { history.update { it.filterNot { item -> item == keyword } + keyword } }
    }

    fun retry() {
        if (uiState.failure == null && uiState.suggestionFailure != null) {
            suggest()
            return
        }
        cancelWork()
        mutableState.failure = unavailableFailure()
        mutableState.suggestionFailure = null
        mutableState.isLoadingComplete = false
        mutableState.searchResult.clear()
        resetPages()
        load()
        suggest()
    }

    fun updateSuggestions(keyword: String) {
        if (uiState.query != keyword) {
            cancelWork()
            // Editing retires the old request and any queued direct-book jump. Keep partial results
            // as a static snapshot without a spinner; "complete" prevents load() from restarting the
            // old submitted query while draft edits refresh suggestions. Submitting resumes results.
            mutableState.isLoadingComplete = true
            mutableState.nextPage = null
            mutableState.query = keyword
            saved["search.query"] = keyword
        }
        load()
        suggest()
    }

    private fun suggest() {
        val token = ++suggestionSerial
        suggestions?.cancel()
        mutableState.suggestions = emptyList()
        mutableState.suggestionFailure = null
        val current = session ?: return
        if (!active || uiState.query.isBlank()) return
        val keyword = uiState.query
        val historyText = uiState.historyList.reversed()
        suggestions = viewModelScope.launch {
            try {
                val result = current.suggestions(historyText, keyword)
                if (active && token == suggestionSerial) mutableState.suggestions = result
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (active && token == suggestionSerial) mutableState.suggestionFailure = searchFailure(failure)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun load() {
        if (!active || version == null || uiState.failure != null || work?.isActive == true) return
        if (session != null && (uiState.submittedKeyword.isBlank() || uiState.isLoadingComplete)) return
        val token = ++epoch
        mutableState.isLoading = true
        work = viewModelScope.launch(foreground) {
            try {
                val current = session ?: exploreRepository.open(sourceId).getOrElse {
                    if (token == epoch) {
                        mutableState.failure = it
                        mutableState.isLoadingComplete = true
                    }
                    return@launch
                }.also { opened ->
                    if (token != epoch) return@launch
                    session = opened
                    mutableState.searchTypeIdList.addAll(opened.types.map { it.type })
                    mutableState.searchTypeNameMap.putAll(opened.types.associate { it.type to it.name })
                    chooseType(uiState.searchType.takeIf { it in uiState.searchTypeIdList } ?: opened.types.first().type)
                    suggest()
                }
                if (token != epoch || !active || uiState.submittedKeyword.isBlank() || uiState.isLoadingComplete) return@launch
                val type = current.types.first { it.type == uiState.searchType }
                if (current.hasPages) {
                    val seen = mutableState.searchResult.mapTo(hashSetOf()) { it.first }
                    current.page(type, uiState.submittedKeyword, page, queryId).flowOn(io).transformWhile { batch ->
                        if (token != epoch || !active) return@transformWhile false
                        for (item in batch.books) {
                            batch.scores[item.bookId]?.let { score -> scores[item.bookId] = minOf(scores[item.bookId] ?: Int.MAX_VALUE, score) }
                            val completeInfo = item.information?.takeUnless { item.bookId in batch.previewIds }
                            if (seen.add(item.bookId)) mutableState.searchResult.add(item.bookId to (
                                completeInfo?.let { flowOf(Ok(it)) } ?: bookRepository.getBookInformationFlow(item.bookId)
                                    .filter { it.isOk || item.information == null }
                                    .onStart { item.information?.let { emit(Ok(it)) } }))
                            else if (completeInfo != null) {
                                val index = mutableState.searchResult.indexOfFirst { it.first == item.bookId }
                                mutableState.searchResult[index] = item.bookId to flowOf(Ok(completeInfo))
                            }
                        }
                        if (scores.isNotEmpty()) mutableState.searchResult.sortWith(compareBy({ scores[it.first] ?: 30 }, { it.first }))
                        if (batch.complete) {
                            mutableState.nextPage = batch.nextPage?.takeIf { it > page }
                            mutableState.failure = batch.failure?.let(::searchFailure)
                        }
                        emit(Unit)
                        !batch.complete
                    }.collect()
                    if (token == epoch) mutableState.isLoadingComplete = true
                    return@launch
                }
                val seen = mutableSetOf<String>()
                mutableState.searchResult.clear()
                current.search(type, uiState.submittedKeyword).flowOn(io).takeWhile { event ->
                    if (token != epoch || !active) return@takeWhile false
                    mutableState.isLoading = false
                    when (event) {
                        is SearchResult.MultipleBook -> if (seen.add(event.bookId)) {
                            mutableState.searchResult.add(event.bookId to (event.information?.let { flowOf(Ok(it)) }
                                ?: bookRepository.getBookInformationFlow(event.bookId)))
                        }
                        is SearchResult.SingleBook -> {
                            mutableState.setSearchBarExpandedState(true)
                            outgoing.send(SearchNavigation(event.bookId, token))
                        }
                        is SearchResult.Error -> mutableState.failure = searchFailure(event.error)
                        else -> Unit
                    }
                    event is SearchResult.MultipleBook
                }.collect()
                if (token == epoch) mutableState.isLoadingComplete = true
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (token == epoch) {
                    mutableState.failure = searchFailure(failure)
                    mutableState.isLoadingComplete = true
                }
            } finally {
                if (token == epoch) mutableState.isLoading = false
            }
        }
    }

    private fun cancelWork() {
        epoch++
        suggestionSerial++
        work?.cancel()
        work = null
        suggestions?.cancel()
        suggestions = null
        mutableState.isLoading = registry.restoration.value == SourceRestoration.Restoring
    }

    private fun unavailableFailure(): SourceSearchFailure? = if (version != null ||
        registry.restoration.value == SourceRestoration.Restoring) null else SourceSearchFailure(
            if (registry.restoration.value == SourceRestoration.Failed) DiscoveryError.InvalidRules else DiscoveryError.Unavailable)

    fun loadMore() {
        val next = uiState.nextPage ?: return
        if (!active || work?.isActive == true) return
        page = next
        mutableState.nextPage = null
        mutableState.failure = null
        mutableState.isLoadingComplete = false
        load()
    }

    private fun resetPages() {
        page = 1
        queryId = java.util.UUID.randomUUID().toString()
        scores.clear()
        mutableState.nextPage = null
    }

    fun deleteHistory(value: String) {
        viewModelScope.launch(io) { history.update { it.filterNot { item -> item == value } } }
    }

    fun clearAllHistory() { viewModelScope.launch(io) { history.update { emptyList() } } }
}
