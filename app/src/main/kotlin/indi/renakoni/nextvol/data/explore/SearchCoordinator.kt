package indi.renakoni.nextvol.data.explore

import com.github.michaelbull.result.getOrElse
import hnovel.execution.ExecutionResult
import hnovel.execution.FailureCode
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

internal const val SEARCH_RESULT_LIMIT = 1000
internal const val SEARCH_CONCURRENCY = 8
internal data class SearchRequest(val source: Identifier, val page: Int, val query: String? = null)
internal data class SearchBatch(
    val request: SearchRequest,
    val books: List<SearchResult.MultipleBook> = emptyList(),
    val complete: Boolean = false,
    val nextPage: Int? = null,
    val failure: SourceSearchFailure? = null,
    val limited: Boolean = false,
    val previewIds: Set<String> = emptySet(),
    val evidence: Map<String, SearchEvidence> = emptyMap(),
)

/** Searches one page per source. Both worker count and in-flight requests are bounded. */
@Singleton
class SearchCoordinator internal constructor(private val explore: ExploreRepository, private val io: CoroutineDispatcher) {
    @Inject constructor(explore: ExploreRepository) : this(explore, Dispatchers.IO)

    // Shared across runs, so replacement queries wait for cancelled requests to release their permits.
    private val gate = Semaphore(SEARCH_CONCURRENCY)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun search(keyword: String, requests: List<SearchRequest>, query: String? = null): Flow<SearchBatch> = requests.asFlow()
        .flatMapMerge(concurrency = SEARCH_CONCURRENCY) { request ->
            flow {
                // A replacement query has a separate admission budget. Never release a permit
                // still owned by an old provider which has not finished cancellation.
                var acquired = false
                try {
                    val admitted = withTimeoutOrNull(30_000) { gate.acquire(); acquired = true; true } ?: false
                    if (!admitted) emit(SearchBatch(request, complete = true,
                        failure = SourceSearchFailure(DiscoveryError.Limit, field = "search.queue",
                            diagnostic = ExecutionResult.Failure(FailureCode.Timeout))))
                    else emitAll(load(keyword, request, request.query ?: query))
                } finally { if (acquired) gate.release() }
            }.flowOn(io)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun load(keyword: String, request: SearchRequest, query: String?): Flow<SearchBatch> = flow {
        val pending = mutableListOf<SearchResult.MultipleBook>()
        val seen = hashSetOf<String>()
        var failure: SourceSearchFailure? = null
        var next: Int? = null
        var limited = false
        var progressive = false
        var previews: Set<String> = emptySet()
        var evidence: Map<String, SearchEvidence> = emptyMap()
        try {
            withTimeout(30_000) {
                val session = explore.open(request.source).getOrElse {
                    failure = it
                    return@withTimeout
                }
                if (session.hasPages) {
                    session.page(session.types.first(), keyword, request.page, query).transformWhile { page ->
                        emit(page)
                        !page.complete
                    }.collect { page ->
                        val additions = page.books.filter { item ->
                            item.bookId in seen || seen.size < SEARCH_RESULT_LIMIT && seen.add(item.bookId)
                        }
                        limited = limited || additions.size < page.books.size
                        if (!page.complete) {
                            progressive = true
                            if (additions.isNotEmpty()) emit(SearchBatch(request, additions, previewIds = page.previewIds, evidence = page.evidence))
                        } else if (progressive) {
                            if (additions.isNotEmpty()) emit(SearchBatch(request, additions, previewIds = page.previewIds, evidence = page.evidence))
                        } else {
                            pending += additions
                            previews = page.previewIds
                            evidence = page.evidence
                        }
                        next = page.nextPage
                        page.failure?.let { failure = searchFailure(it) }
                    }
                    if (next?.let { it <= request.page } == true) {
                        next = null
                        failure = SourceSearchFailure(DiscoveryError.InvalidResponse)
                    }
                } else {
                    // Streaming providers have no page cursor. Consume their stream once, retaining partial
                    // results on failure and stopping even if the provider ignores its terminal event.
                    session.search(session.types.first(), keyword).buffer(0).transformWhile { event ->
                        emit(event)
                        event is SearchResult.MultipleBook
                    }.collect { event ->
                        val book = when (event) {
                            is SearchResult.MultipleBook -> event
                            is SearchResult.SingleBook -> SearchResult.MultipleBook(event.bookId)
                            else -> null
                        }
                        if (book != null && seen.add(book.bookId)) pending += book
                        if (event is SearchResult.Error) failure = searchFailure(event.error)
                        if (pending.size >= 16 || seen.size == 1 && pending.isNotEmpty()) {
                            emit(SearchBatch(request, pending.toList()))
                            pending.clear()
                        }
                        if (seen.size >= SEARCH_RESULT_LIMIT) throw ResultLimitReached()
                    }
                }
            }
        } catch (_: ResultLimitReached) {
            limited = true
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure = SourceSearchFailure(DiscoveryError.Limit, field = "search.execution",
                diagnostic = ExecutionResult.Failure(FailureCode.Timeout))
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            failure = searchFailure(error)
        }
        emit(SearchBatch(request, pending.toList(), complete = true, nextPage = next, failure = failure, limited = limited,
            previewIds = previews, evidence = evidence))
    }

    private class ResultLimitReached : RuntimeException()
}
