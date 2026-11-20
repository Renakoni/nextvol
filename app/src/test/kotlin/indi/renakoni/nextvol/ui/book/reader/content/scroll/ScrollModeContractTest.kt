package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ScrollModeContractTest {
    private val env = ModeTestEnvironment(ForegroundSourceRequest())
    private val continuous = MutableStateFlow(false)
    private val progress = mutableListOf<Pair<String, Float>>()
    private lateinit var mode: ScrollReaderController

    @After fun tearDown() = env.close()

    private fun open(continuousScrolling: Boolean = false, id: String = "requested") {
        continuous.value = continuousScrolling
        val settings = object : ContinuousScrollSettings {
            override fun getFlow() = continuous
            override suspend fun isEnabled() = continuous.value
        }
        mode = ScrollReaderController(
            env.loader, env.records, env.scope, settings,
            { chapter, value -> progress += chapter to value },
            ioDispatcher = env.dispatcher, mainDispatcher = env.dispatcher,
        )
        // Let the initial settings observation run before an explicit chapter request.
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter(id)
        env.runCurrent()
    }

    @Test
    fun singleChapterRestoresProgressInsideTheWriteThenPreloadsWithDefaultPriority() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.6f))
        open()
        env.events.clear()
        env.emit("requested", Ok(env.chapter("payload", "prev", "next", "Title")))
        assertEquals("requested", mode.uiState.contentList[1]!!.first)
        val content = mode.uiState.readingChapterContent!!.get()!!
        assertEquals(listOf("payload", "Title", "prev", "next"), listOf(content.id, content.title, content.prevChapter, content.nextChapter))
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertEquals("requested", env.records.writes.single().lastReadChapterId)
        assertEquals("Title", env.records.writes.single().lastReadChapterTitle)
        assertNotNull(env.records.writes.single().lastReadTime)
        assertEquals(WebDataSourcePriority.Default, env.chapters.requests.single().priority)
        assertEquals(WebDataSourcePriority.Default, env.chapters.preloads.single().priority)
        assertEquals(listOf("render/Title", "write/start/book", "write/end/requested", "preload/start/next", "preload/end/next"), env.events)
        assertNull(mode.uiState.contentList[0])
        assertNull(mode.uiState.contentList[2])
    }

    @Test
    fun refreshKeepsTheVisibleProgressAndErrorDoesNotWriteReadingData() {
        open()
        env.emit("requested", Ok(env.chapter("requested")))
        mode.uiState.readingProgress = 0.8f
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.2f))
        env.emit("requested", Ok(env.chapter("requested", title = "remote")))
        assertEquals(0.8f, mode.uiState.readingProgress)
        val error = Err(WebRequestError("offline", "failed"))
        env.emit("requested", error)
        assertEquals(error, mode.uiState.readingChapterContent)
        assertEquals(2, env.records.writes.size)
    }

    @Test
    fun selfLinksAndDuplicateAdjacentIdsDoNotCreateExtraSubscriptions() {
        open(continuousScrolling = true)
        env.emit("requested", Ok(env.chapter("requested", "requested", "requested")))
        assertEquals(1, env.chapters.requests.size)
        env.emit("requested", Ok(env.chapter("requested", "duplicate", "duplicate")))
        assertEquals(1, env.chapters.requests.size)
        assertNull(mode.uiState.contentList[0])
        assertNull(mode.uiState.contentList[2])
        assertTrue(env.chapters.preloads.isEmpty())
    }

    @Test
    fun crossingIntoTheNextChapterShiftsCachedSlotsBeforeResubscribing() {
        open(continuousScrolling = true, id = "current")
        env.emit("current", Ok(env.chapter("current", "prev", "next")))
        env.emit("next", Ok(env.chapter("next", "current", "later")))
        val oldCurrent = mode.uiState.contentList[1]
        val oldNext = mode.uiState.contentList[2]
        val viewport = Viewport()
        mode.uiState.lazyListState = viewport.state
        mode.uiState.onProgressRestored(viewport.state)
        env.runCurrent()
        viewport.items.value = listOf(item("next", 0, 500))
        env.runCurrent()
        assertEquals("next", mode.uiState.readingChapterId)
        assertSame(oldCurrent, mode.uiState.contentList[0])
        assertSame(oldNext, mode.uiState.contentList[1])
        assertNull(mode.uiState.contentList[2])
        assertEquals(listOf("current", "next"), env.chapters.active.map { it.chapterId })
        assertEquals("next", env.records.writes.last().lastReadChapterId)
        env.emit("next", Ok(env.chapter("next", "current", "later")))
        assertEquals(listOf("current", "later", "next"), env.chapters.active.map { it.chapterId }.sorted())
        assertSame("An unchanged promoted chapter must retain its prepared content",
            oldNext!!.second.get(), mode.uiState.contentList[1]!!.second.get())
        env.emit("current", Ok(env.chapter("current", "prev", "next")))
        assertSame("The shifted previous chapter must also retain its prepared content",
            oldCurrent!!.second.get(), mode.uiState.contentList[0]!!.second.get())
        assertTrue(env.chapters.preloads.isEmpty())
    }

    @Test
    fun visibleFailedNeighbourKeepsCurrentChapterAndRetryCanPromoteItsSuccessfulContent() {
        open(continuousScrolling = true, id = "current")
        env.emit("current", Ok(env.chapter("current", "prev", "next")))
        env.emit("next", Err(WebRequestError("Offline", "Retry after connecting")))
        val viewport = Viewport()
        mode.uiState.lazyListState = viewport.state
        mode.uiState.onProgressRestored(viewport.state)
        mode.uiState.setLazyColumnSize(IntSize(100, 200))
        viewport.items.value = listOf(item("next", 0, 200, readable = false))
        env.runCurrent()
        assertNotNull(mode.uiState.readingChapterContent)
        assertEquals("current", mode.uiState.readingChapterId)
        assertEquals("current", env.records.data.lastReadChapterId)

        assertEquals("next" to false, env.chapters.interactions.last { it.first == "next" })

        mode.uiState.retryChapter("next")
        env.runCurrent()
        assertEquals("next" to true, env.chapters.interactions.last { it.first == "next" })
        assertEquals("current", mode.uiState.readingChapterId)
        env.emit("next", Ok(env.chapter("next", "current", "later")))
        env.runCurrent()
        // The old error block is still the measured item until the next layout pass.
        assertEquals("current", mode.uiState.readingChapterId)
        assertEquals("current", env.records.data.lastReadChapterId)
        viewport.items.value = listOf(item("next", 0, 500))
        env.runCurrent()
        assertEquals("next", mode.uiState.readingChapterId)
        assertEquals("next", env.records.data.lastReadChapterId)
        assertEquals(0.4f, mode.uiState.readingProgress)
        assertEquals(2, mode.uiState.contentList.mapNotNull { it?.first }.toSet().size)
    }

    @Test
    fun stoppingWhileCurrentChapterIsWaitingDoesNotRecreateAdjacentSubscriptions() {
        open(continuousScrolling = true)
        val metadataGate = CompletableDeferred<Unit>()
        env.records.writeGate = metadataGate

        env.emit("requested", Ok(env.chapter("requested", "prev", "next")))
        continuous.value = false
        env.runCurrent()
        assertEquals(listOf("requested"), env.chapters.active.map { it.chapterId })

        metadataGate.complete(Unit)
        env.runCurrent()

        assertEquals(listOf("requested"), env.chapters.active.map { it.chapterId })
        assertNull(mode.uiState.contentList[0])
        assertNull(mode.uiState.contentList[2])
    }

    @Test
    fun adjacentLayoutCannotChangeTheResumeChapterWhileHistoryIsBeingRestored() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("current" to 0.5f))
        open(continuousScrolling = true, id = "current")
        env.emit("current", Ok(env.chapter("current", "prev", "next")))
        env.emit("prev", Ok(env.chapter("prev", "earlier", "current")))
        mode.uiState.setLazyColumnSize(IntSize(100, 200))
        val viewport = Viewport()
        mode.uiState.lazyListState = viewport.state
        viewport.items.value = listOf(item("prev", -200, 1000))
        env.runCurrent()
        assertTrue(mode.uiState.isRestoringProgress)
        assertEquals("current", mode.uiState.readingChapterId)
        assertEquals(0.5f, mode.uiState.readingProgress)
        assertEquals("current", env.records.data.lastReadChapterId)
    }

    @Test
    fun retryAfterCachedBodyFailsBeforeLayoutStillEntersAtTheRequestedStart() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("prev" to 1f))
        open()
        env.emit("requested", Ok(env.chapter("requested", prev = "prev")))
        mode.loadPrevChapter()
        env.runCurrent()
        env.emit("prev", Ok(env.chapter("prev")))
        assertTrue(mode.uiState.isRestoringProgress)
        env.emit("prev", Err(WebRequestError("offline", "refresh failed")))
        mode.uiState.retryChapter("prev")
        env.runCurrent()
        env.emit("prev", Ok(env.chapter("prev")))
        assertEquals(0f, mode.uiState.readingProgress)
    }

    private class Viewport {
        val items = mutableStateOf<List<LazyListItemInfo>>(emptyList())
        val scrolling = mutableStateOf(false)
        private val layout = mockk<LazyListLayoutInfo> { every { visibleItemsInfo } answers { items.value } }
        val state = mockk<LazyListState> {
            every { layoutInfo } returns layout
            every { firstVisibleItemScrollOffset } returns 0
            every { isScrollInProgress } answers { scrolling.value }
        }
    }

    private fun item(chapterId: String, top: Int, height: Int, readable: Boolean = true): LazyListItemInfo = mockk {
        every { key } returns chapterId
        every { offset } returns top
        every { size } returns height
        every { contentType } returns readable
    }
}
