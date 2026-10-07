package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.explore.SourceSearchFailure
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.ui.home.explore.search.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
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
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchHubScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var state by mutableStateOf(SearchHubState())
    private var submitted: String? = null
    private var opened: String? = null
    private var retried = false
    private var back = false
    private var visible by mutableStateOf(true)
    private val source = SearchHubSource(Identifier("fixture", "a"), "Source A", SourceCategory.Adult)

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun render(fontScale: Float = 1f, height: Int = 640) {
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MaterialTheme { Box(Modifier.height(height.dp)) {
                    val holder = rememberSaveableStateHolder()
                    if (visible) holder.SaveableStateProvider("search") {
                    SearchHubScreen(state, onQuery = { state = state.copy(query = it, submittedKeyword = "") },
                        onSearch = { submitted = it }, onScope = { state = state.copy(scope = it, selectedSource = null) },
                        onSelectSource = { state = state.copy(scope = null, selectedSource = it) },
                        onDeleteHistory = { value -> state = state.copy(history = state.history - value) },
                        onClearHistory = { state = state.copy(history = emptyList()) },
                        onLoadMore = {}, onStop = {}, onResume = {}, onRetry = { retried = true },
                        onManageSources = {}, onSource = {}, onBook = { opened = it }, onBack = { back = true })
                    }
                } }
            }
        }
    }

    @Test fun hundredHistoryEntriesCanBeReachedSearchedDeletedAndCleared() {
        state = SearchHubState(history = (1..100).map { "History $it" }, sources = listOf(source))
        render()
        compose.onNodeWithText("History 1").assertIsDisplayed()
        compose.onNodeWithText("Source A").assertDoesNotExist()
        compose.onNodeWithTag("search_history").performScrollToNode(hasText("History 100"))
        compose.onNodeWithText("History 100").assertIsDisplayed().performClick()
        assertEquals("History 100", submitted)
        compose.onNodeWithContentDescription("Delete history: History 100").performClick()
        compose.onNodeWithText("History 100").assertDoesNotExist()
        compose.onNodeWithTag("search_history").performScrollToIndex(0)
        compose.onNodeWithText("Clear All").performClick()
        compose.onNodeWithText("History 1").assertDoesNotExist()
        compose.onNodeWithText("Find your next book").assertIsDisplayed()
    }

    @Test fun historyRemainsUsableWithLargeTextAndKeyboardSizedViewport() {
        state = SearchHubState(history = (1..100).map { "A longer history entry $it" }, sources = listOf(source))
        render(fontScale = 1.6f, height = 380)
        compose.onNodeWithTag("search_history").performScrollToNode(hasText("A longer history entry 100"))
        compose.onNodeWithText("A longer history entry 100").assertIsDisplayed().performClick()
        assertEquals("A longer history entry 100", submitted)
        compose.onNodeWithContentDescription("Delete history: A longer history entry 100").assertIsDisplayed().performClick()
        assertFalse(state.history.contains("A longer history entry 100"))
    }

    @Test fun scopePickerShowsGroupsInsteadOfThousandsOfSourceChips() {
        state = SearchHubState(sources = List(1000) { source.copy(id = Identifier("fixture", "$it"), name = "Source $it") })
        render()
        compose.onNodeWithText("All sources").performClick()
        compose.onNodeWithText("Source 0").assertDoesNotExist()
        compose.onNodeWithTag("search_source_query").assertDoesNotExist()
        compose.onNodeWithText("One source").assertIsDisplayed()
        compose.onNodeWithText("Mainstream").assertDoesNotExist()
        compose.onNodeWithTag("search_scope_options").performScrollToNode(hasText("r18"))
        compose.onNodeWithText("r18").performClick()
        assertEquals(SourceCategory.Adult, state.scope)
        compose.onNodeWithText("Source 0").assertDoesNotExist()
        compose.onNodeWithText("Searchable sources: 1000").assertIsDisplayed()
    }

    @Test fun singleSourceNameKeepsItsArrowAnchoredForEmptyShortAndLongNames() {
        state = SearchHubState(sources = listOf(source))
        render(fontScale = 1.6f)
        compose.onNodeWithTag("search_scope").performClick()
        val arrow = compose.onNodeWithTag("search_source_arrow", useUnmergedTree = true)
        val emptyBounds = arrow.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("One source").assertIsNotSelected()
        compose.runOnIdle { state = state.copy(selectedSource = source.id) }
        compose.onNodeWithText("One source").assertIsSelected().assert(hasText(source.name))
        assertEquals(emptyBounds, arrow.fetchSemanticsNode().boundsInRoot)
        val longName = "A very long source name · 中英文混排阅读资料珍藏版 Archive 300"
        compose.runOnIdle { state = state.copy(sources = listOf(source.copy(name = longName))) }
        compose.onNodeWithText("One source").assert(hasText(longName))
        assertEquals(emptyBounds, arrow.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state = state.copy(sources = emptyList(), selectedSourceName = "Saved source") }
        compose.onNodeWithText("One source").assert(hasText("Saved source"))
        assertEquals(emptyBounds, arrow.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun oneOfAThousandSourcesCanBeFoundWithoutChangingTheBookQuery() {
        state = SearchHubState(query = "A book", scope = SourceCategory.Adult, sources = List(1000) {
            source.copy(id = Identifier("fixture", "$it"), name = "Source $it",
                category = if (it == 999) null else source.category)
        })
        render()
        compose.onNodeWithTag("search_scope").performClick()
        compose.onNodeWithText("Source 0").assertDoesNotExist()
        compose.onNodeWithText("One source").performClick()
        compose.onNodeWithTag("search_source_query").assertIsFocused()
        val fieldTop = compose.onNodeWithTag("search_source_query").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("search_source_query").performTextInput("999")
        assertEquals(fieldTop, compose.onNodeWithTag("search_source_query").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("Source 999").assertIsDisplayed().performClick()
        assertEquals(Identifier("fixture", "999"), state.selectedSource)
        assertNull(state.scope)
        assertEquals("A book", state.query)
        assertNull(submitted)
        compose.onNodeWithTag("search_source_query").assertDoesNotExist()
        compose.onNodeWithTag("search_scope").assertTextEquals("Source 999")
        compose.onNodeWithText("Searchable sources: 1").assertDoesNotExist()
    }

    @Test fun sourceLookupSearchesAcrossGroupsAndClearingRestoresTheCompactPicker() {
        val other = source.copy(id = Identifier("fixture", "wenku8"), name = "Light novel library", category = SourceCategory.Anime)
        state = SearchHubState(sources = listOf(source, other), scope = SourceCategory.Adult)
        render()
        compose.onNodeWithTag("search_scope").performClick()
        compose.onNodeWithText("One source").performClick()
        compose.onNodeWithTag("search_source_query").performTextInput("WENKU")
        compose.onNodeWithText("Light novel library").assertIsDisplayed()
        compose.onNodeWithTag("search_source_query").performTextReplacement("unknown")
        compose.onNodeWithText("No matching sources. Try another name.").assertIsDisplayed()
        compose.onNodeWithTag("search_source_query").performTextClearance()
        compose.onNodeWithText("Light novel library").assertDoesNotExist()
        compose.onNodeWithTag("search_scope_back").performClick()
        compose.onNodeWithTag("search_source_query").assertDoesNotExist()
        compose.onNodeWithText("One source").assertIsDisplayed()
        assertEquals(SourceCategory.Adult, state.scope)
    }

    @Test fun returningFromSourceLookupKeepsTheSelectionAndLetsGroupsBeSelectedDirectly() {
        state = SearchHubState(query = "A book", submittedKeyword = "A book", sources = listOf(source), selectedSource = source.id)
        render()
        compose.onNodeWithTag("search_scope").performClick()
        compose.onNodeWithText("One source").performClick()
        compose.onNode(hasText("Source A") and hasAnyAncestor(hasTestTag("search_source_matches"))).assertIsDisplayed()
        compose.onNodeWithTag("search_source_query").performTextInput("unknown")
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("search_source_query").assertIsFocused().assertTextContains("unknown")
        compose.onNodeWithTag("search_scope_back").performClick()
        assertEquals(source.id, state.selectedSource)
        assertEquals("A book", state.submittedKeyword)
        compose.onNodeWithText("One source").performClick()
        compose.onNodeWithTag("search_source_query").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("search_scope_back").performClick()
        compose.onNodeWithTag("search_scope_options").performScrollToNode(hasText("r18"))
        compose.onNodeWithText("r18").performClick()
        assertNull(state.selectedSource)
        assertEquals(SourceCategory.Adult, state.scope)
        assertEquals("A book", state.submittedKeyword)
    }

    @Test
    @Config(sdk = [35])
    fun systemBackReturnsFromSourceLookupBeforeDismissingTheScopeSheet() {
        state = SearchHubState(query = "A book", sources = listOf(source), selectedSource = source.id)
        render()
        compose.onNodeWithTag("search_scope").performClick()
        compose.onNodeWithText("One source").performClick()
        compose.onNodeWithTag("search_source_query").performTextInput("unknown")
        compose.onNodeWithTag("search_source_query").performImeAction()
        compose.runOnIdle {
            ShadowDialog.getLatestDialog().window!!.decorView.findViewTreeOnBackPressedDispatcherOwner()!!
                .onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("search_source_query").assertDoesNotExist()
        compose.onNodeWithText("One source").assertIsDisplayed().assertIsSelected()
        assertEquals(source.id, state.selectedSource)
        assertEquals("A book", state.query)
        assertFalse(back)
        compose.runOnIdle {
            ShadowDialog.getLatestDialog().window!!.decorView.findViewTreeOnBackPressedDispatcherOwner()!!
                .onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("search_scope_options").assertDoesNotExist()
        compose.onNodeWithTag("search_scope").assertIsDisplayed()
        assertFalse(back)
    }

    @Test fun emptySingleSourceCanExpandWithoutLosingTheKeyword() {
        state = SearchHubState(query = "A book", submittedKeyword = "A book", selectedSource = source.id,
            sources = listOf(source.copy(pending = false)))
        render()
        compose.onNodeWithText("No books found in Source A").assertIsDisplayed()
        compose.onNodeWithText("Search all sources").performClick()
        assertNull(state.selectedSource)
        assertNull(state.scope)
        assertEquals("A book", state.submittedKeyword)
    }

    @Test fun filteredEmptyPageShowsLoadMoreInsteadOfTerminalNoResults() {
        state = SearchHubState(query = "book", submittedKeyword = "book",
            sources = listOf(source.copy(pending = false, nextPage = 2)))
        render()
        compose.onNodeWithText(activity.get().getString(R.string.search_load_more)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.search_no_results)).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(sources = listOf(source.copy(pending = false))) }
        compose.onNodeWithText(activity.get().getString(R.string.search_no_results)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.search_load_more)).assertDoesNotExist()
    }

    @Test fun booksAreVerticalSourceBoundRowsAndEmptySourcesHaveNoPlaceholder() {
        val other = source.copy(id = Identifier("fixture", "empty"), name = "Empty source")
        val info = BookInformation("book", "A book", author = "An author", description = "A short description",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.MIN, isComplete = false)
        state = SearchHubState(query = "book", submittedKeyword = "book", sources = listOf(source, other),
            books = List(30) { index -> SearchHubBook("book-$index", source.id, source.name,
                info.copy(title = "Book $index"), flowOf(Ok(info.copy(title = "Book $index")))) })
        render()
        compose.onNodeWithText("Book 0").assertIsDisplayed()
        compose.onNodeWithText("Empty source").assertDoesNotExist()
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("Book 29"))
        compose.onNodeWithText("Book 29").performClick()
        assertEquals("book-29", opened)
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        compose.onNodeWithText("Book 29").assertIsDisplayed()
        compose.onNodeWithText("Book 0").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(query = "new", submittedKeyword = "new", revision = state.revision + 1) }
        compose.onNodeWithText("Book 0").assertIsDisplayed()
    }

    @Test fun updatedMetadataIsDisplayedEvenWhenTheInformationFlowStillHoldsAnOlderPreview() {
        val info = BookInformation("book", "Old preview", author = "Writer", description = "",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.MIN, isComplete = false)
        val information = flowOf(Ok(info))
        state = SearchHubState(query = "book", submittedKeyword = "book", sources = listOf(source),
            books = listOf(SearchHubBook("book", source.id, source.name, info, information)))
        render()
        compose.onNodeWithText("Old preview").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(books = state.books.map { it.copy(preview = info.copy(title = "Updated title")) }) }
        compose.onNodeWithText("Updated title").assertIsDisplayed()
        compose.onNodeWithText("Old preview").assertDoesNotExist()
        assertSame(information, state.books.single().information)
    }

    @Test fun replacingTheInformationFlowClearsMetadataFromThePreviousQuery() {
        val info = BookInformation("book", "Previous query title", author = "Writer", description = "",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.MIN, isComplete = false)
        state = SearchHubState(query = "book", submittedKeyword = "book", sources = listOf(source),
            books = listOf(SearchHubBook("book", source.id, source.name, null, flowOf(Ok(info)))))
        render()
        compose.onNodeWithText("Previous query title").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(books = state.books.map { it.copy(information = emptyFlow()) })
        }
        compose.onNodeWithText("Previous query title").assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.search_book_loading)).assertIsDisplayed()
    }

    @Test fun editingFiltersHistoryAndSubmittingWorksWithTheImeAndAccessibleBackButton() {
        state = SearchHubState(history = listOf("first", "second"), sources = listOf(source))
        render()
        compose.onNodeWithTag("search_query").performTextInput("sec")
        compose.onNodeWithText("first").assertDoesNotExist()
        compose.onNodeWithText("second").assertIsDisplayed()
        compose.onNodeWithTag("search_query").performImeAction()
        assertEquals("sec", submitted)
        compose.onNodeWithContentDescription(activity.get().getString(R.string.sources_back)).performClick()
        assertTrue(back)
    }

    @Test fun failureIsDistinctFromEmptyAndCanBeInspectedAndRetried() {
        state = SearchHubState(query = "book", submittedKeyword = "book", sources = listOf(
            source.copy(failure = SourceSearchFailure(DiscoveryError.Network))))
        render()
        compose.onNodeWithText("No Results Found").assertDoesNotExist()
        compose.onNodeWithText("Failed: 1").performClick()
        compose.onNodeWithText("Source A").assertIsDisplayed()
        compose.onNodeWithText("Retry failed sources").performClick()
        assertTrue(retried)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp-mdpi")
    fun russianScopeAndSearchActionsRemainReachableWithLargeText() {
        state = SearchHubState(query = "book", submittedKeyword = "book", searching = true, total = 1000,
            sources = listOf(source.copy(failure = SourceSearchFailure(DiscoveryError.Network))))
        render(fontScale = 1.6f)
        compose.onNodeWithText(activity.get().getString(R.string.source_range_all)).assertIsDisplayed().performClick()
        val category = activity.get().getString(R.string.source_category_adult)
        compose.onNodeWithTag("search_scope_options").performScrollToNode(hasText(category))
        compose.onNodeWithText(category).performClick()
        compose.onNodeWithContentDescription(activity.get().getString(R.string.search_stop)).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.get().getString(R.string.search_hub_title)).assertIsDisplayed()
    }
}
