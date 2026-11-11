package indi.renakoni.nextvol.data.image

import hnovel.execution.SourceWorkRequest
import coil3.decode.DataSource
import hnovel.network.RequestRetryContext
import indi.renakoni.nextvol.data.web.BackgroundSourceRequest
import indi.renakoni.nextvol.data.web.DownloadSourceRequest
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.data.web.SourceRequestOwner
import indi.renakoni.nextvol.data.web.SourceRequestVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** One runtime generation owns the bytes; callers retain their own decoders and cancellation. */
internal class SourceImageRequests(private val scope: CoroutineScope) {
    data class Bytes(val value: ByteArray, val dataSource: DataSource)
    private data class Key(val resource: String, val force: Boolean,
        val foreground: ForegroundSourceRequest?, val background: BackgroundSourceRequest?,
        val download: DownloadSourceRequest?, val retry: RequestRetryContext?)
    private class Entry(val result: Deferred<Bytes>, var consumers: Int = 0)
    private val requests = mutableMapOf<Key, Entry>()

    suspend fun get(resource: String, force: Boolean, fetch: suspend () -> Bytes): Bytes {
        val context = currentCoroutineContext()
        val foreground = context[ForegroundSourceRequest]?.takeIf { it.allowsInteraction }
        val key = Key(resource, force, foreground, context[BackgroundSourceRequest],
            context[DownloadSourceRequest], context[RequestRetryContext])
        val entry = synchronized(requests) {
            requests.getOrPut(key) {
                val ownership = (context[ForegroundSourceRequest] ?: EmptyCoroutineContext) +
                    (context[BackgroundSourceRequest] ?: EmptyCoroutineContext) +
                    (context[DownloadSourceRequest] ?: EmptyCoroutineContext) +
                    (context[RequestRetryContext] ?: EmptyCoroutineContext) +
                    (context[SourceRequestVersion] ?: EmptyCoroutineContext) +
                    (context[SourceRequestOwner] ?: EmptyCoroutineContext) +
                    (context[SourceWorkRequest] ?: EmptyCoroutineContext)
                Entry(scope.async(ownership, start = CoroutineStart.LAZY) { fetch() })
            }.also { it.consumers++ }
        }
        try {
            return entry.result.await()
        } finally {
            synchronized(requests) {
                if (--entry.consumers == 0) {
                    requests.remove(key)
                    entry.result.cancel()
                }
            }
        }
    }
}
