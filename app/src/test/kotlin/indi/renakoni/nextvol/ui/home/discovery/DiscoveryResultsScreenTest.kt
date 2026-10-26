package indi.renakoni.nextvol.ui.home.discovery

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    private fun render() {
        activity.get().setContent {
            MaterialTheme(typography = AppTypography) {
                DiscoveryResultsScreen(state,
                    onFilter = { applied += it; state = state.copy(filters = it) },
                    onLoadMore = {}, onRefresh = {}, onScroll = {},
                    onBook = {}, onManageSources = {}, onSettings = {}, onBack = {})
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
}
