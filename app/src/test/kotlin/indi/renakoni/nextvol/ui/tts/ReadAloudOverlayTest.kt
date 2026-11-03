package indi.renakoni.nextvol.ui.tts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import indi.renakoni.nextvol.tts.*
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.Route
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
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadAloudOverlayTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val state = mutableStateOf(ReadAloudState(SpeechRequest("book", "chapter"),
        SpeechPhase.Playing, bookTitle = "Listening book", showFloatingPlayer = true))
    private val hidePanel = mutableStateOf(false)
    private val hideSettings = mutableStateOf(false)
    private val direction = mutableStateOf(LayoutDirection.Ltr)
    private val windowWidth = mutableStateOf(360.dp)
    private val actions = mutableListOf<SpeechAction>()
    private val opened = mutableListOf<SpeechRequest>()
    private var pageClicks = 0
    private lateinit var list: LazyListState

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun destroy() { activity.pause().stop().destroy() }

    private fun show(content: (@Composable () -> Unit)? = null, open: (SpeechRequest) -> Unit = { opened += it },
        controller: ReadAloudController? = null) {
        activity.get().setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    Box(Modifier.width(windowWidth.value).fillMaxHeight().testTag("window")) {
                        val playback = controller?.state?.collectAsStateWithLifecycle()?.value ?: state.value
                        ReadAloudOverlayHost(playback, { actions += it; controller?.command(it) }, open,
                            cover = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary)) }) {
                            if (hidePanel.value) HideReadAloudOverlay()
                            if (hideSettings.value) HideReadAloudOverlay()
                            if (content != null) content() else {
                                Column {
                                    Button(onClick = { pageClicks++ }, modifier = Modifier.testTag("page-action")) { Text("Choose book") }
                                    list = rememberLazyListState()
                                    LazyColumn(Modifier.fillMaxSize().testTag("book-list"), state = list) {
                                        items((1..80).toList()) { Text("Book $it", Modifier.fillMaxWidth().height(56.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun player() = compose.onNodeWithTag("read-aloud-floating-player")
    private fun expand() = compose.onNodeWithTag("read-aloud-expand")
    private fun phase(phase: SpeechPhase) = compose.runOnIdle {
        state.value = state.value.copy(phase = phase, showFloatingPlayer =
            phase !in setOf(SpeechPhase.Stopped, SpeechPhase.Completed) &&
                (state.value.showFloatingPlayer || phase == SpeechPhase.Playing))
    }
    private fun drag(dx: Float, dy: Float = 0f) {
        player().performTouchInput {
            down(center)
            moveBy(Offset(dx, dy), delayMillis = 300)
            up()
        }
        compose.waitForIdle()
    }

    @Test fun startsOnlyAfterBookPlaybackAndNeverForPreview() {
        state.value = state.value.copy(phase = SpeechPhase.Preparing, showFloatingPlayer = false)
        show()
        player().assertDoesNotExist()
        phase(SpeechPhase.Paused)
        player().assertDoesNotExist()
        phase(SpeechPhase.Playing)
        player().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(request = SpeechRequest("", "", "Preview")) }
        player().assertDoesNotExist()
    }

    @Test fun pauseResumeAndCloseControlTheSameSessionAndCloseImmediately() {
        val controller = ReadAloudController(activity.get(), mockk(relaxed = true))
        controller.publish(state.value)
        show(controller = controller)
        fun phase(phase: SpeechPhase) = compose.runOnIdle {
            controller.publish(controller.state.value.copy(phase = phase))
        }
        compose.onNodeWithContentDescription("Pause").performClick()
        phase(SpeechPhase.Paused)
        player().assertIsDisplayed()
        compose.onNodeWithContentDescription("Resume").performClick()
        phase(SpeechPhase.Playing)
        compose.onNodeWithContentDescription("Stop listening and close").performClick()
        player().assertDoesNotExist()
        assertEquals(listOf(SpeechAction.Pause, SpeechAction.Resume, SpeechAction.Stop), actions)
        phase(SpeechPhase.Buffering)
        phase(SpeechPhase.Playing)
        player().assertDoesNotExist()
        phase(SpeechPhase.Stopped)
        phase(SpeechPhase.Playing)
        player().assertIsDisplayed()
    }

    @Test fun outsideTouchCollapsesWithoutSwallowingThePageClick() {
        show()
        compose.onNodeWithTag("page-action").performTouchInput { click() }
        expand().assertIsDisplayed()
        assertEquals(1, pageClicks)
        expand().performTouchInput { click() }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test fun outsideScrollCollapsesAndTheBookListStillScrolls() {
        show()
        compose.onNodeWithTag("book-list").performTouchInput { swipeUp() }
        expand().assertIsDisplayed()
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > 0) }
    }

    @Test fun rightEdgeStaysAnchoredThroughoutCollapseAndExpansion() {
        show()
        val right = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot.right
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page-action").performTouchInput { click() }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            val edge = player().fetchSemanticsNode().boundsInRoot.right
            assertTrue("Collapse frame $it drifted to $edge", edge in (right - 13f)..(right + 1f))
        }
        expand().performClick()
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            val edge = player().fetchSemanticsNode().boundsInRoot.right
            assertTrue("Expansion frame $it drifted to $edge", edge in (right - 13f)..(right + 1f))
        }
    }

    @Test fun collapsingKeepsTheCoverUntilItsExitAnimationFinishes() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page-action").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("read-aloud-cover").assertExists().assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("read-aloud-cover").assertDoesNotExist()
    }

    @Test fun touchingHandleDuringCollapseReversesTheTransitionWithoutActivatingControls() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page-action").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(64)
        expand().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(320)
        expand().assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsEnabled()
        assertEquals(128f, player().fetchSemanticsNode().boundsInRoot.width, 1f)
        assertTrue(actions.isEmpty())
    }

    @Test fun middleReleaseSnapsToNearestSideAndEdgeDragCollapsesWithoutClicking() {
        show()
        drag(-140f)
        expand().assertDoesNotExist()
        assertEquals(12f, player().fetchSemanticsNode().boundsInRoot.left, 1f)
        drag(400f)
        expand().assertIsDisplayed()
        val root = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        assertEquals(root.right, player().fetchSemanticsNode().boundsInRoot.right, 1f)
        expand().performClick()
        assertEquals(root.right - 12f, player().fetchSemanticsNode().boundsInRoot.right, 1f)
        assertTrue(actions.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test fun leftHandleExpandsInwardAndCanBeDraggedAlongTheEdge() {
        show()
        drag(-250f)
        expand().assertIsDisplayed()
        assertEquals(0f, player().fetchSemanticsNode().boundsInRoot.left, 1f)
        val before = player().fetchSemanticsNode().boundsInRoot.top
        drag(0f, 160f)
        assertTrue(player().fetchSemanticsNode().boundsInRoot.top > before + 100f)
        expand().performClick()
        assertEquals(12f, player().fetchSemanticsNode().boundsInRoot.left, 1f)
    }

    @Test fun windowResizeAndRtlKeepThePlayerInsidePhysicalEdges() {
        show()
        compose.runOnIdle { direction.value = LayoutDirection.Rtl }
        drag(-120f, 1200f)
        assertEquals(12f, player().fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.runOnIdle { windowWidth.value = 240.dp }
        val bounds = player().fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("window").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left && bounds.right <= root.right)
        assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
    }

    @Test fun overlappingPanelsHideUntilBothAreGoneAndRestoreDockPosition() {
        show()
        drag(-120f, 100f)
        val before = player().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { hidePanel.value = true; hideSettings.value = true }
        player().assertDoesNotExist()
        compose.runOnIdle { hidePanel.value = false }
        player().assertDoesNotExist()
        compose.runOnIdle { hideSettings.value = false }
        assertEquals(before, player().fetchSemanticsNode().boundsInRoot)
    }

    @Test fun backgroundHidesPlayerAndReturningKeepsTheSession() {
        show()
        compose.runOnIdle { activity.pause().stop() }
        player().assertDoesNotExist()
        compose.runOnIdle { activity.start().resume() }
        player().assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test fun playbackStartingAndPausingInBackgroundStillOffersControlsOnReturn() {
        val controller = ReadAloudController(activity.get(), mockk(relaxed = true))
        controller.publish(state.value.copy(phase = SpeechPhase.Preparing))
        show(controller = controller)
        player().assertDoesNotExist()
        compose.runOnIdle { activity.pause().stop() }
        compose.runOnIdle {
            controller.publish(state.value)
            controller.publish(state.value.copy(phase = SpeechPhase.Paused))
        }
        compose.runOnIdle { activity.start().resume() }
        compose.onNodeWithContentDescription("Resume").assertIsDisplayed()
    }

    @Test fun sameBookCanRestartEvenIfUiMissesTheStoppedState() {
        val controller = ReadAloudController(activity.get(), mockk(relaxed = true))
        controller.publish(state.value)
        show(controller = controller)
        compose.onNodeWithContentDescription("Stop listening and close").performClick()
        player().assertDoesNotExist()
        compose.runOnIdle {
            controller.publish(state.value.copy(phase = SpeechPhase.Stopped))
            controller.publish(state.value.copy(phase = SpeechPhase.Preparing))
            controller.publish(state.value)
        }
        player().assertIsDisplayed()
    }

    @Test fun playbackEligibilityResetsForAnotherBookAndPreview() {
        val controller = ReadAloudController(activity.get(), mockk(relaxed = true))
        controller.publish(state.value)
        show(controller = controller)
        player().assertIsDisplayed()
        compose.runOnIdle {
            controller.publish(ReadAloudState(SpeechRequest("another-book", "chapter"), SpeechPhase.Preparing))
        }
        player().assertDoesNotExist()
        compose.runOnIdle { controller.publish(controller.state.value.copy(phase = SpeechPhase.Playing)) }
        player().assertIsDisplayed()
        compose.runOnIdle { controller.publish(ReadAloudState(SpeechRequest("", "", "Preview"), SpeechPhase.Playing)) }
        player().assertDoesNotExist()
    }

    @Test fun explicitResumeAfterAServiceFailureCanShowThePlayerAgain() {
        val controller = ReadAloudController(activity.get(), mockk(relaxed = true))
        controller.publish(state.value)
        show(controller = controller)
        compose.onNodeWithContentDescription("Stop listening and close").performClick()
        compose.runOnIdle {
            controller.publish(state.value.copy(phase = SpeechPhase.Failed, error = SpeechError.ServiceUnavailable))
            controller.command(SpeechAction.Resume)
            controller.publish(state.value)
        }
        player().assertIsDisplayed()
    }

    @Test fun releasingAndInterruptingDockingContinueFromTheFingerPosition() {
        show()
        compose.mainClock.autoAdvance = false
        player().performTouchInput { down(center); moveBy(Offset(-140f, 0f)) }
        compose.mainClock.advanceTimeBy(32)
        val released = player().fetchSemanticsNode().boundsInRoot.left
        player().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        val first = player().fetchSemanticsNode().boundsInRoot.left
        assertTrue("Release jumped from $released to $first", first in 12f..(released + 1f))
        compose.mainClock.advanceTimeBy(64)
        val beforeDrag = player().fetchSemanticsNode().boundsInRoot.left
        player().performTouchInput { down(center); moveBy(Offset(80f, 0f)) }
        compose.mainClock.advanceTimeBy(32)
        val interrupted = player().fetchSemanticsNode().boundsInRoot.left
        assertTrue(interrupted > beforeDrag + 40f)
        player().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(interrupted, player().fetchSemanticsNode().boundsInRoot.left, 2f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(actions.isEmpty())
    }

    @Test fun completionAndStopRemovePlayerButBufferingAndFailureKeepControls() {
        show()
        phase(SpeechPhase.Buffering)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        phase(SpeechPhase.Failed)
        compose.onNodeWithContentDescription("Resume").assertIsDisplayed()
        phase(SpeechPhase.Completed)
        player().assertDoesNotExist()
        phase(SpeechPhase.Preparing)
        player().assertDoesNotExist()
        phase(SpeechPhase.Playing)
        player().assertIsDisplayed()
        phase(SpeechPhase.Stopped)
        player().assertDoesNotExist()
    }

    @Test fun coverAlwaysUsesTheCurrentListeningBookAndChapter() {
        show()
        compose.runOnIdle { state.value = state.value.copy(request = SpeechRequest("another-book", "chapter-2")) }
        compose.onNodeWithTag("read-aloud-cover").performClick()
        assertEquals(listOf(SpeechRequest("another-book", "chapter-2")), opened)
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun coverUsesTheAudibleChapterWhileTheNextChapterIsBuffering() {
        show()
        compose.runOnIdle {
            state.value = state.value.copy(request = SpeechRequest("book", "next"), phase = SpeechPhase.Buffering,
                position = indi.renakoni.nextvol.tts.SpeechPosition("book", "audible", "fingerprint", 12, 24))
        }
        compose.onNodeWithTag("read-aloud-cover").performClick()
        assertEquals(listOf(SpeechRequest("book", "audible")), opened)
    }

    @Test fun coverNavigatesFromDiscoveryAndReplacesAnAlreadyOpenReader() {
        lateinit var nav: NavHostController
        show(content = {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Main.Explore.Home,
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
                composable<Route.Main.Explore.Home> { Text("Discover books") }
                composable<Route.Book.Reader> { Text(it.toRoute<Route.Book.Reader>().chapterId) }
            }
        }, open = { nav.navigateToReadAloudBook(it) })
        compose.onNodeWithTag("read-aloud-cover").performClick()
        compose.onNodeWithText("chapter").assertIsDisplayed()
        val firstEntry = nav.currentBackStackEntry!!.id
        compose.runOnIdle { state.value = state.value.copy(request = SpeechRequest("book", "next-chapter")) }
        compose.onNodeWithTag("read-aloud-cover").performClick()
        compose.onNodeWithText("next-chapter").assertIsDisplayed()
        assertNotEquals(firstEntry, nav.currentBackStackEntry!!.id)
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Discover books").assertIsDisplayed()
    }

    @Test fun repeatedCoverTapDuringNavigationDoesNotReplaceTheIncomingReader() {
        lateinit var nav: NavHostController
        show(content = {
            nav = rememberNavController()
            NavHost(nav, startDestination = Route.Main.Explore.Home,
                enterTransition = { fadeIn(tween(300)) }, exitTransition = { fadeOut(tween(300)) }) {
                composable<Route.Main.Explore.Home> { Text("Discover books") }
                composable<Route.Book.Reader> { Text(it.toRoute<Route.Book.Reader>().chapterId) }
            }
        }, open = { nav.navigateToReadAloudBook(it) })
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("read-aloud-cover").performClick()
        compose.mainClock.advanceTimeBy(32)
        val entry = nav.currentBackStackEntry!!.id
        compose.onNodeWithTag("read-aloud-cover").performClick()
        assertEquals(entry, nav.currentBackStackEntry!!.id)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { nav.popBackStack() }
        compose.onNodeWithText("Discover books").assertIsDisplayed()
    }
}
