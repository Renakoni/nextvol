package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.IntSize
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.reading.ReaderRecordStore
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipReaderController
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderLeafMapping
import indi.renakoni.nextvol.ui.book.reader.content.flip.ReaderSpreadPagerState
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ContinuousScrollSettings
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollReaderController
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.UserReadingData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderProgressPersistenceTest {
    private val env = ModeTestEnvironment()
    private var data = UserReadingData(
        id = "book", readingProgress = 0.6f,
        currentChapterReadingProgressMap = mapOf("chapter" to 0.6f),
        maxChapterReadingProgressMap = mapOf("chapter" to 0.6f),
    )
    private val writes = mutableListOf<UserReadingData>()
    private val finished = mutableListOf<String>()
    private val store = mockk<ReaderRecordStore> {
        every { progressRevision() } returns 0L
        coEvery { updateChapterProgress(any(), any(), any(), any()) } answers {
            data = arg<(UserReadingData) -> UserReadingData>(3)(data)
            writes += data
            true
        }
        coEvery { markBookFinished(any()) } answers { finished += firstArg<String>() }
    }
    private val records = ReaderReadingRecords(
        store, env.scope, env.scope, { "book" }, { "Chapter" }, { setOf("chapter") }, ioDispatcher = env.dispatcher,
    )

    @After fun close() = env.close()

    private fun assertHistoryUnchanged() {
        assertTrue(writes.isEmpty())
        assertEquals(0.6f, data.currentChapterReadingProgressMap.getValue("chapter"))
        assertEquals(0.6f, data.maxChapterReadingProgressMap.getValue("chapter"))
        assertEquals(0.6f, data.readingProgress)
        assertTrue(finished.isEmpty())
    }

    private fun assertReadToEnd() {
        assertEquals(listOf(1f), writes.map { it.currentChapterReadingProgressMap.getValue("chapter") })
        assertEquals(1f, data.maxChapterReadingProgressMap.getValue("chapter"))
        assertEquals(1f, data.readingProgress)
        assertEquals(listOf("book"), finished)
    }

    @Test fun flipReflowDoesNotWriteTheNewSpreadEndButRealTurningDoes() {
        env.records.data = data
        val mode = FlipReaderController(env.loader, env.records, env.scope,
            { id, value -> records.saveProgress(id, value) }, ioDispatcher = env.dispatcher)
        mode.changeBookId("book")
        mode.changeChapter("chapter")
        env.runCurrent()
        env.emit("chapter", Ok(env.chapter("chapter")))
        mode.updatePagerState(pager(ReaderLeafMapping(10, 1), mutableIntStateOf(0)))
        env.runCurrent()
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertHistoryUnchanged()
        val screen = mutableIntStateOf(1)
        mode.uiState.updateAnchoredPageState(pager(ReaderLeafMapping(5, 2), screen))
        repeat(5) { env.runCurrent() }
        assertEquals(0.8f, mode.uiState.readingProgress)
        mode.flushProgress()
        // The reader records layer can flush outstanding work, but receives no new 80% event.
        env.scope.launch { records.awaitProgress() }
        env.runCurrent()
        assertHistoryUnchanged()
        screen.intValue = 2
        env.runCurrent()
        assertReadToEnd()
    }

    @Test fun chapterScrollReflowAndBackgroundDoNotAdvanceHistory() = scrollReflow(false)
    @Test fun continuousScrollReflowAndBackgroundDoNotAdvanceHistory() = scrollReflow(true)

    private fun scrollReflow(continuous: Boolean) {
        env.records.data = data
        val settings = object : ContinuousScrollSettings {
            override fun getFlow() = MutableStateFlow(continuous)
            override suspend fun isEnabled() = continuous
        }
        val mode = ScrollReaderController(env.loader, env.records, env.scope, settings,
            { id, value -> records.saveProgress(id, value) }, env.dispatcher, env.dispatcher)
        mode.changeBookId("book")
        mode.changeChapter("chapter")
        env.runCurrent()
        env.emit("chapter", Ok(env.chapter("chapter")))
        val scrollOffset = mutableIntStateOf(500)
        val scrolling = mutableStateOf(false)
        val item = mockk<LazyListItemInfo> {
            every { key } returns "chapter"
            every { index } returns 1
            every { contentType } returns true
            every { size } returns 1000
            every { offset } answers { -scrollOffset.intValue }
        }
        val layout = mockk<LazyListLayoutInfo> { every { visibleItemsInfo } returns listOf(item) }
        val list = mockk<LazyListState> {
            every { layoutInfo } returns layout
            every { firstVisibleItemIndex } returns 1
            every { firstVisibleItemScrollOffset } answers { scrollOffset.intValue }
            every { isScrollInProgress } answers { scrolling.value }
        }
        val ui = mode.uiState
        ui.lazyListState = list
        ui.setLazyColumnSize(IntSize(600, 100))
        ui.onProgressRestoring(list)
        ui.onProgressRestored(list)
        env.runCurrent()
        assertHistoryUnchanged()
        ui.setLazyColumnSize(IntSize(900, 300))
        ui.onProgressRestored(list)
        repeat(5) {
            scrolling.value = true
            env.runCurrent()
            scrolling.value = false
            env.runCurrent()
        }
        assertEquals(0.8f, ui.readingProgress)
        ui.writeProgressRightNow()
        env.runCurrent()
        assertHistoryUnchanged()
        scrollOffset.intValue = 700
        ui.onReadingPositioned(list)
        env.runCurrent()
        assertReadToEnd()
    }

    private fun pager(mapping: ReaderLeafMapping, page: MutableIntState): PagerState = mockk<ReaderSpreadPagerState> {
        every { leaves } returns mapping
        every { pageCount } returns mapping.screenCount
        every { settledPage } answers { page.intValue }
        every { currentPage } answers { page.intValue }
        every { targetPage } answers { page.intValue }
        every { isScrollInProgress } returns false
        coEvery { scrollToPage(any(), any()) } answers { page.intValue = firstArg() }
    }
}
