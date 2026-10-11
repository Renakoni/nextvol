package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.theme.AppTheme
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkSession
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import indi.renakoni.nextvol.ui.book.reader.content.flip.MutableFlipPageContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.scroll.MutableScrollContentUiSate
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
@Config(sdk = [27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReaderVolumeKeysTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var view: View
    private val otherFocus = FocusRequester()
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val windowFocused = mutableStateOf(true)
    private val enabled = mutableStateOf(true)
    private val controlsOpen = mutableStateOf(false)
    private val modalOpen = mutableStateOf(false)
    private val visible = mutableStateOf(true)
    private val fraction = mutableStateOf(0.6f)
    private val reduceMotion = mutableStateOf(false)
    private val interval = mutableStateOf(-1f)
    private val height = mutableStateOf(400)
    private val rtl = mutableStateOf(false)
    private val animated = mutableStateOf(false)
    private val fastChapterChange = mutableStateOf(false)
    private var nextChapters = 0
    private var previousChapters = 0
    private var scrollProgressRestored = false
    private var scrollReadingPositioned = false
    private val scroll = MutableScrollContentUiSate({}, {}, {}, {}, {},
        onProgressRestored = { scrollProgressRestored = true },
        onReadingPositioned = { scrollReadingPositioned = true })
    private lateinit var flip: MutableFlipPageContentUiState
    private val reader = MutableReaderScreenUiState(scroll)
    private val bookmarks = ReaderBookmarkSession()
    private val settings = object : ReaderSettings by mockk<ReaderSettings>(relaxed = true) {
        override val textColor = Color.Black
        override val textDarkColor = Color.White
    }
    private val fonts = mockk<ReaderFontFamilySettings>()

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val chapter = ChapterContentUiState("chapter", "Chapter", List(3) { TestContent() }, "previous", "next")
        scroll.readingChapterId = "chapter"
        scroll.contentList[1] = "chapter" to Ok(chapter)
        flip = MutableFlipPageContentUiState(
            { nextChapters++ }, { previousChapters++ }, {}, { flip.pagerState = it },
        ).apply { readingChapterContent = Ok(chapter) }
        every { settings.isUsingFlipPage } answers { reader.contentUiState === flip }
        every { settings.isUsingVolumeKeyFlip } answers { enabled.value }
        every { settings.volumeKeyScrollFraction } answers { fraction.value }
        every { settings.volumeKeyContinuousFlipInterval } answers { interval.value }
        every { settings.reduceMotion } answers { reduceMotion.value }
        every { settings.topPadding } returns 20f
        every { settings.bottomPadding } returns 20f
        every { settings.fontFamilyUri } returns Uri.EMPTY
        every { settings.fastChapterChange } answers { fastChapterChange.value }
        every { settings.flipAnime } answers {
            if (animated.value) MenuOptions.FlipAnimationOptions.ScrollWithoutShadow else MenuOptions.FlipAnimationOptions.None
        }
        every { fonts.getFlow() } returns flowOf(Uri.EMPTY)
        activity.get().setContent {
            val windowInfo = object : WindowInfo {
                override val isWindowFocused by windowFocused
                override val containerSize = IntSize(600, height.value)
            }
            CompositionLocalProvider(
                LocalReaderBookmarks provides bookmarks,
                LocalAppTheme provides AppTheme(false, lightColorScheme()),
                LocalLifecycleOwner provides owner,
                LocalWindowInfo provides windowInfo,
                LocalDensity provides Density(1f),
                LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                view = LocalView.current
                if (visible.value) {
                    Box(Modifier.size(600.dp, height.value.dp)) {
                        Content(
                            isImmersive = !controlsOpen.value,
                            readingScreenUiState = reader,
                            settingState = settings,
                            fontFamilySettings = fonts,
                            onClickPrevChapter = {},
                            onClickNextChapter = {},
                            onChangeIsImmersive = {},
                            volumeKeysEnabled = !modalOpen.value,
                        )
                        Box(Modifier.size(1.dp).focusRequester(otherFocus).focusable())
                    }
                }
            }
        }
        compose.waitForIdle()
        // Compose can be idle while the chapter is prepared on Dispatchers.Default.
        // Restoration can report from a cancelled placement's finally block. Wait for the
        // reader's focused input node too, before a test freezes the animation clock.
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            scrollProgressRestored && compose.onAllNodes(
                isFocused() and hasAnyDescendant(hasScrollAction()), useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    @After fun tearDown() { activity.pause().stop().destroy() }

    @Test
    fun scrollUsesPaddedViewportAndNewSizeInRtl() {
        val initial = scroll.lazyListState.firstVisibleItemScrollOffset
        val viewport = scroll.lazyListState.layoutInfo.viewportSize.height
        assertEquals(360, viewport)
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(initial + 216, scroll.lazyListState.firstVisibleItemScrollOffset)
        tap(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(initial, scroll.lazyListState.firstVisibleItemScrollOffset)
        compose.runOnIdle { fraction.value = 0.5f; height.value = 240; rtl.value = true }
        compose.waitForIdle()
        val before = scroll.lazyListState.firstVisibleItemScrollOffset
        assertEquals(200, scroll.lazyListState.layoutInfo.viewportSize.height)
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(before + 100, scroll.lazyListState.firstVisibleItemScrollOffset)
    }

    @Test
    fun scrollTapAnimatesInBothDirectionsAndFinishesAfterKeyRelease() {
        val initial = scroll.lazyListState.firstVisibleItemScrollOffset
        compose.mainClock.autoAdvance = false
        try {
            for (code in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) {
                compose.runOnIdle { scrollReadingPositioned = false }
                val start = scroll.lazyListState.firstVisibleItemScrollOffset
                val target = if (code == KeyEvent.KEYCODE_VOLUME_DOWN) initial + 216 else initial
                assertTrue(dispatch(code, KeyEvent.ACTION_DOWN))
                assertTrue(dispatch(code, KeyEvent.ACTION_UP))
                compose.mainClock.advanceTimeBy(64)
                compose.runOnIdle {
                    val middle = scroll.lazyListState.firstVisibleItemScrollOffset
                    assertTrue("The text must pass through intermediate positions", middle in
                        (minOf(start, target) + 1) until maxOf(start, target))
                    assertTrue(scroll.lazyListState.isScrollInProgress)
                    assertTrue(scrollReadingPositioned)
                }
                compose.mainClock.advanceTimeBy(240)
                compose.runOnIdle {
                    assertEquals(target, scroll.lazyListState.firstVisibleItemScrollOffset)
                    assertFalse(scroll.lazyListState.isScrollInProgress)
                }
                compose.mainClock.advanceTimeBy(1_000)
                compose.runOnIdle { assertEquals(target, scroll.lazyListState.firstVisibleItemScrollOffset) }
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun cancelledBeforeMovementDoesNotRecordRestoredPositionAsReading() {
        val initial = scroll.lazyListState.firstVisibleItemScrollOffset
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { scrollReadingPositioned = false }
            assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertTrue(scroll.lazyListState.isScrollInProgress)
                assertEquals(initial, scroll.lazyListState.firstVisibleItemScrollOffset)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
            }
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle {
                assertEquals(initial, scroll.lazyListState.firstVisibleItemScrollOffset)
                assertFalse(scroll.lazyListState.isScrollInProgress)
                assertFalse("Cancelled input must not authorize saving the restored position", scrollReadingPositioned)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun touchScrollRejectsVolumeMovementWithoutRecordingReading() {
        val initial = scroll.lazyListState.firstVisibleItemScrollOffset
        compose.mainClock.autoAdvance = false
        val touch = compose.runOnIdle {
            scrollReadingPositioned = false
            activity.get().lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                scroll.lazyListState.scroll(MutatePriority.UserInput) { awaitCancellation() }
            }
        }
        try {
            assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
            assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle {
                assertTrue(touch.isActive)
                assertEquals(initial, scroll.lazyListState.firstVisibleItemScrollOffset)
                assertFalse(scrollReadingPositioned)
            }
        } finally {
            compose.runOnIdle { touch.cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun reducedMotionScrollsTheSameDistanceWithoutAnimation() {
        compose.runOnIdle { reduceMotion.value = true; scrollReadingPositioned = false }
        compose.waitForIdle()
        val initial = scroll.lazyListState.firstVisibleItemScrollOffset
        compose.mainClock.autoAdvance = false
        try {
            assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
            assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(initial + 216, scroll.lazyListState.firstVisibleItemScrollOffset)
                assertFalse(scroll.lazyListState.isScrollInProgress)
                assertTrue(scrollReadingPositioned)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun scrollClampsAtBothEnds() {
        repeat(2) { tap(KeyEvent.KEYCODE_VOLUME_UP) }
        assertFalse(scroll.lazyListState.canScrollBackward)
        compose.runOnIdle { fraction.value = 1f }
        repeat(40) { tap(KeyEvent.KEYCODE_VOLUME_DOWN) }
        assertFalse(scroll.lazyListState.canScrollForward)
        val end = scroll.lazyListState.firstVisibleItemScrollOffset
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(end, scroll.lazyListState.firstVisibleItemScrollOffset)
        assertEquals(0, nextChapters)
    }

    @Test
    fun disabledControlsModalPauseWindowLossAndExitReleaseInput() {
        val blockers = listOf(
            { enabled.value = false } to { enabled.value = true },
            { controlsOpen.value = true } to { controlsOpen.value = false },
            { modalOpen.value = true } to { modalOpen.value = false },
            { owner.lifecycle.currentState = Lifecycle.State.STARTED } to { owner.lifecycle.currentState = Lifecycle.State.RESUMED },
            { windowFocused.value = false } to { windowFocused.value = true },
            { visible.value = false } to { visible.value = true },
        )
        blockers.forEach { (block, restore) ->
            compose.runOnIdle { block(); Snapshot.sendApplyNotifications() }
            compose.waitForIdle()
            assertFalse(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
            assertFalse(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))
            compose.runOnIdle(restore)
            compose.waitForIdle()
            tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        }
    }

    @Test
    fun flipKeysMoveOnePageInBothDirectionsAndPreserveChapterBoundaryPolicy() {
        compose.runOnIdle { rtl.value = true }
        switchToFlip()
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(1, flip.pagerState.currentPage)
        compose.runOnIdle { animated.value = true }
        compose.waitForIdle()
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(2, flip.pagerState.currentPage)
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(2, flip.pagerState.currentPage)
        assertEquals(0, nextChapters)
        compose.runOnIdle { fastChapterChange.value = true }
        compose.waitForIdle()
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(1, nextChapters)
        tap(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(1, flip.pagerState.currentPage)
        tap(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(0, flip.pagerState.currentPage)
        tap(KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(1, previousChapters)
    }

    @Test
    fun heldKeyStopsWhenModeChangesAndDoesNotRestartFromSystemRepeats() {
        compose.runOnIdle { interval.value = 0.1f }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertTrue(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle { reader.contentUiState = flip }
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(5_000) { flip.pagerState.pageCount == 3 }
        compose.mainClock.advanceTimeBy(500)
        assertFalse(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 4))
        assertFalse(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))
        assertEquals(0, flip.pagerState.currentPage)
        compose.mainClock.autoAdvance = true
        tap(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(1, flip.pagerState.currentPage)
    }

    @Test
    fun holdingStopsOnPauseWindowLossAndFocusTransferWithoutAKeyUp() {
        compose.runOnIdle { interval.value = 0.1f }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val blockers = listOf<() -> Unit>(
            { owner.lifecycle.currentState = Lifecycle.State.STARTED },
            { windowFocused.value = false },
            { otherFocus.requestFocus() },
            { modalOpen.value = true },
            { enabled.value = false },
        )
        blockers.forEachIndexed { index, block ->
            val start = scroll.lazyListState.firstVisibleItemScrollOffset
            compose.runOnIdle { scrollReadingPositioned = false }
            assertTrue("Initial down for blocker $index", dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN))
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                assertTrue(scroll.lazyListState.firstVisibleItemScrollOffset in (start + 1) until (start + 216))
                assertTrue(scrollReadingPositioned)
            }
            compose.runOnIdle { block(); Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(100)
            val stopped = scroll.lazyListState.firstVisibleItemScrollOffset
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals(stopped, scroll.lazyListState.firstVisibleItemScrollOffset)
            assertFalse(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP))
            compose.runOnIdle { enabled.value = false; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(100)
            compose.runOnIdle {
                enabled.value = true
                modalOpen.value = false
                windowFocused.value = true
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeBy(100)
            assertFalse(dispatch(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 3))
        }
        compose.mainClock.autoAdvance = true
    }

    private fun switchToFlip() {
        compose.runOnIdle { reader.contentUiState = flip }
        compose.waitForIdle()
        compose.waitUntil(5_000) { flip.pagerState.pageCount == 3 }
        compose.waitForIdle()
    }

    @Test
    fun outgoingModeCannotCaptureOrConsumeBookmarksWhileIncomingModeLoads() {
        val chapter = ChapterContentUiState("chapter", "Chapter", listOf(
            SimpleTextComponent(SimpleTextComponentData("A readable paragraph. ".repeat(200)),
                mockk(relaxed = true), activity.get()),
        ), null, null)
        compose.runOnIdle {
            scroll.bookId = "book"
            scroll.contentList[1] = "chapter" to Ok(chapter)
            flip.bookId = "book"
            flip.readingChapterContent = null
        }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            bookmarks.capture?.invoke()?.chapter === chapter
        }
        val saved = compose.runOnIdle { bookmarks.capture!!.invoke()!!.bookmark() }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { reader.contentUiState = flip; Snapshot.sendApplyNotifications() }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            // The fading scroll viewport is still composed, but it no longer owns the position.
            compose.runOnIdle { assertNull(bookmarks.capture?.invoke()) }
            compose.runOnIdle { bookmarks.pending = saved }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.runOnIdle { assertSame(saved, bookmarks.pending) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.runOnIdle { flip.readingChapterContent = Ok(chapter) }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            bookmarks.pending == null && bookmarks.capture?.invoke() != null
        }
        compose.runOnIdle {
            assertNull(bookmarks.notice)
            assertEquals(saved.fingerprint, bookmarks.capture!!.invoke()!!.bookmark().fingerprint)
        }
    }

    private fun tap(code: Int) {
        assertTrue(dispatch(code, KeyEvent.ACTION_DOWN))
        assertTrue(dispatch(code, KeyEvent.ACTION_UP))
        compose.waitForIdle()
    }

    private fun dispatch(code: Int, action: Int, repeat: Int = 0): Boolean = compose.runOnIdle {
        view.dispatchKeyEvent(KeyEvent(0, 0, action, code, repeat))
    }

    private class TestContent : AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id = Identifier("test", "content")
        @Composable override fun Content(modifier: Modifier) {
            Box(modifier.fillMaxWidth().height(4_000.dp))
        }
    }
}
