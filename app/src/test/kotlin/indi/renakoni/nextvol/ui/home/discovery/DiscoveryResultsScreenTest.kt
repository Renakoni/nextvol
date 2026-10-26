package indi.renakoni.nextvol.ui.home.discovery

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.SourceDiscoveryBook
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
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
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryResultsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var state by mutableStateOf(DiscoveryResultsState("Romance", sourceName = "Source A", loaded = true))
    private val applied = mutableListOf<Map<String, String>>()
    private var loadedMore = 0
    private var refreshed = 0
    private var settings = 0
    private var opened: SourceBookId? = null

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    private fun render(fontScale: Float = 1f) {
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme(typography = AppTypography) {
                    DiscoveryResultsScreen(state,
                        onFilter = { applied += it; state = state.copy(filters = it) },
                        onLoadMore = { loadedMore++ }, onRefresh = { refreshed++ }, onScroll = {},
                        onBook = { opened = it }, onManageSources = {}, onSettings = { settings++ }, onBack = {})
                }
            }
        }
    }

    @Test fun filtersAreStagedUntilAppliedAndResetCanBeCancelled() {
        val definitions = listOf(
            DiscoveryFilter.Choice("sort", "Sort", linkedMapOf("new" to "Latest", "popular" to "Popular"), "new"),
            DiscoveryFilter.Toggle("completed", "Completed"))
        state = state.copy(definitions = definitions, filters = filterValues(definitions, emptyMap()))
        render()
        compose.onNodeWithText("Sort").assertDoesNotExist()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Popular").performClick()
        compose.onNode(hasText("Completed") and isToggleable()).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(applied.isEmpty())
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Latest").assertIsSelected()
        compose.onNode(hasText("Completed") and isToggleable()).assertIsOff()
        compose.onNodeWithText("Popular").performClick()
        compose.onNode(hasText("Completed") and isToggleable()).performClick()
        compose.onNodeWithText("Apply filters").performClick()
        assertEquals(listOf(mapOf("sort" to "popular", "completed" to "true")), applied)
        compose.onNodeWithText("Sort: Popular").assertIsDisplayed()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Popular").assertIsSelected()
        compose.onNode(hasText("Completed") and isToggleable()).assertIsOn()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Apply filters").performClick()
        assertEquals(filterValues(definitions, emptyMap()), applied.last())
        assertEquals(2, applied.size)
        compose.onNodeWithText("Sort: Popular").assertDoesNotExist()
    }

    @Test fun sourceNumberAndTextInputsValidateBeforeApplyingTheWholeDraft() {
        state = state.copy(definitions = listOf(DiscoveryFilter.Number("limit", "Limit", 0, 10),
            DiscoveryFilter.Text("query", "Keyword")))
        render()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Limit").performTextReplacement("11")
        compose.onNodeWithText("Apply filters").assertIsNotEnabled()
        compose.onNodeWithText("Limit").performTextReplacement("5")
        compose.onNodeWithText("Keyword").performTextReplacement("winter")
        compose.onNodeWithText("Apply filters").assertIsEnabled().performClick()
        assertEquals(listOf(mapOf("limit" to "5", "query" to "winter")), applied)
    }

    @Test fun sourcesWithoutFiltersKeepPaginationAndOverflowActionsAvailable() {
        state = state.copy(hasMore = true)
        render()
        compose.onNodeWithContentDescription("Filters").assertDoesNotExist()
        compose.onNodeWithText("Source A").assertIsDisplayed()
        compose.onNodeWithText("No matches on the pages loaded so far. Try loading more.").assertIsDisplayed()
        compose.onNodeWithText("Load more").performClick()
        assertEquals(1, loadedMore)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Refresh").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Settings").performClick()
        assertEquals(1, refreshed)
        assertEquals(1, settings)
        compose.runOnIdle { state = state.copy(hasMore = false) }
        compose.onNodeWithText("No books match these filters.").assertIsDisplayed()
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test fun longBookTitlesWithMissingMetadataRemainClickableAtLargeFontScale() {
        val book = SourceDiscoveryBook(SourceBookId(Identifier("fixture", "a"), "one"),
            "A very long book title that wraps onto multiple lines while its author and cover are unavailable", "", "")
        state = state.copy(books = listOf(book))
        render(fontScale = 1.5f)
        compose.onNodeWithText(book.title).assertIsDisplayed().performClick()
        assertEquals(book.id, opened)
    }
}
