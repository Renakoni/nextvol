package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.ui.home.explore.home.ExploreHomeScreen
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
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
class ExploreHomeScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun listing(id: Identifier, capabilities: Set<SourceCapability> = setOf(SourceCapability.Explore, SourceCapability.Search, SourceCapability.Categories)) =
        SourceListing(SourceMetadata(WebDataSourceItem(id, id.id, "fixture"), capabilities), SourceStatus.Ready)
    private fun content(id: Identifier) = DiscoveryPageContent(loaded = true, sections = listOf(
        SourceDiscoverySection("list", "Recommended", listOf(SourceDiscoveryBook(SourceBookId(id, "same"), "Same book", "", "")),
            SourceDiscoveryTarget(id, "all"))))

    @Test fun sourceTabsAreTheOnlyTabsAndBooksAndMoreKeepTheirOwningSource() {
        val a = Identifier("fixture", "Source A")
        val b = Identifier("fixture", "Source B")
        var state by mutableStateOf(DiscoveryPageState(listOf(listing(a), listing(b)), a,
            mapOf(a to content(a), b to content(b))))
        val books = mutableListOf<SourceBookId>()
        val more = mutableListOf<SourceDiscoverySection>()
        var search: Identifier? = null
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(state, { state = state.copy(selected = it) }, { _, _ -> }, {}, { more += it }, { books += it },
                { search = state.selected }, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(2)
        compose.onNodeWithText("Source A").assertIsSelected()
        compose.onNode(hasClickAction() and hasText("Same book")).performClick()
        compose.onNodeWithContentDescription("Show more").performClick()
        compose.onNodeWithText("Source B").performClick().assertIsSelected()
        compose.onNode(hasClickAction() and hasText("Same book")).performClick()
        compose.onNodeWithContentDescription("Show more").performClick()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithContentDescription("Categories").assertDoesNotExist()
        compose.onNodeWithContentDescription("Source scope: All sources").assertIsDisplayed()
        assertEquals(listOf(a, b), books.map { it.sourceId })
        assertEquals(listOf(a, b), more.map { it.more!!.sourceId })
        assertEquals(b, search)
    }

    @Test fun delayedFeedStartsAtTheTopAndLaterUpdatesKeepTheReadersPosition() {
        val id = Identifier("fixture", "Delayed source")
        val sections = List(20) { index ->
            SourceDiscoverySection("$index", "Section $index", emptyList(), SourceDiscoveryTarget(id, "/$index"))
        }
        var page by mutableStateOf(DiscoveryPageContent(loading = true))
        var scroll = DiscoveryScroll()
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, position -> scroll = position }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { page = page.copy(sections = sections.take(10)) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Section 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(DiscoveryScroll(), scroll) }

        compose.runOnIdle { page = page.copy(sections = sections, loaded = true, loading = false) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Section 0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(DiscoveryScroll(), scroll) }

        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(8)
        compose.onNodeWithText("Section 8").assertIsDisplayed()
        val previous = scroll
        compose.runOnIdle { page = page.copy(sections = sections + sections.last().copy(id = "20", title = "Section 20")) }
        compose.onNodeWithText("Section 8").assertIsDisplayed()
        compose.runOnIdle { assertEquals(previous, scroll) }
    }

    @Test fun returningToTheFeedRestoresTheSavedPositionForEachSource() {
        val a = Identifier("fixture", "Source A")
        val b = Identifier("fixture", "Source B")
        fun page(id: Identifier) = DiscoveryPageContent(loaded = true, sections = List(20) { index ->
            SourceDiscoverySection("$index", "${id.id} section $index", emptyList(), SourceDiscoveryTarget(id, "/$index"))
        })
        var state by mutableStateOf(DiscoveryPageState(listOf(listing(a), listing(b)), a, mapOf(a to page(a), b to page(b))))
        var visible by mutableStateOf(true)
        activity.get().setContent { MaterialTheme {
            if (visible) ExploreHomeScreen(state, { state = state.copy(selected = it) }, { id, position ->
                state = state.copy(content = state.content + (id to state.content.getValue(id).copy(scroll = position)))
            }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(8)
        compose.onNodeWithText("Source A section 8").assertIsDisplayed()
        compose.onNodeWithText("Source B").performClick()
        compose.onNodeWithText("Source B section 0").assertIsDisplayed()
        compose.onNodeWithText("Source A").performClick()
        compose.onNodeWithText("Source A section 8").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Source A section 8").assertIsDisplayed()
    }

    @Test fun previewRetryStaysInItsEntryAndKeepsSuccessfulBooksAvailable() {
        val id = Identifier("fixture", "Partial source")
        val broken = SourceDiscoverySection("broken", "Broken preview", emptyList(), SourceDiscoveryTarget(id, "/broken"),
            previewFailure = DiscoveryPreviewFailure(DiscoveryError.InvalidRules, "ruleExplore.bookList"), previewRetryAvailable = true)
        val retried = mutableListOf<SourceDiscoverySection>()
        var opened = 0
        var refreshed = 0
        var page by mutableStateOf(content(id).copy(sections = listOf(broken) + content(id).sections))
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, _ -> }, { refreshed++ }, { opened++ }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {},
                onRetryPreview = { retried += it; page = page.copy(sections = listOf(it.copy(previewFailure = null, previewLoading = true)) + content(id).sections) })
        } }
        compose.onNodeWithText("Broken preview").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(broken), retried)
        assertEquals(0, opened)
        assertEquals(0, refreshed)
        compose.onNodeWithText("Loading books…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("This list has no books yet.").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasText("Same book")).performScrollTo().assertExists()
    }

    @Test fun ruleInputsAndActionsShareTheFeedAndFailuresKeepVisibleContent() {
        val id = Identifier("fixture", "Rule source")
        val input = mutableListOf<Pair<String, String>>()
        val actions = mutableListOf<Pair<String, Boolean>>()
        val page = content(id).copy(error = DiscoveryError.Network,
            filters = listOf(DiscoveryFilter.Choice("sort", "Sort", linkedMapOf("new" to "New", "popular" to "Popular"), "new")),
            values = mapOf("sort" to "new"), buttons = listOf(DiscoveryButton("login", "Sign in")))
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, _ -> }, {}, {}, {}, {}, {}, { key, value -> input += key to value },
                { key, long -> actions += key to long }, {})
        } }
        compose.onNodeWithText("Sort: New").performClick()
        compose.onNodeWithText("Popular").performClick()
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Sign in").performTouchInput { longClick() }
        compose.onNode(hasClickAction() and hasText("Same book")).performScrollTo().assertExists()
        assertEquals(listOf("sort" to "popular"), input)
        assertEquals(listOf("login" to false, "login" to true), actions)
    }
}
