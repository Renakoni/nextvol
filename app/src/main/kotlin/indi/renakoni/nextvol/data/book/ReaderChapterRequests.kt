package indi.renakoni.nextvol.data.book

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getError
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.data.web.SourceRuntime
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only the current reader window owns these unfinished requests; disk remains the completed cache. */
class ReaderChapterRequests(private val scope: CoroutineScope) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReaderChapterRequests>
    private data class RequestKey(val runtime: SourceRuntime, val chapter: SourceChapterId, val fresh: Boolean)
    private class Entry(val result: Deferred<Result<ChapterContent, WebRequestError>>, val interactive: Boolean)
    private val requests = mutableMapOf<RequestKey, Entry>()
    private var retained = emptySet<SourceChapterId>()

    fun retain(bookId: String, chapterIds: List<String>) = synchronized(requests) {
        val book = BookIdentity.book(bookId)
        retained = chapterIds.map { BookIdentity.chapter(it, book) }.toSet()
        val removed = requests.keys.filter { it.chapter !in retained }
        removed.forEach { requests.remove(it)?.result?.cancel() }
    }

    fun clear() = synchronized(requests) {
        retained = emptySet()
        requests.values.forEach { it.result.cancel() }
        requests.clear()
    }

    internal suspend fun get(runtime: SourceRuntime, chapter: SourceChapterId, fresh: Boolean,
        fetch: suspend () -> Result<ChapterContent, WebRequestError>): Result<ChapterContent, WebRequestError> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val interactive = context[ForegroundSourceRequest]?.let { it.allowsInteraction && it.isActive } == true
        val key = RequestKey(runtime, chapter, fresh)
        val entry = synchronized(requests) {
            if (chapter !in retained) throw CancellationException("Chapter left the reading window")
            requests.getOrPut(key) {
                Entry(scope.async(context.minusKey(Job), start = CoroutineStart.LAZY) { fetch() }, interactive)
            }
        }
        try {
            val result = entry.result.await()
            // A background verification failure does not acquire interaction retroactively.
            // The current foreground retries through the existing verification continuation.
            return if (interactive && !entry.interactive && result.getError()?.kind in setOf(
                    WebRequestErrorKind.VerificationRequired, WebRequestErrorKind.AuthenticationRequired)) fetch() else result
        } finally {
            if (entry.result.isCompleted) synchronized(requests) {
                if (requests[key] === entry) requests.remove(key)
            }
        }
    }
}
