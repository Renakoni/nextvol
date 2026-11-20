package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollProgressTimingTest {
    private val env = ModeTestEnvironment()
    private var now = 10_000L
    private val offset = mutableIntStateOf(0)
    private val scrolling = mutableStateOf(true)
    private val readable = mutableStateOf(true)
    private val chapterId = mutableStateOf("chapter")
    private val writes = mutableListOf<Pair<String, Float>>()
    private val item = mockk<LazyListItemInfo> {
        every { key } answers { chapterId.value }
        every { size } returns 1000
        every { contentType } answers { readable.value }
        every { offset } answers { -this@ScrollProgressTimingTest.offset.intValue }
    }
    private val layout = mockk<LazyListLayoutInfo> { every { visibleItemsInfo } returns listOf(item) }
    private val uiState = MutableScrollContentUiSate({}, {}, {}, {}, {}).apply {
        readingChapterId = "chapter"
        contentList[1] = "chapter" to Ok(ChapterContentUiState("chapter", "Chapter", emptyList(), null, null))
        lazyListState = mockk<LazyListState> {
            every { firstVisibleItemScrollOffset } answers { offset.intValue }
            every { isScrollInProgress } answers { scrolling.value }
            every { layoutInfo } returns layout
        }
    }
    private val progress = ScrollReadingProgress(
        uiState, env.scope, { id, value -> writes += id to value }, { 100 }, env.dispatcher, { now },
    )

    @After fun tearDown() = env.close()

    @Test fun oldListCallbacksCannotUnlockANewerRestoration() {
        scrolling.value = false
        progress.start()
        env.runCurrent()
        writes.clear()
        val old = uiState.lazyListState
        val replacement = mockk<LazyListState> {
            every { layoutInfo } returns layout
            every { isScrollInProgress } returns false
        }
        uiState.lazyListState = replacement
        progress.restoring(replacement)
        progress.restored(old)
        progress.readingPositioned(old)
        progress.writeProgressRightNow()
        env.runCurrent()
        assertEquals(emptyList<Pair<String, Float>>(), writes)
        progress.restored(replacement)
        env.runCurrent()
        assertEquals(emptyList<Pair<String, Float>>(), writes)
        progress.readingPositioned(replacement)
        env.runCurrent()
        assertEquals(listOf("chapter" to 0.1f), writes)
    }

    @Test fun reflowFlushesTheLastAuthorizedThrottledSampleNotTheNewGeometry() {
        offset.intValue = 500
        progress.start()
        env.runCurrent()
        move(10_050, 550)
        assertEquals(0.6f, uiState.readingProgress)
        assertEquals(listOf("chapter" to 0.6f), writes)
        val list = uiState.lazyListState
        progress.restoring(list)
        offset.intValue = 700
        progress.restored(list)
        scrolling.value = false
        env.runCurrent()
        progress.writeProgressRightNow()
        assertEquals(0.8f, uiState.readingProgress)
        assertEquals(listOf("chapter" to 0.6f, "chapter" to 0.65f), writes)
    }

    private fun move(time: Long, pixels: Int) {
        now = time
        offset.intValue = pixels
        env.runCurrent()
    }

    @Test
    fun successfulResponseMustNotSaveTheStillMeasuredErrorBlock() {
        readable.value = false
        uiState.readingProgress = 0.6f
        progress.start()
        env.runCurrent()
        move(12_500, 900)
        scrolling.value = false
        env.runCurrent()
        assertEquals(0.6f, uiState.readingProgress)
        assertEquals(emptyList<Pair<String, Float>>(), writes)
    }

    @Test
    fun repeatedIdleNotificationsAreDeduplicatedButExplicitStopStillSaves() {
        scrolling.value = false
        progress.start()
        env.runCurrent()
        repeat(20) {
            scrolling.value = true
            env.runCurrent()
            scrolling.value = false
            env.runCurrent()
        }
        assertEquals(listOf("chapter" to 0.1f), writes)
        progress.writeProgressRightNow()
        assertEquals(listOf("chapter" to 0.1f, "chapter" to 0.1f), writes)
        writes.clear()
        offset.intValue = 500
        progress.writeProgressRightNow()
        offset.intValue = 200
        progress.writeProgressRightNow()
        assertEquals(listOf("chapter" to 0.6f, "chapter" to 0.3f), writes)
    }

}
