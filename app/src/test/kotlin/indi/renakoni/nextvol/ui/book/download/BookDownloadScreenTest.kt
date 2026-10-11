package indi.renakoni.nextvol.ui.book.download

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.download.BookDownloadState
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadChapterState
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadSelectionState
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadTaskState
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.After
import org.junit.Assert.assertEquals
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
class BookDownloadScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var state by mutableStateOf(BookDownloadUiState("test-book"))
    private var submitted = 0
    private var reloads = 0
    private var resumed = 0
    private var cancelled = 0

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
        activity.get().setContent { MaterialTheme {
            BookDownloadScreen(state, {}, { reloads++ }, { state = state.copy(selected = it) },
                { submitted++ }, { resumed++ }, { cancelled++ })
        } }
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun directoryLoadingAndFailureCannotSubmitAndFailureOffersReload() {
        compose.onNodeWithText("Select chapters").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, directoryFailure = DownloadFailure.Network) }
        compose.onNodeWithText("Select chapters").assertIsNotEnabled()
        compose.onNodeWithText("Reload directory").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reloads); assertEquals(0, submitted) }
    }

    @Test fun selectionRequiresConfirmationAndUsesOneSelectAllAction() {
        val chapters = (1..3).map { ChapterInformation("chapter-$it", "Chapter $it") }
        compose.runOnIdle { state = state.copy(loading = false, volumes = BookVolumes("test-book", listOf(Volume("v1", "Volume One", chapters)))) }
        compose.onNodeWithText("Select all").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, state.selected.size); assertEquals(0, submitted) }
        compose.onNodeWithText("Select all").assertDoesNotExist()
        compose.onNodeWithText("Deselect all").performClick()
        compose.runOnIdle { assertEquals(0, state.selected.size) }
        compose.onNodeWithTag("download-volume-0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(3, state.selected.size); assertEquals(0, submitted) }
        compose.onNodeWithText("Chapter 1").assertDoesNotExist()
        compose.onNodeWithTag("download-volume-0").performClick()
        compose.onNodeWithTag("download-volume-toggle-0").performClick()
        compose.onNodeWithText("Chapter 2").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("chapter-2"), state.selected); assertEquals(0, submitted) }
        compose.onNodeWithText("Download 1 chapters").performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun foldingVolumesKeepsSelectionsAndOriginalChapterTitles() {
        val first = ChapterInformation("one", "Chapter.0 Original prologue")
        val second = ChapterInformation("two", "Chapter.1 A different title")
        compose.runOnIdle { state = state.copy(loading = false, selected = setOf(first.id),
            volumes = BookVolumes("test-book", listOf(Volume("v1", "Volume One", listOf(first)),
                Volume("v2", "Volume Two", listOf(second))))) }
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithTag("download-volume-toggle-0").performScrollTo().performClick()
        compose.onNodeWithText(first.title).assertIsDisplayed().assertIsOn()
        compose.onNodeWithTag("download-volume-toggle-1").performScrollTo().performClick()
        compose.onNodeWithText(first.title).assertDoesNotExist()
        compose.onNodeWithText(second.title).performScrollTo().performClick()
        compose.onNodeWithTag("download-volume-toggle-1").performScrollTo().performClick()
        compose.onNodeWithText(second.title).assertDoesNotExist()
        compose.onNodeWithTag("download-volume-toggle-0").performScrollTo().performClick()
        compose.onNodeWithText(first.title).assertIsDisplayed().assertIsOn()
        compose.runOnIdle { assertEquals(setOf(first.id, second.id), state.selected); assertEquals(0, submitted) }
    }

    @Test fun downloadedChaptersAreLockedAndOnlyTheRestCanBeSelected() {
        val chapters = (1..3).map { ChapterInformation("chapter-$it", "Chapter $it") }
        compose.runOnIdle { state = state.copy(loading = false,
            volumes = BookVolumes("test-book", listOf(Volume("v1", "Volume One", chapters))),
            chapters = DownloadSelectionState(mapOf("chapter-1" to DownloadChapterState(downloaded = true)))) }
        compose.onNodeWithText("Select all").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf("chapter-2", "chapter-3"), state.selected) }
        compose.onNodeWithText("Download 2 chapters").assertIsEnabled()
        compose.onNodeWithTag("download-volume-toggle-0").performScrollTo().performClick()
        compose.onNode(hasText("Chapter 1") and hasContentDescription("Downloaded")).performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState))
            .assertHasNoClickAction()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Chapter 2"))
        compose.onNodeWithText("Chapter 2").assertIsOn()
        compose.runOnIdle { state = state.copy(selected = emptySet(), chapters = DownloadSelectionState(
            chapters.associate { it.id to DownloadChapterState(downloaded = true) })) }
        // Nothing is left to choose: no checkboxes, no select-all and no download button.
        compose.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        compose.onNodeWithTag("download-volume-toggle-0").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Select all").assertDoesNotExist()
        compose.onNodeWithText("Select chapters").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submitted) }
    }

    @Test fun summaryShowsOneTaskLineWithTheActionThatFitsIt() {
        val chapters = (1..6).map { ChapterInformation("chapter-$it", "Chapter $it") }
        compose.runOnIdle { state = state.copy(loading = false, volumes = BookVolumes("test-book", listOf(Volume("v1", "Volume One", chapters))),
            status = BookDownloadStatus(BookDownloadState(BookDownloadPhase.Partial, 2, 6, taskSavedChapters = 2, taskTotalChapters = 4),
                DownloadTaskState(DownloadTaskStatus.Running, DownloadStage.Body))) }
        compose.onNodeWithText("Downloading 2 of 4 chapters").assertIsDisplayed()
        compose.onNodeWithText("Select chapters").assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
        compose.runOnIdle { state = state.copy(status = state.status.copy(
            task = DownloadTaskState(DownloadTaskStatus.Failed, DownloadStage.Body, failure = DownloadFailure.Network))) }
        compose.onNodeWithText(activity.get().getString(R.string.book_download_failed)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.download_error_network)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.cancel)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.book_download_retry)).performClick()
        compose.runOnIdle { assertEquals(1, resumed); assertEquals(0, submitted) }
        compose.onNodeWithText("Select chapters").assertIsDisplayed()
    }
}
