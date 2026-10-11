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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

}
