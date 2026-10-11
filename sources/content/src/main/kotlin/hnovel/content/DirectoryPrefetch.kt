package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.*

/** A sliding window bounds both requests and retained responses. Parsing stays in the caller's source turn. */
internal class DirectoryPrefetch(private val scope: CoroutineScope, private val requests: List<BrokerRequest>,
    private val load: suspend (BrokerRequest) -> Page) {
    companion object { const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 }
    data class Page(val request: BrokerRequest, val response: BrokerResponse, val stableCookies: Boolean)
    private val pending = ArrayDeque<Pair<String, Deferred<Result<Page>>>>()
    private var index = 0

    fun start() {
        while (pending.size < BrokerLimits.DEFAULT_CONCURRENCY && index < requests.size) {
            val request = requests[index++]
            pending.addLast(request.url to scope.async {
                try { Result.success(load(request)) }
                catch (failure: SourceContentException) { Result.failure(failure) }
            })
        }
    }

    suspend fun read(url: String): Page? {
        if (pending.firstOrNull()?.first != url) { stop(); return null }
        start()
        val page = pending.removeFirst().second.await().getOrElse { failure ->
            // Prefetch has a smaller body budget. A large page retains the ordinary single-read limit.
            if (failure is SourceContentException && failure.code == ContentError.Limit) { stop(); return null }
            throw failure
        }
        if (!page.stableCookies || page.response.finalUrl != page.request.url) stop()
        // Refill on the next read, after the caller has released the previous document.
        return page
    }

    suspend fun stop() {
        index = requests.size
        val jobs = pending.map { it.second }
        pending.clear()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }
}
