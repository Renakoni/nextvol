package indi.renakoni.nextvol.ui.home.categories

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.github.michaelbull.result.get
import hnovel.content.DiscoveryCatalogFixtures
import hnovel.content.RuleSourceFixture
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.RuleDiscoveryProvider
import indi.renakoni.nextvol.ui.home.discovery.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CategoriesScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun listing(id: Identifier, name: String) = SourceListing(
        SourceMetadata(WebDataSourceItem(id, name, "fixture"), setOf(SourceCapability.Categories)), SourceStatus.Ready)
    private fun category(id: Identifier) = SourceDiscoveryCategory("category", "Same category", SourceDiscoveryTarget(id, "tag"))

    @Test fun sourceTabsAndCategoryClicksKeepOwningIdentityAndSettingsCallback() {
        val a = Identifier("fixture", "a")
        val b = Identifier("fixture", "b")
        var state by mutableStateOf(DiscoveryPageState(listOf(listing(a, "Source A"), listing(b, "Source B")), a,
            mapOf(a to DiscoveryPageContent(listOf(category(a)), loaded = true), b to DiscoveryPageContent(listOf(category(b)), loaded = true))))
        val clicked = mutableListOf<SourceDiscoveryCategory>()
        var settings = 0
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(state, { state = state.copy(selected = it) }, { clicked += it }, { _, _ -> }, {}, {}, { settings++ }, {})
        } }
        compose.onNodeWithText("Source A").assertIsSelected()
        compose.onNodeWithText("Same category").performClick()
        compose.onNodeWithText("Source B").performClick().assertIsSelected()
        compose.onNodeWithText("Same category").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertEquals(listOf(a, b), clicked.map { it.target.sourceId })
        assertEquals(1, settings)
    }

    @Test fun oneSourceStillDisplaysItsTab() {
        val id = Identifier("fixture", "one")
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(listing(id, "Only source")), id,
                mapOf(id to DiscoveryPageContent(listOf(category(id)), loaded = true))), {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Only source").assertIsSelected()
        compose.onNodeWithText("Same category").assertExists()
    }

    @Test fun delayedCategoriesStartAtTheFirstTag() {
        val id = Identifier("fixture", "delayed")
        var page by mutableStateOf(DiscoveryPageContent(loading = true))
        var scroll = DiscoveryScroll()
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(listing(id, "Delayed source")), id, mapOf(id to page)),
                {}, {}, { _, position -> scroll = position }, {}, {}, {}, {})
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            page = page.copy(loaded = true, loading = false, categories = List(80) { index ->
                SourceDiscoveryCategory("$index", "Category $index", SourceDiscoveryTarget(id, "/$index"))
            })
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Category 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(DiscoveryScroll(), scroll) }
    }

    @Test fun qidianCatalogScrollsPast128RowsAndSelectsItsLastOriginalTarget() {
        val (id, categories) = runBlocking {
            RuleSourceFixture().use { fixture ->
                fixture.source { raw -> JsonObject(raw + mapOf(
                    "exploreUrl" to JsonPrimitive(DiscoveryCatalogFixtures.rows(0).toString()),
                    "ruleExplore" to raw.getValue("ruleSearch"))) }.use { source ->
                    val id = Identifier("rules", source.definition.sourceId)
                    id to RuleDiscoveryProvider(source).catalog().get()!!.categories.map {
                        SourceDiscoveryCategory(it.id, it.title, SourceDiscoveryTarget(id, it.target))
                    }
                }
            }
        }
        assertEquals(326, categories.size)
        val clicked = mutableListOf<SourceDiscoveryCategory>()
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(listing(id, "Qidian")), id,
                mapOf(id to DiscoveryPageContent(categories, loaded = true))), {}, { clicked += it }, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onAllNodesWithText(categories.last().title).onLast().performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(categories.last()), clicked)
        assertTrue(clicked.single().target.target.isNotBlank())
    }

    @Test fun processingLimitIsVisibleWithItsSourceFieldInDetails() {
        val id = Identifier("fixture", "limited")
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(listing(id, "Large source")), id,
                mapOf(id to DiscoveryPageContent(error = DiscoveryError.Limit, errorField = "exploreUrl"))),
                {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        compose.onNodeWithText(activity.get().getString(R.string.discovery_limit)).assertExists()
        compose.onNodeWithText("Source rule: exploreUrl").assertDoesNotExist()
        compose.onNodeWithText("Error details").performClick()
        compose.onNodeWithText("Source rule: exploreUrl").assertExists()
        compose.onNodeWithText("Error type: Limit").assertExists()
    }

    @Test fun emptyStateOpensSourceManagement() {
        var opened = 0
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(), {}, {}, { _, _ -> }, {}, { opened++ }, {}, {})
        } }
        compose.onNodeWithText("No enabled book source provides categories. Add or enable a source in source management.").assertExists()
        compose.onNodeWithText("Book sources").performClick()
        assertEquals(1, opened)
    }

    @Test fun sourceFormChoicesAndButtonsUseHostCallbacksInsideOneSourceTab() {
        val id = Identifier("fixture", "actions")
        val values = mutableListOf<Pair<String, String>>()
        val actions = mutableListOf<Pair<String, Boolean>>()
        val page = DiscoveryPageContent(listOf(category(id)), loaded = true,
            filters = listOf(DiscoveryFilter.Choice("Sort", "Sort", linkedMapOf("new" to "New", "popular" to "Popular"), "new")),
            values = mapOf("Sort" to "new"), buttons = listOf(DiscoveryButton("login", "Sign in")))
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(DiscoveryPageState(listOf(listing(id, "Rule source")), id, mapOf(id to page)),
                {}, {}, { _, _ -> }, {}, {}, {}, {}, onInput = { key, value -> values += key to value },
                onAction = { key, long -> actions += key to long })
        } }
        compose.onNodeWithText("Rule source").assertIsSelected()
        compose.onNodeWithText("Sort: New").performClick()
        compose.onNodeWithText("Popular").performClick()
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Sign in").performTouchInput { longClick() }
        assertEquals(listOf("Sort" to "popular"), values)
        assertEquals(listOf("login" to false, "login" to true), actions)
    }

    @Test fun waitingForTheFirstSnapshotDoesNotDisplayTheEmptySourceMessage() {
        var state by mutableStateOf(DiscoveryPageState(loadingSources = true))
        activity.get().setContent { MaterialTheme {
            CategoriesScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {})
        } }
        val message = "No enabled book source provides categories. Add or enable a source in source management."
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithText("Book sources").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loadingSources = false) }
        compose.onNodeWithText(message).assertExists()
    }
}
