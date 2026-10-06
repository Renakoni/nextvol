package indi.renakoni.nextvol.ui.home.discovery

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
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
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceBrowseControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun sources(count: Int) = List(count) { index -> SourceListing(SourceMetadata(
        WebDataSourceItem(Identifier("fixture", "$index"), "Source $index", "fixture"), setOf(SourceCapability.Explore)), SourceStatus.Ready) }

    @Test fun paginationIsFixedBesideScrollingTabsAndCanJumpStraightToTheLastPage() {
        val sources = sources(89)
        var state by mutableStateOf(DiscoveryPageState(sources, sources.first().metadata.id))
        activity.get().setContent { MaterialTheme {
            SourceTabs(state, { state = state.copy(selected = it) }, { state = state.copy(selected = sources[it * 20].metadata.id) })
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(20)
        val next = compose.onNodeWithContentDescription("Next source page").assertIsDisplayed()
        val bounds = next.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Previous source page").assertIsNotEnabled()
        compose.onNodeWithText("Source 19").performScrollTo().assertIsDisplayed()
        assertEquals(bounds, next.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("Source page 1 of 5").performClick()
        compose.onNodeWithText("Page 5").performScrollTo().performClick()
        compose.onNodeWithText("Source 80").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("Source 0").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(9)
        compose.onNodeWithContentDescription("Next source page").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Source page 5 of 5").assertIsDisplayed()
    }

    @Test fun twentySourcesUseTheWholeTabRowAndTwentyOneExposeTheSecondPage() {
        var sources by mutableStateOf(sources(20))
        var selected by mutableStateOf(sources.first().metadata.id)
        activity.get().setContent { MaterialTheme {
            SourceTabs(DiscoveryPageState(sources, selected), { selected = it }, { selected = sources[it * 20].metadata.id })
        } }
        compose.onNodeWithContentDescription("Next source page").assertDoesNotExist()
        compose.runOnIdle { sources = sources(21) }
        compose.onNodeWithContentDescription("Next source page").performClick()
        compose.onNodeWithText("Source 20").assertIsSelected().assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(1)
    }

    @Test fun ninetyNinePagesKeepTheFullNumberAndSeparateArrowAndJumpTargets() {
        val sources = sources(1980)
        var state by mutableStateOf(DiscoveryPageState(sources, sources[1960].metadata.id))
        var fontScale by mutableFloatStateOf(1f)
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { MaterialTheme(typography = AppTypography) {
                SourceTabs(state, { state = state.copy(selected = it) },
                    { state = state.copy(selected = sources[it * 20].metadata.id) })
            } }
        }
        for (scale in listOf(1f, 1.3f)) {
            compose.runOnIdle { fontScale = scale }
            val number = compose.onNodeWithText("99/99").assertIsDisplayed()
            val layout = mutableListOf<TextLayoutResult>()
            number.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertEquals(1, layout.single().lineCount)
            assertFalse("fontScale=$scale, size=${layout.single().size}, " +
                "widthOverflow=${layout.single().didOverflowWidth}, heightOverflow=${layout.single().didOverflowHeight}, " +
                "textWidth=${layout.single().getLineRight(0)}, textHeight=${layout.single().getLineBottom(0)}",
                layout.single().hasVisualOverflow)
            val previous = compose.onNodeWithContentDescription("Previous source page").assertIsDisplayed()
            val page = compose.onNodeWithContentDescription("Source page 99 of 99").assertIsDisplayed()
            val next = compose.onNodeWithContentDescription("Next source page").assertIsDisplayed().assertIsNotEnabled()
            val previousBounds = previous.fetchSemanticsNode().boundsInRoot
            val pageBounds = page.fetchSemanticsNode().boundsInRoot
            val nextBounds = next.fetchSemanticsNode().boundsInRoot
            assertTrue(previousBounds.right <= pageBounds.left)
            assertTrue(pageBounds.right <= nextBounds.left)
            assertTrue(nextBounds.right <= 360f)
            assertTrue(previousBounds.left >= 250f)
            if (scale == 1f) assertEquals("Pagination occupies a quarter of the 360dp row",
                90f, nextBounds.right - previousBounds.left, 1f)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(20)

            previous.performTouchInput { click(center) }
            compose.onNodeWithText("98/99").assertIsDisplayed()
            next.performTouchInput { click(center) }
            page.performTouchInput { click(center) }
            compose.onNodeWithText("Page 1").performClick()
            compose.onNodeWithText("1/99").assertIsDisplayed()
            compose.onNodeWithContentDescription("Previous source page").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Source page 1 of 99").performTouchInput { click(center) }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(99)
            compose.onNodeWithText("Page 99").performClick()
            compose.onNodeWithText("99/99").assertIsDisplayed()
        }
    }

    @Test @Config(qualifiers = "en-rUS-w320dp-h800dp-mdpi")
    fun largeTextMovesPaginationBelowTheTabsAndKeepsFullTouchTargets() {
        val sources = sources(21)
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) { MaterialTheme {
                SourceTabs(DiscoveryPageState(sources, sources.first().metadata.id), {}, {})
            } }
        }
        val tab = compose.onNodeWithText("Source 0").fetchSemanticsNode().boundsInRoot
        for (description in listOf("Previous source page", "Source page 1 of 2", "Next source page")) {
            val button = compose.onNodeWithContentDescription(description).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= tab.bottom)
            assertTrue(bounds.right <= 320)
        }
    }

    @Test fun scopePickerListsOnlyCategoriesInUseAndKeepsAnEmptyActiveScopeSelected() {
        val anime = sources(1).single().let { it.copy(metadata = it.metadata.copy(category = SourceCategory.Anime)) }
        var state by mutableStateOf(DiscoveryPageState(scope = SourceCategory.Female, availableSources = listOf(anime)))
        activity.get().setContent { MaterialTheme { Column {
            SourceScopeTitle(state) { state = state.copy(scope = it) }
            SourceScopeEmpty(state, true, { state = state.copy(scope = it) }, {})
        } } }
        compose.onNodeWithText("No enabled Female fiction sources support discovery.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Source scope: Female fiction").performClick()
        compose.onAllNodes(isSelectable()).assertCountEquals(3)
        compose.onNode(hasText("Female fiction") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("Mainstream").assertDoesNotExist()
        compose.onNodeWithText("Light novels").performClick()
        assertEquals(SourceCategory.Anime, state.scope)
        compose.onNodeWithContentDescription("Source scope: Light novels").performClick()
        compose.onAllNodes(isSelectable()).assertCountEquals(2)
        compose.onNodeWithText("Female fiction").assertDoesNotExist()
        compose.onNode(hasText("All sources") and isSelectable()).performClick()
        assertNull(state.scope)
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h800dp-mdpi")
    fun simplifiedChineseScopeMessageKeepsActionsVisibleWithLargeText() {
        assertScopeMessageAndActions(true,
            "“经典文学”分类下暂无已启用且支持发现的书源。", "全部书源")
    }

    @Test @Config(qualifiers = "zh-rTW-w320dp-h800dp-mdpi")
    fun traditionalChineseScopeMessageKeepsActionsVisibleWithLargeText() {
        assertScopeMessageAndActions(false,
            "「經典文學」分類下暫無已啟用且提供分類的書源。", "全部書源")
    }

    @Test @Config(qualifiers = "ru-rRU-w320dp-h800dp-mdpi")
    fun russianScopeMessageKeepsActionsVisibleWithLargeText() {
        assertScopeMessageAndActions(true,
            "В категории «Классика» нет включённых источников с обзором книг.", "Все источники")
    }

    private fun assertScopeMessageAndActions(explore: Boolean, message: String, all: String) {
        var state by mutableStateOf(DiscoveryPageState(scope = SourceCategory.Literature))
        var opened = false
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.6f)) { MaterialTheme(typography = AppTypography) {
                SourceScopeEmpty(state, explore, { state = state.copy(scope = it) }, { opened = true })
            } }
        }
        val label = compose.onNodeWithText(message).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithText(activity.get().getString(R.string.sources_add)).assertIsDisplayed().performClick()
        assertTrue(opened)
        compose.onNodeWithText(all).assertIsDisplayed().performClick()
        assertNull(state.scope)
    }
}
