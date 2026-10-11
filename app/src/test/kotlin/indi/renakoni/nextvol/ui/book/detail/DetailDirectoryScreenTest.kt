package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.PartialBookVolumesException
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
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
class DetailDirectoryScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var opened: String? = null
    private var resumed = false
    private val writes = mutableListOf<Set<String>>()
    private val state = MutableDetailUiState().apply {
        readingAvailable = true
        bookInformation = Ok(BookInformation("book", "Book title", author = "Author", description = "",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.of(2026, 9, 29, 0, 0), isComplete = false))
        bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 18))))
        userReadingData = UserReadingData("book", lastReadChapterId = "a-2")
    }

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }

    @After fun teardown() { activity.pause().stop().destroy() }

    private fun volume(id: String, count: Int) = Volume(id, "Volume $id",
        (1..count).map { ChapterInformation("$id-$it", "Chapter $id-$it") })
    private fun text(id: Int) = activity.get().getString(id)
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun readAction() = compose.onNode(
        hasClickAction() and hasAnyDescendant(hasText(text(R.string.continue_reading))),
        useUnmergedTree = true,
    )
    private fun toolbar() { list().performScrollToNode(hasText(text(R.string.detail_contents))) }
    private fun options() {
        toolbar()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_options)).performClick()
    }
    private fun chooseRange(range: String) {
        toolbar()
        compose.onAllNodesWithContentDescription(text(R.string.detail_directory_ranges))[0].performClick()
        compose.onNode(hasText(range) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .performScrollTo().performClick()
    }
    private fun show(fontScale: Float = 1f, dark: Boolean = false) {
        activity.get().setContent {
            CompositionLocalProvider(
                LocalNavController provides NavHostController(activity.get()),
                LocalSnackbarHost provides SnackbarHostState(), LocalClaimSnackbarHost provides {},
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), typography = AppTypography) {
                    DetailScreen(state, {}, {}, { opened = it }, { resumed = true }, {}, {}, {}, {}, {},
                        onMarkChaptersUnread = { writes += it })
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
    }

    private fun assertReadActionDoesNotCoverEnd(lastText: String) {
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        readAction().assertDoesNotExist()
        val content = list().fetchSemanticsNode().boundsInRoot
        val endNode = if (compose.onAllNodesWithTag("directory-pagination").fetchSemanticsNodes().isNotEmpty())
            compose.onNode(hasText(lastText) and hasAnyAncestor(hasTestTag("directory-pagination")))
        else compose.onNodeWithText(lastText)
        val end = endNode.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The directory end must remain fully visible when the original floating action hides",
            end.bottom <= content.bottom)
    }

    @Test fun shortVolumesStayUnpaginatedAndTheOriginalFloatingActionHidesAtTheEnd() {
        show()
        val action = readAction().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val content = list().fetchSemanticsNode().boundsInRoot
        assertTrue("The reader action should float at the right without a full-width bottom bar",
            action.width < content.width && action.left > content.left && action.bottom <= content.bottom)
        toolbar()
        compose.onNodeWithText("Volume a").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_volumes)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_next)).assertDoesNotExist()
        assertReadActionDoesNotCoverEnd("Chapter a-18")
        list().performScrollToNode(hasText("Chapter a-18"))
        compose.onNodeWithText("Chapter a-18").assertIsDisplayed().performClick()
        assertEquals("a-18", opened)
        list().performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(1000)
        readAction().assertIsDisplayed().performClick()
        assertTrue(resumed)
    }

    @Test fun foldingSurvivesGlobalPagesAndDescendingReversesBothVolumesAndEntries() {
        val volumes = listOf(volume("a", 98), volume("b", 105), volume("c", 4))
        state.bookVolumes = Ok(BookVolumes("book", volumes))
        show()
        chooseRange("201–207")
        compose.onNodeWithTag("directory-volume:b").assertIsDisplayed().performClick()
        compose.onNodeWithText("Chapter b-103").assertDoesNotExist()
        chooseRange("1–100")
        list().performScrollToNode(hasTestTag("directory-volume:b"))
        compose.onNodeWithTag("directory-volume:b").assertIsDisplayed()
        compose.onNodeWithText("Chapter b-1").assertDoesNotExist()
        val progress = activity.get().getString(R.string.info_reading_progress, 0, 105)
        val range = activity.get().getString(R.string.detail_directory_volume_page_range, 99, 100)
        compose.onNodeWithText("$progress · $range").assertIsDisplayed()
        toolbar()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ascending)).performClick()
        compose.onAllNodesWithText("207–108")[0].assertIsDisplayed()
        val last = compose.onNodeWithText("Chapter c-4").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithText("Chapter c-3").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(last.top < previous.top)
        compose.onNodeWithText("Chapter b-105").assertDoesNotExist()
        list().performScrollToNode(hasTestTag("directory-volume:b"))
        val descendingRange = activity.get().getString(R.string.detail_directory_volume_page_range, 108, 203)
        compose.onNodeWithText("$progress · $descendingRange").assertIsDisplayed()
        compose.onNodeWithTag("directory-volume:b").performClick()
        compose.onNodeWithText("Chapter b-105").assertIsDisplayed().performClick()
        assertEquals("b-105", opened)
        assertEquals("b-1", volumes[1].chapters.first().id)
        assertEquals("c-1", volumes.last().chapters.first().id)
    }

    @Test fun rangesJumpDirectlyAndLocateReturnsToTheCurrentChapterWithoutOpeningIt() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 305))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-152")
        show()
        toolbar()
        compose.onNodeWithText("101–200").assertExists()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).performClick()
        compose.onNodeWithText("301–305").performClick()
        list().performScrollToNode(hasText("Chapter a-305"))
        compose.onNodeWithText("Chapter a-305").assertIsDisplayed()
        assertReadActionDoesNotCoverEnd("301–305")
        options()
        compose.onNodeWithText(text(R.string.detail_directory_locate)).performClick()
        compose.onNodeWithText("Chapter a-152").assertIsDisplayed()
        assertNull(opened)
        assertReadActionDoesNotCoverEnd("101–200")
    }

    @Test fun searchFindsTitlesAcrossVolumesAndOutsideTheDisplayedRange() {
        val first = volume("a", 150).let { it.copy(chapters = it.chapters.dropLast(1) + it.chapters.last().copy(title = "Needle")) }
        val second = volume("b", 12).let { it.copy(chapters = it.chapters.dropLast(1) + it.chapters.last().copy(title = "Needle")) }
        state.bookVolumes = Ok(BookVolumes("book", listOf(first, second)))
        show()
        toolbar()
        compose.onNodeWithTag("directory-volume:a").performClick()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Needle")
        val matches = hasText("Needle") and !hasSetTextAction()
        compose.waitUntil(5000) { compose.onAllNodes(matches).fetchSemanticsNodes().size == 2 }
        compose.onAllNodes(matches)[1].performClick()
        assertEquals("b-12", opened)
        compose.onNode(hasSetTextAction()).performTextClearance()
        list().performScrollToNode(hasText("Volume a"))
        compose.onNodeWithText("Volume a").assertExists()
    }

    @Test fun selectionSurvivesPageChangesAndSavesOriginalChapterIds() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 205))))
        show()
        compose.onNodeWithContentDescription(text(R.string.action_more_options)).performClick()
        compose.onNodeWithText(text(R.string.mark_as_unread)).performClick()
        readAction().assertDoesNotExist()
        compose.onNodeWithText("Chapter a-1").performClick()
        chooseRange("101–200")
        compose.onNodeWithText("Chapter a-101").performClick()
        chooseRange("1–100")
        compose.onNodeWithText("Chapter a-1").assertIsOn()
        compose.onNodeWithText(text(R.string.mark_unread_action)).performClick()
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        assertEquals(listOf(setOf("a-1", "a-101")), writes)
        assertNull(opened)
    }

    @Test fun hidingReadChaptersKeepsRangesStableAndLocateCanRevealAHiddenCurrentChapter() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 205))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-2",
            maxChapterReadingProgressMap = (1..100).associate { "a-$it" to 1f })
        show()
        options()
        compose.onNodeWithText(text(R.string.hide_read)).performClick()
        compose.onAllNodesWithText("1–100")[0].assertExists()
        compose.onNodeWithText(text(R.string.detail_directory_filtered_empty)).assertExists()
        compose.onAllNodesWithContentDescription(text(R.string.detail_directory_next))[0].performClick()
        list().performScrollToNode(hasText("Chapter a-101"))
        compose.onNodeWithText("Chapter a-101").assertIsDisplayed()
        options()
        compose.onNodeWithText(text(R.string.detail_directory_locate)).performClick()
        compose.onNodeWithText("Chapter a-2").assertIsDisplayed()
    }

    @Test fun ninetyEightChapterBoundaryAndLocateUseGlobalPositions() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 98), volume("b", 105), volume("c", 4))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "b-3")
        show()
        toolbar()
        compose.onNodeWithText("101–200").assertIsDisplayed()
        compose.onNodeWithText("Volume b").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Chapter b-3").assertIsDisplayed().performClick()
        assertEquals("b-3", opened)
        chooseRange("1–100")
        list().performScrollToKey("chapter:1:a:a-98")
        compose.onNodeWithText("Chapter a-98").assertIsDisplayed()
        compose.onNodeWithText("Volume b").assertIsDisplayed()
        compose.onNodeWithText("Chapter b-1").assertIsDisplayed().performClick()
        assertEquals("b-1", opened)
        compose.onNodeWithText("Chapter b-2").assertIsDisplayed()
        compose.onNodeWithText("Chapter b-3").assertDoesNotExist()
        chooseRange("201–207")
        compose.onNodeWithText("Chapter b-103").assertIsDisplayed()
        list().performScrollToKey("chapter:1:c:c-1")
        compose.onNodeWithText("Chapter c-1").assertIsDisplayed().performClick()
        assertEquals("c-1", opened)
        options()
        compose.onNodeWithText(text(R.string.detail_directory_locate)).performClick()
        compose.onNodeWithText("Chapter b-3").assertIsDisplayed()
        assertEquals("Locating must not open another chapter", "c-1", opened)
    }

    @Test fun partialDirectoryKeepsPaginationSearchAndExactCurrentChapterLocation() {
        val partial = BookVolumes("book", listOf(volume("a", 205)))
        state.bookVolumes = Err(WebRequestError("Directory failed", "HTTP 502",
            PartialBookVolumesException(partial, SourceContentException(ContentError.Network, "ruleToc.chapterList", httpStatus = 502))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-150")
        show()
        toolbar()
        compose.onNodeWithText("101–200").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.book_directory_incomplete)).assertIsDisplayed()
        chooseRange("1–100")
        compose.onNodeWithTag("directory-volume:a").performClick()
        options()
        compose.onNodeWithText(text(R.string.detail_directory_locate)).performClick()
        compose.onNodeWithText("Chapter a-150").assertIsDisplayed()
        compose.onNodeWithText("Chapter a-149").assertIsNotDisplayed()
        assertNull("Locating must not open a chapter", opened)
        toolbar()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Chapter a-205")
        val match = hasText("Chapter a-205") and !hasSetTextAction()
        compose.waitUntil(5000) { compose.onAllNodes(match).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(text(R.string.book_directory_incomplete)).assertIsDisplayed()
        compose.onNode(match).assertIsDisplayed().performClick()
        assertEquals("a-205", opened)
    }

}
