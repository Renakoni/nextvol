package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavHostController
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.map
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.PartialBookVolumesException
import indi.renakoni.nextvol.data.book.UNKNOWN_BOOK_UPDATE_TIME
import indi.renakoni.nextvol.data.download.BookDownloadState
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.download.MutableDownloadItem
import indi.renakoni.nextvol.data.download.DownloadType
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.dateFormatter
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.ui.LocalNavController
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
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MetadataDetailScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }
    private fun readingState(count: Int, updated: LocalDateTime) = MutableDetailUiState().apply {
        val key = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("rules", "metadata"), "book").storageKey
        bookInformation = Ok(BookInformation(key, "Book", author = "Author", description = "Description",
            publishingHouse = "", wordCount = WordCount(count), lastUpdated = updated, isComplete = false))
        readingAvailable = true
        bookVolumes = Ok(BookVolumes(key, emptyList()))
        userReadingData = io.nightfish.lightnovelreader.api.book.UserReadingData(key)
    }

    private fun show(state: MutableDetailUiState, retry: () -> Unit = {}, bookmark: (String) -> Unit = {}, cache: (String) -> Unit = {},
        chapter: (String) -> Unit = {}) {
        activity.get().setContent {
            CompositionLocalProvider(LocalNavController provides NavHostController(activity.get()),
                LocalSnackbarHost provides SnackbarHostState(), LocalClaimSnackbarHost provides {}) {
                MaterialTheme { DetailScreen(state, {}, {}, chapter, {}, cache, bookmark, {}, {}, {}, retry) }
            }
        }
    }

    @Test fun missingWordCountAndUpdateDateAreHiddenWithoutHidingDirectoryStatistics() {
        show(readingState(0, UNKNOWN_BOOK_UPDATE_TIME))
        val zeroWords = activity.get().getString(R.string.book_info_word_count_kilo, "0")
        val missingDate = activity.get().getString(R.string.book_info_update_date, UNKNOWN_BOOK_UPDATE_TIME.format(dateFormatter()))
        compose.onNodeWithText(zeroWords).assertDoesNotExist()
        compose.onNodeWithText(missingDate).assertDoesNotExist()
        val info = activity.get().getString(R.string.action_show_info)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(info))
        compose.onNodeWithText(info).performClick()
        compose.onNodeWithText(activity.get().getString(R.string.detail_info_updated_on)).assertDoesNotExist()
        compose.onAllNodes(hasText(zeroWords, substring = true)).assertCountEquals(0)
        compose.onNodeWithText(activity.get().getString(R.string.detail_info_stats_count_content, 0, 0)).assertExists()
    }

    @Test fun knownMetadataRemainsVisibleAndStatisticsDoNotRepeatTheWordUnit() {
        val updated = LocalDateTime.of(2026, 9, 14, 0, 0)
        show(readingState(123, updated))
        val words = activity.get().getString(R.string.book_info_word_count_kilo, "123")
        compose.onNodeWithText(words).assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.book_info_update_date, updated.format(dateFormatter()))).assertExists()
        val info = activity.get().getString(R.string.action_show_info)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(info))
        compose.onNodeWithText(info).performClick()
        compose.onNodeWithText(activity.get().getString(R.string.detail_info_updated_on)).assertExists()
        val statistics = activity.get().getString(R.string.detail_info_stats_count_content, 0, 0)
        compose.onNodeWithText(words + "\n" + statistics).assertExists()
    }

    @Test fun wordCountAndUpdateDateAreHiddenIndependently() {
        val state = readingState(123, UNKNOWN_BOOK_UPDATE_TIME)
        show(state)
        val words = activity.get().getString(R.string.book_info_word_count_kilo, "123")
        val missingDate = activity.get().getString(R.string.book_info_update_date, UNKNOWN_BOOK_UPDATE_TIME.format(dateFormatter()))
        compose.onNodeWithText(words).assertExists()
        compose.onNodeWithText(missingDate).assertDoesNotExist()
        val updated = LocalDateTime.of(2026, 9, 14, 0, 0)
        compose.runOnIdle {
            state.bookInformation = state.bookInformation?.map { it.copy(wordCount = WordCount(0), lastUpdated = updated) }
        }
        compose.onNodeWithText(words).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.book_info_update_date, updated.format(dateFormatter()))).assertExists()
    }

    @Test fun metadataShowsEditionAndBasicInfoWithoutReadingDirectoryCacheOrExport() {
        val key = SourceBookId(ZLibrarySources.ID, "17/abcdef").storageKey
        val book = BookInformation(key, "Metadata book", subtitle = "Chinese · EPUB · 2020", author = "Author",
            description = "A real book description", publishingHouse = "Publisher", wordCount = WordCount(0),
            lastUpdated = LocalDateTime.of(1970, 1, 1, 0, 0), isComplete = false)
        val state = MutableDetailUiState().apply { bookInformation = Ok(book); metadataOnly = true }
        var saved: String? = null
        show(state, bookmark = { saved = it })
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(book.subtitle).assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(activity.get().getString(R.string.source_metadata_only)))
        compose.onNodeWithText(activity.get().getString(R.string.source_metadata_only)).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.detail_contents)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.start_reading)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.cached_false)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Export").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(activity.get().getString(R.string.add_to_bookshelf)))
        compose.onNodeWithText(activity.get().getString(R.string.add_to_bookshelf)).performClick()
        assertEquals(key, saved)
        compose.onNodeWithText(activity.get().getString(R.string.action_show_info)).performClick()
        compose.onNodeWithText("17/abcdef").assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.detail_info_stats)).assertDoesNotExist()
    }

    @Test fun failedMetadataHasAnExplicitRetryAction() {
        val state = MutableDetailUiState().apply { bookInformation = Err(WebRequestError("Z-Library", "Invalid response")) }
        var retries = 0
        show(state, retry = { retries++ })
        compose.onNodeWithText("Invalid response").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun failedDirectoryIsVisibleAndCanRetryInsteadOfOfferingAnInertReadAction() {
        val key = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "a"), "book").storageKey
        val state = MutableDetailUiState().apply {
            bookInformation = Ok(BookInformation(key, "Book", author = "Author", description = "Description",
                publishingHouse = "", wordCount = WordCount(1), lastUpdated = LocalDateTime.of(2026, 9, 15, 0, 0), isComplete = false))
            readingAvailable = true
            userReadingData = io.nightfish.lightnovelreader.api.book.UserReadingData(key)
            bookVolumes = Err(WebRequestError("Directory failed", "EmptyContent: ruleToc.chapterList"))
        }
        var retries = 0
        show(state, retry = { retries++ })
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("EmptyContent: ruleToc.chapterList"))
        compose.onNodeWithText("EmptyContent: ruleToc.chapterList").assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.start_reading)).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.discovery_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun partialDirectoryShowsWarningAndReadableChaptersUntilRetrySucceeds() {
        val state = readingState(0, UNKNOWN_BOOK_UPDATE_TIME)
        val key = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("rules", "metadata"), "book").storageKey
        val partial = BookVolumes(key, listOf(Volume("volume", "Volume", listOf(ChapterInformation("one", "Chapter one")))))
        state.bookVolumes = Err(WebRequestError("Directory failed", "HTTP 502",
            PartialBookVolumesException(partial, SourceContentException(ContentError.Network, "ruleToc.chapterList", httpStatus = 502))))
        var retries = 0
        var selected = ""
        show(state, retry = {
            retries++
            state.bookVolumes = Ok(partial.copy(volumes = listOf(partial.volumes.single().copy(
                chapters = partial.volumes.single().chapters + ChapterInformation("two", "Chapter two")))))
        }, chapter = { selected = it })
        compose.mainClock.advanceTimeBy(500)
        val warning = activity.get().getString(R.string.book_directory_incomplete)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(warning))
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter one"))
        compose.onNodeWithText("Chapter one").assertIsDisplayed().performClick()
        assertEquals("one", selected)
        assertTrue(state.bookVolumes!!.isErr)
        val retry = activity.get().getString(R.string.discovery_retry)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(retry))
        compose.onNodeWithText(retry).performClick()
        assertEquals(1, retries)
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Chapter two"))
        compose.onNodeWithText("Chapter two").assertIsDisplayed()
    }

    @Test fun completedDownloadsOnlyOpenTheDownloadPageAndFailuresCanRetryWithoutNegativeProgress() {
        val key = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "a"), "book").storageKey
        val state = MutableDetailUiState().apply {
            bookInformation = Ok(BookInformation(key, "Book", author = "Author", description = "",
                publishingHouse = "", wordCount = WordCount(1), lastUpdated = LocalDateTime.of(2026, 9, 15, 0, 0), isComplete = false))
            canCache = true
            downloadState = BookDownloadStatus(BookDownloadState(BookDownloadPhase.Complete, 3, 3))
        }
        var requests = 0
        show(state, cache = { assertEquals(key, it); requests++ })
        val downloaded = activity.get().getString(R.string.cached)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(downloaded))
        compose.onNodeWithText(downloaded).assertIsEnabled().performClick()
        assertEquals(1, requests)
        compose.runOnIdle {
            state.downloadState = BookDownloadStatus(BookDownloadState(BookDownloadPhase.Updating, 1, 3))
            state.downloadItem = MutableDownloadItem(DownloadType.CACHE, key, kotlinx.coroutines.flow.emptyFlow()).apply { progress = 0.5f }
        }
        compose.onNodeWithText(activity.get().getString(R.string.book_download_updating)).assertIsNotEnabled()
        compose.onNodeWithText("50%").assertExists()
        compose.runOnIdle {
            state.downloadState = BookDownloadStatus(BookDownloadState(BookDownloadPhase.Failed, 1, 3))
            (state.downloadItem as MutableDownloadItem).progress = -1f
        }
        compose.onNodeWithText(activity.get().getString(R.string.book_download_retry)).assertIsEnabled().performClick()
        compose.onNodeWithText("-100%").assertDoesNotExist()
        assertEquals(2, requests)
        compose.runOnIdle {
            state.downloadState = BookDownloadStatus(BookDownloadState(BookDownloadPhase.Complete, 3, 3))
            state.canCache = false
        }
        compose.onNodeWithText(downloaded).assertIsNotEnabled()
    }
}
