package indi.renakoni.nextvol.ui.bookmanager

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.github.michaelbull.result.Err
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.download.*
import indi.renakoni.nextvol.data.storage.StorageUsageRepository
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.coVerify
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertSame
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
class DownloadManagerScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val book = SourceBookId(Identifier("fixture", "removed"), "private-book-id")

    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    private fun item(status: BookDownloadStatus) = MutableDownloadItem(DownloadType.CACHE, book.storageKey,
        flowOf(Err(WebRequestError("Cookie: session=secret", "https://private.invalid/book?token=secret"))))
        .apply { this.status = status; progress = -1f }

    private fun show(item: DownloadItem, retry: (DownloadItem) -> Unit = {}, remove: (DownloadItem) -> Unit = {}) {
        val state = MutableLocalBookManagerUiState({}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {})
        activity.get().setContent {
            CompositionLocalProvider(LocalNavController provides NavHostController(activity.get()),
                LocalSnackbarHost provides SnackbarHostState(), LocalClaimSnackbarHost provides {}) {
                MaterialTheme { BookManagerScreen({}, listOf(item), state, remove, retry, {}) }
            }
        }
    }

    @Test fun missingDetailsStillShowASafeTaskCardWithRetryAndRemove() {
        val item = item(BookDownloadStatus(BookDownloadState(BookDownloadPhase.Partial),
            DownloadTaskState(DownloadTaskStatus.Failed, DownloadStage.Details, failure = DownloadFailure.SourceUnavailable)))
        var retried: DownloadItem? = null
        var removed: DownloadItem? = null
        show(item, retry = { retried = it }, remove = { removed = it })
        compose.onNodeWithText(activity.get().getString(R.string.download_task_unknown, book.fileKey.take(8))).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.download_error_source), substring = true).assertIsDisplayed()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText(book.remoteId, substring = true).assertDoesNotExist()
        compose.onNodeWithText(activity.get().getString(R.string.book_download_continue)).performClick()
        compose.onNodeWithContentDescription(activity.get().getString(R.string.download_task_remove)).performClick()
        assertSame(item, retried)
        assertSame(item, removed)
    }

    @Test fun interruptedContentShowsCountsAndActiveTasksCannotBeStartedAgain() {
        val status = BookDownloadStatus(BookDownloadState(BookDownloadPhase.Partial, 1, 3),
            DownloadTaskState(DownloadTaskStatus.Interrupted, DownloadStage.Body, chapterIndex = 2))
        val item = item(status)
        show(item)
        compose.onNodeWithText(activity.get().getString(R.string.download_task_interrupted), substring = true).assertIsDisplayed()
        compose.onNodeWithText(activity.get().getString(R.string.download_task_content, 1, 3), substring = true).assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.download_task_chapter, 2), substring = true).assertExists()
        compose.onNodeWithText(activity.get().getString(R.string.book_download_continue)).assertIsDisplayed()
        compose.runOnIdle {
            item.status = status.copy(task = status.task.copy(status = DownloadTaskStatus.Running))
            item.progress = 1f / 3f
        }
        compose.onNodeWithText(activity.get().getString(R.string.book_download_continue)).assertDoesNotExist()
        compose.onNodeWithContentDescription(activity.get().getString(R.string.download_task_remove)).assertExists()
    }

    @Test fun completedTaskOffersNoUpdateButIncompleteTaskResumesFromTheManager() {
        val repository = mockk<BookRepository>()
        coEvery { repository.submitDownload(any(), any()) } returns
            indi.renakoni.nextvol.data.download.DownloadSubmission.Rejected(indi.renakoni.nextvol.data.download.DownloadFailure.Scheduling)
        val usage = mockk<StorageUsageRepository>()
        coEvery { usage.getCachedSnapshot() } coAnswers { awaitCancellation() }
        val model = BookManagerViewModel(repository, mockk(), mockk(), usage, mockk(), mockk(), mockk())
        try {
            val status = BookDownloadStatus(BookDownloadState(BookDownloadPhase.Complete, 3, 3),
                DownloadTaskState(DownloadTaskStatus.Complete, DownloadStage.Body))
            val item = item(status).apply { progress = 1f }
            show(item, retry = model::onClickRetry)
            compose.onNodeWithText(activity.get().getString(R.string.book_download_continue)).assertDoesNotExist()
            compose.runOnIdle {
                item.status = status.copy(task = status.task.copy(status = DownloadTaskStatus.Failed))
                item.progress = -1f
            }
            compose.onNodeWithText(activity.get().getString(R.string.book_download_continue)).performClick()
            compose.waitForIdle()
            coVerify(exactly = 1) { repository.submitDownload(book.storageKey, null) }
        } finally {
            model.viewModelScope.cancel()
        }
    }
}
