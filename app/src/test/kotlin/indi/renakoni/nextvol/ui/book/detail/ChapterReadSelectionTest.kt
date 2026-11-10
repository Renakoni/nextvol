package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavHostController
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.CompletableDeferred
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
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS-w400dp-h900dp")
class ChapterReadSelectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var openedChapter: String? = null
    private var read = false
    private val readStates = mutableListOf<Boolean>()
    private val writes = mutableListOf<Set<String>>()
    private val snackbar = SnackbarHostState()
    private val state = MutableDetailUiState().apply {
        readingAvailable = true
        bookInformation = Ok(BookInformation("book", "Book title", author = "Author", description = "",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.of(2026, 9, 19, 0, 0), isComplete = false))
        bookVolumes = Ok(BookVolumes("book", listOf(Volume("v1", "Volume 1",
            (0..3).map { ChapterInformation("chapter-$it", "Chapter $it") }))))
        userReadingData = UserReadingData("book", lastReadChapterId = "chapter-2",
            maxChapterReadingProgressMap = mapOf("chapter-0" to 1f, "chapter-1" to .7f, "chapter-2" to .4f))
    }

    @Before fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun tearDown() { activity.pause().stop().destroy() }

    private fun text(id: Int) = activity.get().getString(id)

    private fun show(save: suspend (Set<String>) -> Unit = { writes += it }) {
        activity.get().setContent {
            CompositionLocalProvider(
                LocalNavController provides NavHostController(activity.get()),
                LocalSnackbarHost provides snackbar, LocalClaimSnackbarHost provides {},
            ) {
                MaterialTheme {
                    DetailScreen(state, {}, {}, { openedChapter = it }, {}, {}, {}, {}, {},
                        onSetChaptersRead = { ids, targetRead -> readStates += targetRead; save(ids) })
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
    }

    private fun enterSelection() {
        compose.onNodeWithContentDescription(text(R.string.action_more_options)).performClick()
        compose.onNodeWithText(text(if (read) R.string.mark_as_read else R.string.mark_as_unread)).performClick()
    }

    private fun confirm() {
        compose.onNodeWithText(text(if (read) R.string.mark_read_action else R.string.mark_unread_action)).performClick()
        compose.onNodeWithText(text(R.string.confirm)).performClick()
    }

    @Test fun readSelectionSkipsMiddleChaptersAndDoesNotLeakIntoTheUnreadAction() {
        read = true
        show()
        enterSelection()
        compose.onNodeWithText(text(R.string.mark_read_action)).assertIsNotEnabled()
        compose.onNodeWithText("Chapter 0").performClick().assertIsOn()
        compose.onNodeWithText("Chapter 2").performClick().assertIsOn()
        compose.onNodeWithText("Chapter 1").assertIsOff()
        confirm()
        assertEquals(listOf(setOf("chapter-0", "chapter-2")), writes)
        assertEquals(listOf(true), readStates)
        assertNull(openedChapter)

        read = false
        enterSelection()
        compose.onNodeWithText("Chapter 0").assertIsOff()
        compose.onNodeWithText("Chapter 2").assertIsOff()
        compose.onNodeWithText("Chapter 1").performClick()
        confirm()
        assertEquals(listOf(true, false), readStates)
        assertEquals(setOf("chapter-1"), writes.last())
    }

    @Test fun rowsSelectArbitraryChaptersWithoutNavigationAndRestoreTheHideReadPreference() {
        show()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.detail_contents)))
        compose.onNodeWithContentDescription(text(R.string.detail_directory_options)).performClick()
        compose.onNodeWithText(text(R.string.hide_read)).performClick()
        compose.onNodeWithText("Chapter 0").assertDoesNotExist()
        enterSelection()
        compose.onNodeWithText("Chapter 0").performClick().assertIsOn()
        compose.onNodeWithText("Chapter 2").performClick().assertIsOn()
        val toggle = compose.onNodeWithText("Chapter 3").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { toggle(); toggle() }
        compose.onNodeWithText("Chapter 3").assertIsOff()
        assertNull(openedChapter)
        confirm()
        assertEquals(listOf(setOf("chapter-0", "chapter-2")), writes)
        compose.onNodeWithText("Chapter 0").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_options)).performClick()
        compose.onNode(hasText(text(R.string.hide_read)) and hasClickAction()).assertIsSelected()
        assertNull(openedChapter)
    }

    @Test fun cancelAndBackExitWithoutWritingAndSingleSelectionIsSupported() {
        show()
        enterSelection()
        compose.onNodeWithText("Chapter 1").performClick()
        compose.onNodeWithText(text(R.string.mark_unread_action)).performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithText("Chapter 1").assertIsOn()
        compose.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
        assertTrue(writes.isEmpty())
        enterSelection()
        compose.onNodeWithText("Chapter 1").assertIsOff().performClick()
        confirm()
        assertEquals(listOf(setOf("chapter-1")), writes)
    }

    @Test fun selectAllCanBeClearedBeforeApplying() {
        show()
        enterSelection()
        compose.onNodeWithText(text(R.string.mark_unread_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.select_all)).performClick()
        compose.onNodeWithText(text(R.string.deselect_all)).performClick()
        compose.onNodeWithText(text(R.string.mark_unread_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.select_all)).performClick()
        confirm()
        assertEquals(listOf((0..3).mapTo(mutableSetOf()) { "chapter-$it" }), writes)
    }

    @Test fun savingWaitsForPersistenceAndFailureKeepsSelectionForRetry() {
        read = true
        val completed = CompletableDeferred<Unit>()
        var calls = 0
        show { selected ->
            calls++
            completed.await()
            if (calls == 1) error("Write failed")
            writes += selected
        }
        enterSelection()
        compose.onNodeWithText("Chapter 1").performClick()
        compose.onNodeWithText(text(R.string.mark_read_action)).performClick()
        val save = compose.onNodeWithText(text(R.string.confirm)).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { save(); save() }
        compose.onNodeWithText(text(R.string.cancel)).assertIsNotEnabled()
        assertEquals(1, calls)
        assertTrue(writes.isEmpty())
        compose.runOnIdle { completed.complete(Unit) }
        compose.onNodeWithText("Chapter 1").assertIsOn()
        compose.onNodeWithText(text(R.string.mark_read_action)).assertIsEnabled()
        confirm()
        assertEquals(listOf(true, true), readStates)
        assertEquals(listOf(setOf("chapter-1")), writes)
    }
}
