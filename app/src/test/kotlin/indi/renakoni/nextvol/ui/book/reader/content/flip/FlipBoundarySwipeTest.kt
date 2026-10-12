package indi.renakoni.nextvol.ui.book.reader.content.flip

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.ReaderFlipAnimation
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
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
class FlipBoundarySwipeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var next = 0
    private var previous = 0
    private lateinit var state: MutableFlipPageContentUiState
    @Before fun setUp() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    @After fun close() { activity.pause().stop().destroy() }

    @Test fun animatedBoundarySwipeAndTapEachProduceOneCommand() {
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, next)
        compose.onRoot().performTouchInput { click(centerRight.copy(x = width * 0.85f)) }
        compose.waitForIdle()
        assertEquals(2, next)
    }

    @Test fun arrivingAtLastPageDoesNotAlsoChangeChapter() {
        mount(pages = 2)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, state.pagerState.settledPage)
        assertEquals(0, next)
        compose.onRoot().performTouchInput { swipeLeft(durationMillis = 900) }
        compose.waitForIdle()
        assertEquals(1, next)
    }

    @Test fun cancelledReversedAndVerticalGesturesDoNotChangeChapter() {
        mount()
        compose.onRoot().performTouchInput {
            down(centerRight)
            moveBy(Offset(-width * 0.4f, 0f))
            cancel()
        }
        compose.onRoot().performTouchInput {
            val start = centerRight
            down(start)
            moveTo(start - Offset(width * 0.4f, 0f))
            moveTo(start - Offset(1f, 0f))
            up()
        }
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(0, next)
        assertEquals(0, previous)
    }

    @Test fun multiplePointersCannotTriggerAChapterTurn() {
        mount()
        compose.onRoot().performTouchInput {
            down(0, centerRight)
            down(1, center)
            moveTo(0, centerLeft)
            up(1)
            up(0)
        }
        compose.waitForIdle()
        assertEquals(0, next)
    }

    @Test fun rtlBoundaryDirectionsMatchPagerDirections() {
        mount(rtl = true)
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1, next)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, previous)
        compose.onRoot().performTouchInput { click(centerLeft.copy(x = width * 0.15f)) }
        compose.waitForIdle()
        assertEquals(2, next)
    }

    @Test fun clickRegionsUseTheReaderWidthInsideALargerWindow() {
        mount(viewportFraction = 0.5f)
        compose.onNodeWithTag("reader").performTouchInput { click(centerRight.copy(x = width * 0.85f)) }
        compose.waitForIdle()
        assertEquals(1, next)
    }

    @Test fun fastChangeOffShowsAnExplicitNextChapterAction() {
        mount(fast = false)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, next)
        compose.onNodeWithText(activity.get().getString(R.string.next_chapter)).performClick()
        compose.waitForIdle()
        assertEquals(1, next)
    }

    @Test fun lastChapterShowsBookEndWithoutRequestingAnotherChapter() {
        mount(hasNext = false)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, next)
        compose.onNodeWithText(activity.get().getString(R.string.reader_reached_end)).assertIsDisplayed()
    }

    @Test fun oldBoundaryActionCannotSkipAChapterAfterDirectoryNavigation() {
        mount(fast = false)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle {
            state.readingChapterId = "8"
            state.readingChapterContent = Ok(ChapterContentUiState("8", "Eighth", listOf(Page()), "7", "9"))
        }
        compose.waitForIdle()
        compose.onNodeWithText(activity.get().getString(R.string.next_chapter)).performClick()
        compose.waitForIdle()
        assertEquals(0, next)
    }

    @Test fun oldBoundaryActionCannotSkipRemainingPagesAfterAnInwardSwipe() {
        mount(fast = false, pages = 2)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(0, state.pagerState.settledPage)
        compose.onNodeWithText(activity.get().getString(R.string.next_chapter)).performClick()
        compose.waitForIdle()
        assertEquals(0, next)
    }

    @Test fun reducedMotionStillTurnsOneChapterWithoutPagerDragging() {
        mount(reduced = true)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, next)
    }

    @Test fun animationNoneUsesTheSameBoundaryCommand() {
        mount(animation = ReaderFlipAnimation.None)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, next)
    }

    @Test @Config(qualifiers = "w1130dp-h800dp-land")
    fun tabletLandscapeBoundarySwipeChangesChapter() {
        mount()
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(1, next)
    }

    private fun mount(
        reduced: Boolean = false,
        animation: String = ReaderFlipAnimation.ScrollWithoutShadow,
        pages: Int = 1,
        fast: Boolean = true,
        rtl: Boolean = false,
        hasNext: Boolean = true,
        viewportFraction: Float = 1f,
    ) {
        val settings = mockk<ReaderSettings>(relaxed = true) {
            every { reduceMotion } returns reduced
            every { flipAnime } returns animation
            every { fastChapterChange } returns fast
            every { isUsingFlipPage } returns true
            every { isUsingClickFlipPage } returns true
            every { enableBackgroundImage } returns false
            every { fontFamilyUri } returns Uri.EMPTY
        }
        state = MutableFlipPageContentUiState({ next++ }, { previous++ }, {}, { state.pagerState = it })
        state.readingChapterId = "3"
        state.readingChapterContent = Ok(ChapterContentUiState("3", "Third", List(pages) { Page() }, "2", if (hasNext) "4" else null))
        compose.runOnUiThread {
            activity.get().setContent {
                MaterialTheme {
                    val snackbar = androidx.compose.runtime.remember { SnackbarHostState() }
                    CompositionLocalProvider(LocalSnackbarHost provides snackbar,
                        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth(viewportFraction).fillMaxHeight().testTag("reader")) {
                                FlipPageContentComponent(Modifier, state, settings, PaddingValues(0.dp), {}, { previous++ }, { next++ })
                            }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            state.pagerState.pageCount == pages
        }
        compose.waitForIdle()
    }

    private class Page : AbstractContentComponent<AbstractContentComponentData>(mockk(relaxed = true)) {
        override val id: Identifier = mockk(relaxed = true)
        @Composable override fun Content(modifier: Modifier) { Text("BODY_3", modifier) }
    }
}
