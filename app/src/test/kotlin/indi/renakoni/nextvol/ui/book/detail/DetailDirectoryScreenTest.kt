package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toPixelMap
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
import org.robolectric.annotation.GraphicsMode
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

    private fun assertDirectoryAtTop() {
        val header = compose.onNodeWithText(text(R.string.detail_contents)).fetchSemanticsNode().boundsInRoot
        val content = list().fetchSemanticsNode().boundsInRoot
        assertTrue("Search must keep the directory at the top", header.top - content.top <= header.height)
    }

    @Test
    @Config(sdk = [35])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun collapsedTopBarHasNoProgressTrackAtAnyReadingProgress() {
        show(dark = true)
        toolbar()
        for (progress in listOf(0f, .55f, 1f)) {
            compose.runOnIdle {
                state.userReadingData = state.userReadingData!!.copy(readingProgress = progress)
            }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            val bottom = list().fetchSemanticsNode().boundsInRoot.top.toInt()
            val background = pixels[1, bottom - 12]
            for (x in listOf(1, pixels.width / 4, pixels.width / 2, pixels.width - 2)) {
                assertEquals("The collapsed toolbar must not draw a progress track at $progress",
                    background, pixels[x, bottom - 2])
            }
            compose.onNodeWithContentDescription(text(R.string.action_more_options)).assertIsDisplayed()
        }
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

    @Test fun oneHundredChaptersNeedNoNavigationAndLocateStillFindsTheCurrentChapter() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 100))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-52")
        show()
        toolbar()
        compose.onNodeWithText("Volume a").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_volumes)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_next)).assertDoesNotExist()
        options()
        compose.onNodeWithText(text(R.string.detail_directory_locate)).performClick()
        compose.onNodeWithText("Chapter a-52").assertIsDisplayed()
        assertNull(opened)
        assertReadActionDoesNotCoverEnd("Chapter a-100")
    }

    @Test fun oneHundredAndOneChaptersKeepNavigationOnTheShortLastPage() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 101))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-101")
        show()
        toolbar()
        compose.onNodeWithText("Volume a").assertIsDisplayed().assertHasClickAction()
        compose.onAllNodesWithText("101–101")[0].assertIsDisplayed()
        compose.onNodeWithText("Chapter a-101").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(text(R.string.detail_directory_previous))[0]
            .assertIsEnabled().performClick()
        compose.onAllNodesWithText("1–100")[0].assertIsDisplayed()
        compose.onNodeWithText("Chapter a-1").assertIsDisplayed()
    }

    @Test fun multipleShortVolumesKeepInlineFoldingWithoutVolumeSelectionOrPaging() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 18), volume("b", 20))))
        show()
        toolbar()
        compose.onNodeWithText("Volume a").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_volumes)).assertDoesNotExist()
        list().performScrollToNode(hasText("Volume b"))
        compose.onNodeWithText("Volume b").assertIsDisplayed().assertHasClickAction()
        list().performScrollToNode(hasText("Chapter b-1"))
        compose.onNodeWithText("Chapter b-1").assertIsDisplayed()
        compose.onNodeWithText("Volume b").performClick()
        compose.onNodeWithText("Chapter b-1").assertDoesNotExist()
        compose.onNodeWithText("Volume b").performClick()
        list().performScrollToNode(hasText("Chapter b-1"))
        compose.onNodeWithText("Chapter b-1").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_next)).assertDoesNotExist()
    }

    @Test fun oneExplicitVolumeKeepsFoldingAndCountsIllustrationsAndExtras() {
        val chapters = listOf("序章", "第一章", "插图", "特典").mapIndexed { index, title ->
            ChapterInformation("entry-$index", title)
        }
        state.bookVolumes = Ok(BookVolumes("book", listOf(Volume("first", "第一卷", chapters))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "entry-3",
            maxChapterReadingProgressMap = mapOf("entry-0" to 1f, "entry-2" to 1f))
        show()
        toolbar()
        compose.onNodeWithTag("directory-volume:first").assertHasClickAction()
        compose.onNodeWithText(activity.get().getString(R.string.info_reading_progress, 2, 4)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).assertDoesNotExist()
        compose.onNodeWithTag("directory-volume:first").performClick()
        compose.onNodeWithText("插图").assertDoesNotExist()
        compose.onNodeWithText("特典").assertDoesNotExist()
        compose.onNodeWithTag("directory-volume:first").performClick()
        compose.onNodeWithText("插图").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ascending)).performClick()
        val extra = compose.onNodeWithText("特典").fetchSemanticsNode().boundsInRoot
        val illustration = compose.onNodeWithText("插图").fetchSemanticsNode().boundsInRoot
        assertTrue(extra.top < illustration.top)
        compose.onNodeWithText(activity.get().getString(R.string.info_reading_progress, 2, 4)).assertIsDisplayed()
        compose.onNodeWithText("特典").assertIsDisplayed().performClick()
        assertEquals("entry-3", opened)
    }

    @Test fun anUntitledContainerDoesNotInventAVolumeHeader() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 4).copy(volumeTitle = ""))))
        show()
        toolbar()
        compose.onNodeWithTag("directory-volume:a").assertDoesNotExist()
        compose.onNodeWithText("Chapter a-1").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).assertDoesNotExist()
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

    @Test fun locateExpandsACollapsedVolumeAcrossGlobalPages() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 205))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-152")
        show()
        toolbar()
        compose.onNodeWithTag("directory-volume:a").performClick()
        chooseRange("1–100")
        compose.onNodeWithText("Chapter a-1").assertDoesNotExist()
        options()
        compose.onNodeWithText(text(R.string.detail_directory_locate)).performClick()
        compose.onNodeWithText("Chapter a-152").assertIsDisplayed()
        assertNull(opened)
    }

    @Test fun collapsedUnreadChaptersDoNotBecomeAnEmptyFilteredRange() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 5))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-3",
            maxChapterReadingProgressMap = mapOf("a-1" to 1f, "a-2" to 1f))
        show()
        toolbar()
        compose.onNodeWithTag("directory-volume:a").performClick()
        options()
        compose.onNodeWithText(text(R.string.hide_read)).performClick()
        compose.onNodeWithText(text(R.string.detail_directory_filtered_empty)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.info_reading_progress, 2, 5)).assertIsDisplayed()
        compose.onNodeWithTag("directory-volume:a").performClick()
        compose.onNodeWithText("Chapter a-1").assertDoesNotExist()
        compose.onNodeWithText("Chapter a-3").assertIsDisplayed()
    }

    @Test fun globalDirectoryKeepsSourceTitlesAndOpensTheMatchingChapterAcrossVolumes() {
        show()
        for (starts in listOf(listOf(1, 1, 1), listOf(1, 19, 224))) {
            val volumes = listOf("a" to 18, "b" to 205, "c" to 18).mapIndexed { index, (id, count) ->
                val source = volume(id, count)
                source.copy(chapters = source.chapters.mapIndexed { chapterIndex, chapter ->
                    chapter.copy(title = "Source chapter ${starts[index] + chapterIndex}")
                })
            }
            compose.runOnIdle { state.bookVolumes = Ok(BookVolumes("book", volumes)) }
            var offset = 0
            for (volume in volumes) {
                chooseRange(if (offset < 100) "1–100" else "201–241")
                val first = volume.chapters.first()
                list().performScrollToKey("chapter:${volume.volumeId.length}:${volume.volumeId}:${first.id}")
                compose.onNodeWithText(first.title).assertIsDisplayed().performClick()
                assertEquals("The opened chapter must belong to ${volume.volumeId}", first.id, opened)
                offset += volume.chapters.size
            }
        }
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

    @Test fun descendingStartsWithAFullPageFromTheEndWithoutChangingCanonicalOrder() {
        val volumes = listOf(volume("a", 18), volume("b", 205))
        state.bookVolumes = Ok(BookVolumes("book", volumes))
        show()
        toolbar()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ascending)).assertIsOff().performClick()
        compose.onNodeWithText("223–124").assertIsDisplayed()
        list().performScrollToNode(hasText("Chapter b-205"))
        compose.onNodeWithText("Chapter b-205").performClick()
        assertEquals("b-205", opened)
        assertEquals("b-1", volumes.last().chapters.first().id)
        chooseRange("23–1")
        compose.onNodeWithText("Chapter b-5").assertIsDisplayed()
        list().performScrollToNode(hasText("Chapter a-18"))
        compose.onNodeWithText("Chapter a-18").assertIsDisplayed()
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

    @Test fun searchKeepsTheDirectoryAtTheTopWhenResultsReplaceLoading() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 205))))
        show()
        toolbar()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Chapter")
        compose.waitUntil(5000) { compose.onAllNodesWithText("Chapter a-1").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertDirectoryAtTop()
        assertReadActionDoesNotCoverEnd("Chapter a-205")
    }

    @Test fun sparseAndEmptySearchResultsKeepTheSearchControlsAtTheTop() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 205))))
        show()
        toolbar()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_search)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Chapter a-205")
        val match = hasText("Chapter a-205") and !hasSetTextAction()
        compose.waitUntil(5000) { compose.onAllNodes(match).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        assertDirectoryAtTop()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("No matching chapter")
        compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.reader_directory_no_results)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertDirectoryAtTop()
        assertReadActionDoesNotCoverEnd(text(R.string.reader_directory_no_results_hint))
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

    @Test fun sortingStaysDirectWhileSecondaryOptionsKeepTheirState() {
        show()
        toolbar()
        compose.onNodeWithText(text(R.string.hide_read)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ascending)).assertIsDisplayed().assertIsOff()
        compose.onNodeWithText(text(R.string.detail_directory_locate)).assertDoesNotExist()
        options()
        compose.onNodeWithText(text(R.string.hide_read)).assertIsNotSelected().performClick()
        compose.onNode(hasText(text(R.string.hide_read), substring = true)).assertIsDisplayed()
        options()
        compose.onNodeWithText(text(R.string.hide_read)).assertIsSelected().performClick()
        compose.onNode(hasText(text(R.string.hide_read), substring = true)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ascending)).performClick()
        compose.onNodeWithText("Chapter a-18").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_descending)).assertIsOn().performClick()
        compose.onNodeWithText("Chapter a-1").assertIsDisplayed()
    }

    @Test fun longDirectoryUsesAnAnchoredRangeMenuWithoutVolumeNavigation() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 205), volume("b", 20))))
        show()
        toolbar()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_previous)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_next)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_volumes)).assertDoesNotExist()
        compose.onNodeWithText("Volume a").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("1–100").assertIsDisplayed()
        compose.onNodeWithText("Chapter a-1").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.detail_directory_ranges)).performClick()
        val popup = compose.onNode(isPopup()).assertExists().fetchSemanticsNode().boundsInRoot
        val content = list().fetchSemanticsNode().boundsInRoot
        assertTrue("Range selection must stay a compact anchored menu, not a full-width sheet",
            popup.width < content.width && popup.height < content.height / 2)
        compose.onNodeWithText(text(R.string.detail_directory_ranges)).assertDoesNotExist()
        compose.onNode(hasText("1–100") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
        compose.onNodeWithText("201–225").performClick()
        compose.onNodeWithText("Chapter a-201").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "en-rUS-w320dp-h900dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowDarkLayoutAndLargeTypeKeepActionsVisibleAndSeparate() {
        val volumeTitle = "A long light novel volume title that stays readable without becoming a separate selector"
        state.bookVolumes = Ok(BookVolumes("book", listOf(
            volume("a", 205).copy(volumeTitle = volumeTitle), volume("b", 20),
        )))
        show(fontScale = 1.6f, dark = true)
        toolbar()
        val order = compose.onNodeWithContentDescription(text(R.string.detail_directory_ascending))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithContentDescription(text(R.string.detail_directory_search))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription(text(R.string.detail_directory_options))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse("Direct actions must stay separate at large font sizes", order.overlaps(search) || search.overlaps(more))
        val title = compose.onNodeWithText(volumeTitle, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val range = compose.onNodeWithText("1–100").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Long volume labels must stay below navigation, not compete with its controls", title.top >= range.bottom)
        chooseRange("201–225")
        compose.onNodeWithText("Chapter a-201").assertIsDisplayed()
        assertReadActionDoesNotCoverEnd("201–225")
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

    @Test fun longRangeMenuShowsCurrentSelectionAndCanScrollBackToTheStart() {
        state.bookVolumes = Ok(BookVolumes("book", listOf(volume("a", 1001))))
        state.userReadingData = UserReadingData("book", lastReadChapterId = "a-1001")
        show()
        toolbar()
        compose.onAllNodesWithContentDescription(text(R.string.detail_directory_ranges))[0].performClick()
        compose.onNode(hasText("1001–1001") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .assertIsSelected().assertIsDisplayed()
        compose.onNode(hasText("1–100") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .performScrollTo().performClick()
        compose.onNodeWithText("Chapter a-1").assertIsDisplayed()
    }
}
