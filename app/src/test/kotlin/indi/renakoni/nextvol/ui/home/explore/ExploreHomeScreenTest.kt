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

    @Test fun discoveryEmptyPageIsOnlyTerminalWhenItsCursorEnds() {
        var state by mutableStateOf(DiscoveryResultsState(title = "Results", loaded = true, hasMore = true))
        activity.get().setContent { MaterialTheme {
            DiscoveryResultsScreen(state, onFilter = { _, _ -> }, onLoadMore = {}, onRefresh = {},
                onScroll = {}, onBook = {}, onManageSources = {}, onSettings = {}, onBack = {})
        } }
        compose.onNodeWithText(activity.get().getString(R.string.discovery_load_more)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_no_books)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(hasMore = false) }
        compose.onNodeWithText(activity.get().getString(R.string.discovery_no_books)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_load_more)).assertDoesNotExist()
    }

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

    @Test fun bookListedWithoutACoverAsksForItsDetailCover() {
        val id = Identifier("fixture", "Coverless source")
        val requested = mutableListOf<String>()
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to content(id))),
                {}, { _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, coverFor = { requested += it; null })
        } }
        compose.onNode(hasClickAction() and hasText("Same book")).assertExists()
        compose.runOnIdle { assertEquals(listOf(SourceBookId(id, "same").storageKey), requested) }
    }

    @Test fun partialFeedRemainsVisibleAndNavigableWhileLaterPreviewsLoad() {
        val id = Identifier("fixture", "Progressive source")
        val page = content(id).copy(loaded = false, loading = true)
        var more: SourceDiscoverySection? = null
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, _ -> }, {}, { more = it }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        // The indeterminate refresh indicator intentionally remains active.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasClickAction() and hasText("Same book")).assertExists()
        compose.onNodeWithContentDescription("Show more").performClick()
        assertEquals(page.sections.single(), more)
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

    @Test fun aDecayedFeedsSavedPositionSurvivesTheShortFirstBatch() {
        val id = Identifier("fixture", "Decayed source")
        val sections = List(20) { index ->
            SourceDiscoverySection("$index", "Section $index", emptyList(), SourceDiscoveryTarget(id, "/$index"))
        }
        val saved = DiscoveryScroll(8, 0)
        var page by mutableStateOf(DiscoveryPageContent(loading = true, scroll = saved))
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, position -> page = page.copy(scroll = position) }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { page = page.copy(sections = sections.take(2)) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { page = page.copy(sections = sections, loaded = true, loading = false) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Section 8").assertIsDisplayed()
        compose.runOnIdle { assertEquals(saved, page.scroll) }
    }

    @Test fun scrollingDuringReloadKeepsTheNewPositionInsteadOfRestoringTheOldOne() {
        val id = Identifier("fixture", "Decayed source")
        val sections = List(30) { index ->
            SourceDiscoverySection("$index", "Section $index", emptyList(), SourceDiscoveryTarget(id, "/$index"))
        }
        var page by mutableStateOf(DiscoveryPageContent(loading = true, scroll = DiscoveryScroll(8, 0), sections = sections.take(20)))
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, position -> page = page.copy(scroll = position) }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performTouchInput { swipeDown(durationMillis = 300) }
        compose.mainClock.advanceTimeBy(1000)
        val moved = page.scroll
        assertTrue(moved.index < 8)
        compose.runOnIdle { page = page.copy(sections = sections, loaded = true, loading = false) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(moved, page.scroll) }
    }

    @Test fun oneRealSourceAndItsUnsupportedSearchDoNotCreatePlaceholders() {
        val id = Identifier("fixture", "Only source")
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id, setOf(SourceCapability.Explore))), id, mapOf(id to content(id))),
                {}, { _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(1)
        compose.onNodeWithText("Only source").assertIsSelected()
        compose.onNodeWithContentDescription("Search").assertIsEnabled()
        compose.onNodeWithContentDescription("Categories").assertDoesNotExist()
    }

    @Test fun largeFeedCanOpenTheLastSectionWithoutLosingItsCategoryOrSource() {
        val id = Identifier("fixture", "Large source")
        val sections = List(326) { index -> SourceDiscoverySection("$index", "Category $index", emptyList(),
            SourceDiscoveryTarget(id, "/category/$index"), "category-$index") }
        val opened = mutableListOf<SourceDiscoverySection>()
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id,
                mapOf(id to DiscoveryPageContent(loaded = true, sections = sections))),
                {}, { _, _ -> }, {}, { opened += it }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(sections.lastIndex)
        compose.onNodeWithText("Category 325").assertExists()
        compose.onAllNodesWithContentDescription("Show more").onLast().performClick()
        assertEquals(listOf(sections.last()), opened)
    }

    @Test fun emptySourceStateOffersManagementAndNoFakeTabs() {
        var opened = 0
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(), {}, { _, _ -> }, {}, {}, {}, {}, { opened++ }, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertCountEquals(0)
        compose.onNodeWithText("Book sources").performClick()
        assertEquals(1, opened)
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

    @Test fun pendingPreviewKeepsItsEntryUsableAndDoesNotShowEmptyUntilItCompletes() {
        val id = Identifier("fixture", "Pending source")
        val section = SourceDiscoverySection("daily", "Daily", emptyList(), SourceDiscoveryTarget(id, "/daily"), previewLoading = true)
        var page by mutableStateOf(DiscoveryPageContent(loading = true, sections = listOf(section)))
        var opened: SourceDiscoverySection? = null
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, _ -> }, {}, { opened = it }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Daily").assertIsDisplayed()
        compose.onNodeWithText("This list has no books yet.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show more").performClick()
        assertEquals(section, opened)
        compose.runOnIdle { page = page.copy(loaded = true, loading = false, sections = listOf(section.copy(previewLoading = false))) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("This list has no books yet.").assertIsDisplayed()
    }

    @Test fun successfulEmptyPreviewShowsItsStateAlongsideTheMoreAction() {
        val id = Identifier("fixture", "Empty list source")
        val section = SourceDiscoverySection("recent", "Recently updated", emptyList(), SourceDiscoveryTarget(id, "/recent"))
        var opened: SourceDiscoverySection? = null
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id,
                mapOf(id to DiscoveryPageContent(loaded = true, sections = listOf(section)))),
                {}, { _, _ -> }, {}, { opened = it }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        } }
        compose.onNodeWithText("Recently updated").assertIsDisplayed()
        compose.onNodeWithText("This list has no books yet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show more").performClick()
        assertEquals(section, opened)
    }

    @Test fun multipleFailedPreviewsKeepDetailsSeparateAndSuccessfulBooksBrowsable() {
        val id = Identifier("fixture", "Partial source")
        val broken = SourceDiscoverySection("broken", "Broken preview", emptyList(), SourceDiscoveryTarget(id, "/broken"),
            previewFailure = DiscoveryPreviewFailure(DiscoveryError.InvalidRules, "header"), previewRetryAvailable = true)
        val noTarget = broken.copy(id = "no-target", title = "Preview without a list", more = null,
            previewFailure = DiscoveryPreviewFailure(DiscoveryError.Network, "ruleExplore.bookList"), previewRetryAvailable = false)
        val page = content(id).copy(sections = listOf(broken, noTarget) + content(id).sections)
        val opened = mutableListOf<SourceDiscoverySection>()
        val books = mutableListOf<SourceBookId>()
        activity.get().setContent { MaterialTheme {
            ExploreHomeScreen(DiscoveryPageState(listOf(listing(id)), id, mapOf(id to page)),
                {}, { _, _ -> }, {}, {}, { books += it }, {}, {}, { _, _ -> }, { _, _ -> }, {}, onRetryPreview = { opened += it })
        } }
        compose.onNodeWithText("Source rule:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("This list has no books yet.").assertDoesNotExist()
        compose.onAllNodesWithText("Error details").onFirst().performClick()
        compose.onNodeWithText("Source rule: header").assertIsDisplayed()
        compose.onNodeWithText("Error type: InvalidRules").assertIsDisplayed()
        compose.onNodeWithText("Source rule: ruleExplore.bookList").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf(broken), opened)
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToIndex(1)
        compose.onNodeWithText("Preview without a list").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onAllNodesWithText("Error details").onFirst().performClick()
        compose.onNodeWithText("Source rule: ruleExplore.bookList").assertIsDisplayed()
        compose.onNodeWithText("Error type: Network").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(hasClickAction() and hasText("Same book")).performScrollTo().performClick()
        assertEquals(listOf(SourceBookId(id, "same")), books)
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
