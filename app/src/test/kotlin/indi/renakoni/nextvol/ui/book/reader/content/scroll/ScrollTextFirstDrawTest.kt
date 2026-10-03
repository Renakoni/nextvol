package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderTextDrawObserver
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderTextWorkObserver
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragment
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSelectionState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderSelectionState
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
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollTextFirstDrawTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val prepared = mutableStateOf(chapter())
    private val offset = mutableStateOf(0)
    private val draws = mutableListOf<Set<Int>>()
    private val drawnFragments = mutableSetOf<Int>()
    private val textLayouts = mutableMapOf<Int, TextLayoutResult>()
    private val listState = LazyListState()
    private val selection = ReaderSelectionState()
    private var measurements = 0
    private var compositions = 0
    private val density = mutableStateOf(Density(1f))
    private val width = mutableStateOf(240.dp)

    @Before fun open() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = activity.get().window.decorView
        // Robolectric does not submit hardware frames. Draw at each actual pre-draw boundary,
        // before a layout-triggered state write can be consumed by a later recomposition.
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.width > 0 && view.height > 0) {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    bitmap.recycle()
                }
                return true
            }
        })
    }
    @After fun close() { activity.pause().stop().destroy() }

    private fun mount(observeDraws: Boolean = true, lookahead: Boolean = false, lazy: Boolean = false,
        nextChapter: ScrollTextLayout? = null) {
        activity.get().setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReaderSelectionState provides selection,
                    LocalReaderTextDrawObserver provides if (observeDraws) { fragment, result, _ ->
                    drawnFragments += fragment.start
                    textLayouts[fragment.start] = result
                } else null, LocalDensity provides density.value, LocalReaderTextWorkObserver provides if (observeDraws) { phase ->
                    if (phase == "compose") compositions++ else measurements++
                } else null) {
                    val body: @Composable () -> Unit = {
                        Box(Modifier.size(width.value, 240.dp).clipToBounds().drawWithContent {
                            drawnFragments.clear()
                            drawContent()
                            draws += drawnFragments.toSet()
                        }) {
                            if (lazy) LazyColumn(state = listState) {
                                item(key = "chapter") { ScrollTextContent(prepared.value, Color.Black, Modifier) }
                                if (nextChapter != null) item(key = "next") {
                                    // Keep the work counter specific to the chapter being re-entered.
                                    CompositionLocalProvider(LocalReaderTextWorkObserver provides null,
                                        LocalReaderTextDrawObserver provides null) {
                                        ScrollTextContent(nextChapter, Color.Black, Modifier)
                                    }
                                }
                            } else {
                                // Move only the ancestor placement, as LazyColumn's scroll-only path does.
                                Layout(content = { ScrollTextContent(prepared.value, Color.Black, Modifier) }) { children, constraints ->
                                    val child = children.single().measure(Constraints.fixedWidth(constraints.maxWidth))
                                    layout(constraints.maxWidth, constraints.maxHeight) { child.place(0, -offset.value) }
                                }
                            }
                        }
                    }
                    if (lookahead) LookaheadScope { body() } else body()
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun firstDrawAlreadyContainsText() {
        mount()
        assertEveryDrawHasText()
    }

    @Test fun upwardChapterEntryDoesNotComposeAnExtraScreenOfParagraphs() {
        prepared.value = ScrollTextLayout(chapter().fragments.map { it.copy(spacingBefore = 0) }, chapter().style)
        val next = ScrollTextLayout(chapter().fragments.map { it.copy(text = "NEXT_${it.start / 10}") }, chapter().style)
        mount(lookahead = true, lazy = true, nextChapter = next)
        repeat(3) {
            compose.runOnIdle { runBlocking { listState.scrollToItem(1, 5_000) } }
            compose.waitForIdle()
            compose.runOnIdle { runBlocking { listState.scrollToItem(1) } }
            compose.waitForIdle()
            val before = compositions
            compose.runOnIdle {
                draws.clear()
                listState.dispatchRawDelta(-20f)
            }
            compose.waitForIdle()
            assertEveryDrawHasText()
            compose.onNodeWithText("TEXT_199", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("NEXT_0", useUnmergedTree = true).assertIsDisplayed()
            // Lookahead can initially place a recreated item at its old origin before placing
            // its tail. Allow both placement windows, but no extra screen around either one.
            assertTrue("Chapter entry must bound offscreen text work: ${compositions - before}",
                compositions - before in 1..16)
        }
    }

    @Test fun lazyLookaheadScrollingRetainsOverlappingTextLayouts() {
        mount(lookahead = true, lazy = true)
        compose.runOnIdle { runBlocking { listState.scrollToItem(0, 5_000) } }
        compose.waitForIdle()
        for (destination in listOf(5_100, 5_200, 5_100, 5_000)) {
            val before = textLayouts.toMap()
            compose.runOnIdle {
                draws.clear()
                textLayouts.clear()
                runBlocking { listState.scrollToItem(0, destination) }
            }
            compose.waitForIdle()
            assertEveryDrawHasText()
            compose.onNodeWithText("TEXT_${destination / 100}", useUnmergedTree = true).assertIsDisplayed()
            val overlap = before.keys.intersect(textLayouts.keys)
            assertTrue("The test must retain overlapping paragraphs", overlap.size >= 2)
            overlap.forEach { start ->
                assertSame("Scrolling must retain the measured text of overlapping fragment $start",
                    before[start], textLayouts[start])
            }
        }
    }

    // API 27 avoids Robolectric's unsupported native magnifier surface (API 28+).
    @Config(sdk = [27])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun overlappingTextKeepsSelectionWhenTheWindowAdvances() {
        mount(lookahead = true, lazy = true)
        compose.runOnIdle { runBlocking { listState.scrollToItem(0, 5_000) } }
        compose.waitForIdle()
        compose.onNodeWithText("TEXT_51").performTouchInput { longClick(center.copy(x = 20f)) }
        compose.runOnIdle {
            assertTrue("Long press must select visible text", selection.hasSelection)
            runBlocking { listState.scrollToItem(0, 5_100) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Advancing the window must retain the overlapping selection", selection.hasSelection)
            selection.clear()
            assertFalse(selection.hasSelection)
        }
    }

    @Test fun scrollingInsideLookaheadUpdatesTheDisplayedText() {
        mount(observeDraws = false, lookahead = true)
        for (destination in listOf(1_000, 8_000, 16_000, 8_000, 1_000, 0)) {
            compose.runOnIdle { offset.value = destination }
            compose.waitForIdle()
            compose.onNodeWithText("TEXT_${destination / 100}", useUnmergedTree = true).assertIsDisplayed()
            if (destination >= 8_000) compose.onNodeWithText("TEXT_0", useUnmergedTree = true).assertIsNotDisplayed()
        }
    }

    @Test fun lookaheadWindowChangesDrawTheDestinationImmediately() {
        mount(lookahead = true)
        for (destination in listOf(1_000, 8_000, 16_000, 8_000, 0)) {
            compose.runOnIdle { draws.clear(); offset.value = destination }
            compose.waitForIdle()
            assertEveryDrawHasText()
            assertTrue("Each frame must draw the destination, not a stale window: $draws",
                draws.all { it.contains(destination / 100 * 10) })
            assertTrue("Far-away fragments should stay uncomposed", draws.all { it.size < 40 })
        }
    }

    @Test fun replacementGeometryDoesNotDrawAnEmptyFrame() {
        mount()
        compose.runOnIdle { draws.clear(); prepared.value = chapter() }
        compose.waitForIdle()
        assertEveryDrawHasText()
    }

    @Test fun steadyAncestorScrollingReusesMeasuredTextUntilTheWindowChanges() {
        assertSteadyScrollingReusesText(lookahead = false)
    }

    @Test fun steadyLookaheadScrollingReusesMeasuredTextUntilTheWindowChanges() {
        assertSteadyScrollingReusesText(lookahead = true)
    }

    private fun assertSteadyScrollingReusesText(lookahead: Boolean) {
        mount(lookahead = lookahead)
        // Stay inside one 100px fragment boundary after settling the initial placement.
        compose.runOnIdle { offset.value = 10 }
        compose.waitForIdle()
        val count = measurements
        val compositionCount = compositions
        val window = draws.last()
        assertTrue(count > 0)
        for (destination in List(20) { 11 - it % 2 }) {
            compose.runOnIdle { draws.clear(); offset.value = destination }
            compose.waitForIdle()
            assertEveryDrawHasText()
            assertEquals(window, draws.last())
            assertEquals("Unchanged window must reuse its measured text", count, measurements)
            assertEquals("Unchanged window must reuse its composition", compositionCount, compositions)
        }
        compose.runOnIdle { offset.value = 8_000 }
        compose.waitForIdle()
        assertTrue("A changed window must measure new fragments", measurements > count)
    }

    @Test fun anOffscreenComponentHasTextOnItsFirstVisibleDraw() {
        offset.value = -4_000
        mount()
        compose.runOnIdle { draws.clear(); offset.value = 0 }
        compose.waitForIdle()
        assertEveryDrawHasText()
    }

    @Test fun unchangedWindowStillRemeasuresWhenWidthOrFontScaleChanges() {
        mount()
        val initial = measurements
        compose.runOnIdle { draws.clear(); width.value = 220.dp }
        compose.waitForIdle()
        assertEveryDrawHasText()
        assertTrue(measurements > initial)
        val resized = measurements
        compose.runOnIdle { draws.clear(); density.value = Density(1f, 1.5f) }
        compose.waitForIdle()
        assertEveryDrawHasText()
        assertTrue(measurements > resized)
    }

    @Test fun largeAncestorPlacementJumpsComposeTheDestinationBeforeDrawing() {
        mount()
        for (destination in listOf(8_000, 16_000, 0)) {
            compose.runOnIdle { draws.clear(); offset.value = destination }
            compose.waitForIdle()
            assertEveryDrawHasText()
            assertTrue("Far-away fragments should stay uncomposed", draws.all { it.size < 40 })
            assertTrue("The first draw must contain the destination fragment",
                draws.first().contains(destination / 100 * 10))
        }
    }

    private fun assertEveryDrawHasText() {
        assertTrue("The test must observe real draw callbacks", draws.isNotEmpty())
        assertTrue("Visible text was absent in draw(s): $draws", draws.all { it.isNotEmpty() })
    }

    companion object {
        private fun chapter() = ScrollTextLayout((0 until 200).map { index ->
            // Match the actual 20px text plus its spacer to the 100px prepared geometry.
            ReaderTextFragment(0, index * 10, index * 10 + 10, "TEXT_$index", 80, 20,
                listOf(index * 10), listOf(0))
        }, TextStyle(fontSize = 16.sp, lineHeight = 20.sp))
    }
}
