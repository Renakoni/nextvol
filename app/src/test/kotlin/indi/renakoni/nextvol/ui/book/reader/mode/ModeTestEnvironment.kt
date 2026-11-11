package indi.renakoni.nextvol.ui.book.reader.mode

import androidx.compose.runtime.snapshots.Snapshot
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.ChapterSource
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.ui.book.reader.content.ContentRenderer
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.content.ContentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Common controlled I/O, with no dependency on either reading mode implementation. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ModeTestEnvironment(context: CoroutineContext = EmptyCoroutineContext) {
    val scheduler = TestCoroutineScheduler()
    val dispatcher = StandardTestDispatcher(scheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher + context)
    val events = mutableListOf<String>()
    val chapters = Chapters(events)
    val records = Records(events)
    val renderer = mockk<ContentRenderer> {
        every { getContentDataFromJson(any()) } answers {
            events += "render/${firstArg<JsonObject>().getValue("token").jsonPrimitive.content}"
            ContentData.empty()
        }
    }
    val loader = ReaderChapterLoader(chapters, renderer, io.mockk.mockk(relaxed = true))

    fun runCurrent() {
        Snapshot.sendApplyNotifications()
        scheduler.runCurrent()
    }

    fun close() {
        scope.cancel()
        runCurrent()
    }

    fun emit(id: String, result: Result<ChapterContent, WebRequestError>) {
        check(chapters.stream(id).tryEmit(result))
        runCurrent()
    }

    fun chapter(id: String, prev: String? = null, next: String? = null, title: String = id) =
        ChapterContent(id, title, buildJsonObject { put("token", title) }, prev, next)

    class Chapters(private val events: MutableList<String>) : ChapterSource {
        data class Request(val chapterId: String, val bookId: String, val priority: WebDataSourcePriority)
        val requests = mutableListOf<Request>()
        val active = mutableListOf<Request>()
        val preloads = mutableListOf<Request>()
        val interactions = mutableListOf<Pair<String, Boolean?>>()
        var preloadGate: CompletableDeferred<Unit>? = null
        private val streams = mutableMapOf<String, MutableSharedFlow<Result<ChapterContent, WebRequestError>>>()
        fun stream(id: String) = streams.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = 16) }

        override fun getBookVolumesFlow(id: String, priority: WebDataSourcePriority): Flow<Result<BookVolumes, WebRequestError>> =
            error("A reading mode must not request the book directory")

        override fun getChapterContentFlow(chapterId: String, bookId: String, priority: WebDataSourcePriority): Flow<Result<ChapterContent, WebRequestError>> {
            val request = Request(chapterId, bookId, priority)
            requests += request
            return flow {
                interactions += chapterId to currentCoroutineContext()[ForegroundSourceRequest]?.allowsInteraction
                active += request
                events += "subscribe/$chapterId"
                try {
                    stream(chapterId).collect { emit(it) }
                } finally {
                    active.remove(request)
                    events += "cancel/$chapterId"
                }
            }
        }

        override suspend fun preloadChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority) {
            events += "preload/start/$chapterId"
            preloads += Request(chapterId, bookId, priority)
            preloadGate?.await()
            events += "preload/end/$chapterId"
        }
    }

    class Records(private val events: MutableList<String>) : BookReadingDataAccess {
        override fun progressRevision(): Long = 0L
        override suspend fun updateChapterProgress(
            bookId: String, chapterId: String, revision: Long,
            update: (UserReadingData) -> UserReadingData,
        ): Boolean {
            updateUserReadingData(bookId, update)
            return true
        }
        var data = UserReadingData("book")
        val writes = mutableListOf<UserReadingData>()
        var readGate: CompletableDeferred<Unit>? = null
        val readGates = ArrayDeque<CompletableDeferred<Unit>>()
        var writeGate: CompletableDeferred<Unit>? = null
        val writeGates = ArrayDeque<CompletableDeferred<Unit>>()
        val nonCancellableWriteGates = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun getUserReadingData(bookId: String): UserReadingData {
            events += "read/start/$bookId"
            readGates.removeFirstOrNull()?.await() ?: readGate?.await()
            events += "read/end/$bookId"
            return data
        }

        override suspend fun updateUserReadingData(id: String, update: (UserReadingData) -> UserReadingData) {
            events += "write/start/$id"
            nonCancellableWriteGates.removeFirstOrNull()?.let {
                withContext(NonCancellable) { it.await() }
            } ?: writeGates.removeFirstOrNull()?.await() ?: writeGate?.await()
            data = update(data)
            writes += data
            events += "write/end/${data.lastReadChapterId}"
        }
    }
}
