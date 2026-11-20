package indi.renakoni.nextvol.ui.book.reader.content.flip

import android.app.Application
import androidx.compose.foundation.pager.PagerState
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import indi.renakoni.nextvol.ui.book.reader.content.ReaderCheckpoint
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPosition
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPositionSession
import io.nightfish.lightnovelreader.api.error.WebRequestError
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class FlipChapterTransitionTest {
    private val env = ModeTestEnvironment()
    private val saved = mutableListOf<Pair<String, Float>>()
    private val mode = FlipReaderController(env.loader, env.records, env.scope, { id, p -> saved += id to p }, env.dispatcher)
    @After fun close() = env.close()

    private fun openThird() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("2" to 0.2f, "4" to 0.8f))
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter("3")
        env.runCurrent()
        env.emit("3", Ok(env.chapter("3", "2", "4")))
        mode.updatePagerState(PagerState(currentPage = 4) { 5 })
        env.runCurrent()
        saved.clear()
    }

    @Test fun nextKeepsCurrentUntilPaginationAndEntersFirstPageDespiteOldEightyPercent() {
        openThird()
        val original = mode.uiState.readingChapterContent
        val pager = mode.uiState.pagerState
        mode.loadNextChapter()
        repeat(3) { mode.loadNextChapter() }
        env.runCurrent()
        assertSame(original, mode.uiState.readingChapterContent)
        assertSame(pager, mode.uiState.pagerState)
        assertEquals("3", mode.requestedChapterId)
        assertEquals(1, env.chapters.requests.count { it.chapterId == "4" })
        env.emit("4", Ok(env.chapter("4", "3", "5")))
        val pending = mode.uiState.pendingChapter!!
        assertEquals(ChapterEntry.Start, pending.entry)
        assertEquals("3", env.records.data.lastReadChapterId)
        assertEquals(1f, mode.uiState.readingProgress)
        assertTrue(saved.isEmpty())
        assertTrue(mode.uiState.commitPendingChapter(pending, PagerState { 10 }))
        env.runCurrent()
        assertEquals("4", mode.requestedChapterId)
        assertEquals("4", env.records.data.lastReadChapterId)
        assertEquals(0, mode.uiState.pagerState.currentPage)
        assertEquals(0.1f, mode.uiState.readingProgress)
        assertTrue(saved.all { it == "4" to 0.1f })
    }

    @Test fun stagedBoundaryKeepsSourceCheckpointUntilCommitAndRejectsItsOldRestore() {
        openThird()
        val positions = ReaderPositionSession()
        val source = ReaderPosition("book", "3", 0, 127, "source")
        positions.activate(mode.uiState, "book", "3", ReaderCheckpoint("book", "3", source))
        positions.register(mode.uiState, Any()) { source }
        positions.finish(mode.uiState, positions.pending!!, source)
        mode.observeNavigation { book, chapter, preserve -> positions.navigate(mode.uiState, book, chapter, preserve) }
        mode.loadNextChapter()
        env.runCurrent()
        assertEquals("3", mode.requestedChapterId)
        assertEquals(source, positions.checkpoint!!.position)
        val old = positions.pending!!
        env.emit("4", Ok(env.chapter("4", "3", "5")))
        assertTrue(mode.uiState.commitPendingChapter(mode.uiState.pendingChapter!!, PagerState { 10 }))
        positions.finish(mode.uiState, old, source)
        assertEquals(ReaderCheckpoint("book", "4"), positions.checkpoint)
        assertNull(positions.pending)
    }

    @Test fun failedBoundaryRequestKeepsReadableChapterAndCanBeRetried() {
        openThird()
        val original = mode.uiState.readingChapterContent
        mode.loadNextChapter()
        env.runCurrent()
        env.emit("4", Err(WebRequestError("Offline", "Retry")))
        assertTrue(mode.uiState.pendingChapter!!.result!!.isErr)
        assertSame(original, mode.uiState.readingChapterContent)
        assertEquals("3", mode.requestedChapterId)
        assertEquals("3", env.records.data.lastReadChapterId)
        assertTrue(saved.isEmpty())
        mode.uiState.retryPendingChapter()
        env.runCurrent()
        assertNull(mode.uiState.pendingChapter!!.result)
        env.emit("4", Ok(env.chapter("4")))
        assertTrue(mode.uiState.commitPendingChapter(mode.uiState.pendingChapter!!, PagerState { 2 }))
        env.runCurrent()
        assertEquals("4", env.records.data.lastReadChapterId)
    }

    @Test fun cachedTargetRefreshRejectsOldPaginationAndOnlyCommitsNewestContent() {
        openThird()
        mode.loadNextChapter()
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4", title = "Cached")))
        val stale = mode.uiState.pendingChapter!!
        env.emit("4", Ok(env.chapter("4", title = "Refreshed")))
        assertFalse(mode.uiState.commitPendingChapter(stale, PagerState { 3 }))
        assertTrue(mode.uiState.commitPendingChapter(mode.uiState.pendingChapter!!, PagerState { 4 }))
        env.runCurrent()
        assertEquals("Refreshed", env.records.data.lastReadChapterTitle)
    }

    @Test fun turningBackCancelsPendingChapterAndLatePaginationCannotCommit() {
        openThird()
        mode.loadNextChapter()
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4")))
        val pending = mode.uiState.pendingChapter!!
        mode.uiState.cancelPendingChapter()
        assertFalse(mode.uiState.commitPendingChapter(pending, PagerState { 2 }))
        assertEquals("3", mode.requestedChapterId)
        assertEquals("3", mode.uiState.readingChapterId)
    }

}
