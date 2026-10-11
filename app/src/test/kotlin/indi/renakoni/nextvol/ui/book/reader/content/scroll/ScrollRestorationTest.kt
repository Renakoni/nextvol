package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.ContentData
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollRestorationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val env = ModeTestEnvironment()
    private val saved = mutableListOf<Pair<String, Float>>()
    private val gate = CompletableDeferred<Unit>()
    private var continuous = false
    private val mode = ScrollReaderController(
        env.loader, env.records, env.scope,
        object : ContinuousScrollSettings {
            override fun getFlow() = flowOf(continuous)
            override suspend fun isEnabled() = continuous
        }, { id, progress ->
            saved += id to progress
            env.records.data = env.records.data.copyWithUpdatedChapterReadingProgress(id, progress)
                .copy(lastReadChapterId = id)
        }, env.dispatcher, env.dispatcher,
    )
    private val settings = mockk<ReaderSettings>(relaxed = true) {
        every { fontFamilyUri } returns Uri.EMPTY
        every { reduceMotion } returns true
        every { isUsingContinuousScrolling } answers { continuous }
    }

    @Before fun open() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { gate.complete(Unit); activity.pause().stop().destroy(); env.close() }

    @Test fun delayedHistoryRestoresMeasuredBodyBeforeAnyProgressIsSaved() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("3" to 0.7f))
        env.records.writeGate = gate
        mount()
        repeat(5) { compose.mainClock.advanceTimeByFrame(); env.runCurrent(); compose.waitForIdle() }
        assertTrue("History is still loading; writes: $saved", saved.isEmpty())
        gate.complete(Unit)
        env.runCurrent()
        awaitRestoration("3", 0.7f)
        assertTrue("Must not save the chapter top during restoration: $saved", saved.all { it.first == "3" && kotlin.math.abs(it.second - 0.7f) < 0.01f })
    }

    @Test fun changingChapterRebindsTheListBeforeRestoringItsPosition() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("3" to 0.7f, "4" to 0.4f))
        mount()
        awaitRestoration("3", 0.7f)
        saved.clear()
        mode.changeChapter("4")
        env.runCurrent()
        env.emit("4", Ok(env.chapter("4")))
        awaitRestoration("4", 0.4f)
        assertTrue("Must not save the new chapter before restoring it: $saved", saved.all { it.first == "4" && kotlin.math.abs(it.second - 0.4f) < 0.01f })
    }

    @Test fun cachedPreviousChapterCannotBlockRestoringTheRequestedChapter() {
        continuous = true
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("3" to 0.7f))
        mount()
        awaitRestoration("3", 0.7f)
        assertFalse(mode.uiState.isRestoringProgress)
        assertEquals("3", mode.uiState.readingChapterId)
        assertTrue(saved.all { it.first == "3" })
    }

    @Test fun reopeningAfterContinuousScrollingRestoresTheLastMeasuredPosition() {
        continuous = true
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("3" to 0.5f))
        mount()
        awaitRestoration("3", 0.5f)
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        env.runCurrent()
        val progress = env.records.data.currentChapterReadingProgressMap.getValue("3")
        assertTrue("The swipe must persist a position beyond halfway: $progress", progress > 0.5f && progress < 1f)
        val oldList = mode.uiState.lazyListState
        mode.changeChapter(env.records.data.lastReadChapterId!!)
        env.runCurrent()
        env.emit("3", Ok(env.chapter("3", prev = "2")))
        env.emit("2", Ok(env.chapter("2", next = "3")))
        awaitRestoration("3", progress)
        assertNotSame(oldList, mode.uiState.lazyListState)
    }

    @Test fun manualPreviousChapterStartsAtTheTopWithContinuousScrolling() {
        continuous = true
        assertManualPreviousStartsAtTop()
    }

    @Test fun manualPreviousChapterStartsAtTheTopWithoutContinuousScrolling() {
        assertManualPreviousStartsAtTop()
    }

    private fun assertManualPreviousStartsAtTop() {
        env.records.data = env.records.data.copy(currentChapterReadingProgressMap = mapOf("3" to 0.5f, "2" to 1f))
        mount()
        awaitRestoration("3", 0.5f)
        mode.loadPrevChapter()
        env.runCurrent()
        env.emit("2", Ok(env.chapter("2", next = "3")))
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            env.runCurrent()
            !mode.uiState.isRestoringProgress
        }
        val item = mode.uiState.lazyListState.layoutInfo.visibleItemsInfo.first { it.key == "2" }
        assertEquals("2", mode.uiState.readingChapterId)
        assertEquals("Manual previous chapter must enter at its beginning", 0, item.offset)
    }

    private fun mount() {
        every { env.renderer.getContentDataFromJson(any()) } returns ContentData(listOf(Body()))
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter("3")
        env.runCurrent()
        env.emit("3", Ok(env.chapter("3", prev = "2")))
        if (continuous) env.emit("2", Ok(env.chapter("2", next = "3")))
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalAppTheme provides AppTheme(false, MaterialTheme.colorScheme)) {
                        Box(Modifier.fillMaxWidth().height(320.dp)) {
                            ScrollContentComponent(Modifier, mode.uiState, settings,
                                mockk { every { getFlow() } returns flowOf(Uri.EMPTY) },
                                PaddingValues(0.dp), {}, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        env.runCurrent()
    }

    private fun awaitRestoration(id: String, progress: Float) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            env.runCurrent()
            val layout = mode.uiState.lazyListState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == id && it.contentType == true }
            item != null && kotlin.math.abs((-item.offset + layout.viewportSize.height).toFloat() / item.size - progress) < 0.01f
        }
        compose.waitForIdle()
        env.runCurrent()
        assertEquals(progress, mode.uiState.readingProgress, 0.01f)
    }

    private class Body : AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id: Identifier = mockk(relaxed = true)
        @Composable override fun Content(modifier: Modifier) {
            Box(Modifier.fillMaxWidth().height(2000.dp)) { Text("BODY") }
        }
    }
}
