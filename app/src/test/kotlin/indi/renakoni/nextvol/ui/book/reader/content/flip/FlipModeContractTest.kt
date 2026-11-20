package indi.renakoni.nextvol.ui.book.reader.content.flip

import android.app.Application
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.mutableIntStateOf
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class FlipModeContractTest {
    private val env = ModeTestEnvironment()
    private val progress = mutableListOf<Pair<String, Float>>()
    private val mode = FlipReaderController(
        env.loader, env.records, env.scope, { id, value -> progress += id to value },
        ioDispatcher = env.dispatcher,
    )

    @After fun tearDown() = env.close()

    private fun open(id: String = "requested") {
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter(id)
        env.runCurrent()
    }

    @Test fun speechAnchorOverridesEvenALateHistoryRecovery() {
        val gate = CompletableDeferred<Unit>()
        env.records.readGates += gate
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.9f))
        open()
        val targets = mutableListOf<Int>()
        val page = mutableIntStateOf(2)
        val pager = pager(10, page, targets)
        mode.uiState.updateSpeechPageState(pager)
        env.runCurrent()
        gate.complete(Unit)
        env.runCurrent()
        assertTrue("Old recovery must not move the speech page: $targets", targets.isEmpty())
        assertEquals(2, pager.currentPage)
        assertEquals(0.3f, mode.uiState.readingProgress)
    }

    @Test
    fun successMapsThePayloadButPersistsTheRequestedIdBeforePreloading() {
        open()
        env.events.clear()
        env.emit("requested", Ok(env.chapter("payload", "prev", "next", "Title")))
        assertEquals("requested", mode.uiState.readingChapterId)
        val content = mode.uiState.readingChapterContent!!.get()!!
        assertEquals(listOf("payload", "Title", "prev", "next"), listOf(content.id, content.title, content.prevChapter, content.nextChapter))
        assertEquals("requested", env.records.writes.single().lastReadChapterId)
        assertEquals("Title", env.records.writes.single().lastReadChapterTitle)
        assertNotNull(env.records.writes.single().lastReadTime)
        assertEquals(WebDataSourcePriority.High, env.chapters.requests.single().priority)
        assertEquals(WebDataSourcePriority.Default, env.chapters.preloads.single().priority)
        assertEquals(listOf("render/Title", "write/start/book", "write/end/requested", "preload/start/next", "preload/end/next"), env.events)
    }

    @Test
    fun aSuspendedOlderMetadataWriteCannotOverwriteTheNewerChapter() {
        open("first")
        val firstWriteGate = CompletableDeferred<Unit>()
        env.records.nonCancellableWriteGates += firstWriteGate

        env.emit("first", Ok(env.chapter("first")))
        mode.changeChapter("second")
        env.runCurrent()
        env.emit("second", Ok(env.chapter("second")))
        firstWriteGate.complete(Unit)
        env.runCurrent()

        assertEquals("second", env.records.data.lastReadChapterId)
        assertEquals(listOf(null, "second"), env.records.writes.map { it.lastReadChapterId })
    }

    @Test
    fun changingChapterCancelsOlderSubscriptionAndIgnoresItsLateResult() {
        open("first")
        mode.changeChapter("second")
        env.runCurrent()
        env.emit("second", Ok(env.chapter("second")))
        env.emit("first", Ok(env.chapter("first")))
        assertEquals("second", mode.uiState.readingChapterId)
        assertEquals(listOf("second"), env.chapters.active.map { it.chapterId })
        env.close()
        assertTrue(env.chapters.active.isEmpty())
        val writes = env.records.writes.size
        env.emit("first", Ok(env.chapter("late")))
        assertEquals(writes, env.records.writes.size)
    }

    @Test
    fun changingChapterDiscardsTheOlderPendingProgressRecovery() {
        val gate = CompletableDeferred<Unit>()
        env.records.readGate = gate
        env.records.data = env.records.data.copy(
            currentChapterReadingProgressMap = mapOf("first" to 0.2f, "second" to 0.8f),
        )
        open("first")
        mode.changeChapter("second")
        env.runCurrent()

        gate.complete(Unit)
        env.runCurrent()
        val targets = mutableListOf<Int>()
        mode.updatePagerState(pager(10, targets = targets))
        env.runCurrent()
        assertEquals(listOf(7), targets)
    }

    @Test
    fun lateStoredProgressDoesNotOverrideAnInFlightPageChange() {
        val gate = CompletableDeferred<Unit>()
        env.records.readGate = gate
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.75f))
        open()
        val targets = mutableListOf<Int>()
        val pager = mockk<PagerState> {
            every { pageCount } returns 4
            every { settledPage } returns 0
            every { currentPage } returns 1
            every { targetPage } returns 1
            every { isScrollInProgress } returns true
            coEvery { scrollToPage(any(), any()) } answers { targets += firstArg<Int>() }
        }
        mode.updatePagerState(pager)
        env.runCurrent()
        gate.complete(Unit)
        env.runCurrent()
        assertTrue(targets.isEmpty())
    }

    @Test
    fun emptyPagerDuringRepaginationPreservesProgressUntilTheNewPagesAreReady() {
        open()
        env.emit("requested", Ok(env.chapter("requested")))
        mode.updatePagerState(pager(10, mutableIntStateOf(5)))
        env.runCurrent()
        assertEquals(0.6f, mode.uiState.readingProgress)
        progress.clear()

        mode.updatePagerState(pager(0))
        env.runCurrent()
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertTrue(progress.isEmpty())

        val page = mutableIntStateOf(0)
        val targets = mutableListOf<Int>()
        val rebuilt = pager(20, page, targets)
        coEvery { rebuilt.scrollToPage(any(), any()) } answers {
            val target = firstArg<Int>()
            targets += target
            page.intValue = target
        }
        mode.updatePagerState(rebuilt)
        env.runCurrent()
        assertEquals(listOf(11), targets)
        assertEquals(0.6f, mode.uiState.readingProgress)
        assertTrue(progress.isEmpty())
        page.intValue = 12
        env.runCurrent()
        assertEquals(listOf("requested" to 0.65f), progress)
    }

    @Test fun programmaticPositioningCannotPublishAnIntermediateOrRestoredScreen() {
        open()
        env.emit("requested", Ok(env.chapter("requested")))
        val screen = mutableIntStateOf(0)
        val spread = spreadPager(ReaderLeafMapping(5, 2), screen)
        mode.updatePagerState(spread)
        env.runCurrent()
        progress.clear()
        mode.uiState.onProgressRestoring(spread)
        screen.intValue = 1
        env.runCurrent()
        assertTrue(progress.isEmpty())
        mode.uiState.updateAnchoredPageState(spread)
        repeat(5) { env.runCurrent() }
        assertEquals(0.8f, mode.uiState.readingProgress)
        assertTrue(progress.isEmpty())
        screen.intValue = 2
        env.runCurrent()
        assertEquals(listOf("requested" to 1f), progress)
    }

    @Test fun oldPercentageUsesRealLeafCountEvenWhenPagerHasFewerScreens() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("requested" to 0.85f))
        open()
        val targets = mutableListOf<Int>()
        mode.updatePagerState(spreadPager(ReaderLeafMapping(5, 2), mutableIntStateOf(0), targets))
        env.runCurrent()
        assertEquals(listOf(1), targets)
    }

    private fun spreadPager(mapping: ReaderLeafMapping, page: androidx.compose.runtime.MutableIntState, targets: MutableList<Int> = mutableListOf()): PagerState = mockk<ReaderSpreadPagerState> {
        every { leaves } returns mapping
        every { pageCount } returns mapping.screenCount
        every { settledPage } answers { page.intValue }
        every { currentPage } answers { page.intValue }
        every { targetPage } answers { page.intValue }
        every { isScrollInProgress } returns false
        coEvery { scrollToPage(any(), any()) } answers { targets += firstArg<Int>(); page.intValue = firstArg() }
    }

    private fun pager(count: Int, page: androidx.compose.runtime.MutableIntState = mutableIntStateOf(0), targets: MutableList<Int> = mutableListOf()): PagerState = mockk {
        every { pageCount } returns count
        every { settledPage } answers { page.intValue }
        every { currentPage } answers { page.intValue }
        every { targetPage } answers { page.intValue }
        every { isScrollInProgress } returns false
        coEvery { scrollToPage(any(), any()) } answers { targets += firstArg<Int>() }
    }
}
