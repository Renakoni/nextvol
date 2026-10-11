package indi.renakoni.nextvol.defaultplugin.wenku8.search

import indi.renakoni.nextvol.data.explore.SearchPage
import indi.renakoni.nextvol.data.explore.SearchEvidence
import indi.renakoni.nextvol.data.explore.SearchRanking
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Wenku8SearchSupport @Inject constructor(
    internal val catalog: Wenku8SearchCatalog,
    internal val bangumi: BangumiSearchExpansion,
)

/** One submitted query owns a fixed catalogue, cursors and emitted IDs, including after a retry. */
internal class Wenku8SearchSession(
    private val support: Wenku8SearchSupport,
    private val type: String,
    keyword: String,
    private val original: suspend (String, Int) -> SearchPage,
) {
    private val text = Wenku8SearchText.query(keyword)
    private val key = Wenku8SearchText.key(keyword)
    private val explicit = Wenku8SearchText.explicitId(keyword)
    private val authorOnly = type == "author"
    private val normalized = SearchRanking.normalize(keyword)
    private val lock = Mutex()
    private var catalog: List<Wenku8SearchEntry>? = null
    private var local: List<Pair<Wenku8SearchEntry, Int>>? = null
    private data class Page(val result: SearchPage, val sourceMore: Boolean, val bangumiMore: Boolean)
    private val pages = mutableMapOf<Int, Page>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun page(number: Int): Flow<SearchPage> = channelFlow {
        require(number > 0)
        lock.withLock {
            pages[number]?.takeIf { it.result.failure == null }?.let { send(it.result); return@withLock }
            val catalogVersion = support.catalog.generation
            val entries = catalog ?: support.catalog.snapshot().also { catalog = it; support.catalog.refreshInBackground() }
            if (explicit != null && !authorOnly) {
                send(SearchPage(listOf(SearchResult.MultipleBook(explicit, entries.find { it.id == explicit }?.preview())), null,
                    scores = mapOf(explicit to 0), previewIds = setOf(explicit),
                    evidence = mapOf(explicit to SearchEvidence.ExplicitId)))
                return@withLock
            }
            val hits = local ?: entries.mapNotNull { entry ->
                val score = entry.score(key, authorOnly)
                    ?: 50.takeIf { !authorOnly && text == entry.id }
                score?.let { entry to it }
            }.sortedWith(compareBy<Pair<Wenku8SearchEntry, Int>> { it.second }.thenBy { it.first.id.toInt() }).also { local = it }
            val seen = pages.filterKeys { it < number }.values.flatMap { it.result.books }.mapTo(hashSetOf()) { it.bookId }
            val result = linkedMapOf<String, SearchResult.MultipleBook>()
            val scores = mutableMapOf<String, Int>()
            val evidence = mutableMapOf<String, SearchEvidence>()
            val resultsLock = Mutex()
            val errors = mutableListOf<Throwable>()
            var sourceMore = false
            var bangumiMore = false
            suspend fun accept(books: List<SearchResult.MultipleBook>, ranks: Map<String, Int>,
                matches: Map<String, SearchEvidence> = emptyMap()) = resultsLock.withLock {
                val changed = mutableListOf<SearchResult.MultipleBook>()
                for (book in books) {
                    if (book.bookId in seen) continue
                    val old = result[book.bookId]
                    val score = ranks[book.bookId] ?: 30
                    val better = score < (scores[book.bookId] ?: Int.MAX_VALUE)
                    val match = listOfNotNull(evidence[book.bookId], matches[book.bookId]).minByOrNull { it.ordinal }
                    val metadata = old?.information == null || book.information?.lastUpdated?.let {
                        it != java.time.LocalDateTime.MIN
                    } == true
                    if (old == null || better || metadata || match != evidence[book.bookId]) {
                        val next = if (metadata || old == null) book else old
                        result[book.bookId] = next
                        scores[book.bookId] = minOf(score, scores[book.bookId] ?: Int.MAX_VALUE)
                        if (match != null) evidence[book.bookId] = match
                        changed += next
                    }
                }
                if (changed.isNotEmpty()) send(SearchPage(changed, null, complete = false,
                    scores = changed.associate { it.bookId to scores.getValue(it.bookId) }, previewIds = previewIds(changed),
                    evidence = changed.mapNotNull { book -> evidence[book.bookId]?.let { book.bookId to it } }.toMap()))
            }
            suspend fun acceptEntries(matches: List<Pair<Wenku8SearchEntry, Int>>,
                evidence: Map<String, SearchEvidence> = emptyMap()) = accept(
                matches.map { SearchResult.MultipleBook(it.first.id, it.first.preview()) },
                matches.associate { it.first.id to it.second }, evidence)
            suspend fun failed(error: Throwable) { resultsLock.withLock { errors += error } }
            val offset = (number - 1) * 20
            coroutineScope {
                launch {
                    val local = hits.drop(offset).take(20)
                    acceptEntries(local, local.associate { (entry, _) -> entry.id to when {
                        !authorOnly && (Wenku8SearchText.names(entry.title) + entry.aliases)
                            .any { SearchRanking.normalize(it) == normalized } -> SearchEvidence.VerifiedAlias
                        else -> SearchEvidence.Loose
                    } })
                }
                if (number == 1 || pages[number - 1]?.sourceMore != false) launch {
                    try {
                        val page = original(text, number)
                        sourceMore = page.nextPage != null
                        page.failure?.let { failed(it) }
                        accept(page.books, page.books.associate { book ->
                            book.bookId to (book.information?.let { info ->
                                Wenku8SearchEntry(book.bookId, info.title, info.author, listOf(info.subtitle)).score(key, authorOnly)
                            } ?: 30)
                        }, page.books.filter { !authorOnly && it.information?.subtitle?.let { alias ->
                            SearchRanking.normalize(alias) == normalized } == true
                        }.associate { it.bookId to SearchEvidence.VerifiedAlias })
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        failed(error)
                    }
                }
                if (!authorOnly && key.length >= 2 && text.toLongOrNull() == null && !text.contains("://") &&
                    (number == 1 || pages[number - 1]?.bangumiMore != false)) launch {
                    // Supplemental services must neither fail the source nor consume its host timeout.
                    withTimeoutOrNull(20_000) { support.bangumi.search(text, number, entries)
                        .catch { currentCoroutineContext().ensureActive() }.collect { expansion ->
                        acceptEntries(expansion.matches, expansion.evidence)
                        bangumiMore = bangumiMore || expansion.hasMore
                        // Only unresolved families reach the rate-limited source search.
                        for (name in expansion.names) {
                            try {
                                val page = original(Wenku8SearchText.query(name), 1)
                                accept(page.books, page.books.associate { it.bookId to 15 },
                                    page.books.associate { it.bookId to SearchEvidence.Related })
                                page.failure?.let { failed(it) }
                            } catch (error: Exception) {
                                currentCoroutineContext().ensureActive()
                                failed(error)
                            }
                        }
                    } }
                }
            }
            val more = offset + 20 < hits.size || sourceMore || bangumiMore
            val ordered = result.values.sortedWith(compareBy<SearchResult.MultipleBook> { scores[it.bookId] ?: 30 }
                .thenBy { it.bookId.toIntOrNull() ?: Int.MAX_VALUE })
            val final = SearchPage(ordered, (number + 1).takeIf { more }, failure = errors.firstOrNull(),
                scores = scores.toMap(), previewIds = previewIds(ordered), evidence = evidence.toMap())
            // Metadata storage is optional; a cache write cannot turn useful search results into a failure.
            try { support.catalog.remember(ordered.mapNotNull { it.information }, catalogVersion) }
            catch (error: Exception) { currentCoroutineContext().ensureActive() }
            pages[number] = Page(final, sourceMore, bangumiMore)
            send(final)
        }
    }.flowOn(Dispatchers.IO)

    private fun previewIds(books: List<SearchResult.MultipleBook>) = books.filter {
        it.information?.lastUpdated == java.time.LocalDateTime.MIN
    }.mapTo(hashSetOf()) { it.bookId }
}
