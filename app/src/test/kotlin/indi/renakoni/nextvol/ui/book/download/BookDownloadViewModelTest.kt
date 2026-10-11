package indi.renakoni.nextvol.ui.book.download

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadChapterState
import indi.renakoni.nextvol.data.download.DownloadSelectionState
import indi.renakoni.nextvol.data.download.DownloadSubmission
import indi.renakoni.nextvol.data.download.DownloadTaskState
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookDownloadViewModelTest {
    @Test fun lateQueuedAcknowledgementDoesNotReplaceObservedCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val book = SourceBookId(Identifier("test", "download"), "book")
        val statuses = MutableStateFlow(BookDownloadStatus())
        val reply = CompletableDeferred<DownloadSubmission>()
        val books = mockk<BookRepository>()
        every { books.downloadInformationFlow(book.storageKey) } returns emptyFlow()
        every { books.downloadStatusFlow(book.storageKey) } returns statuses
        coEvery { books.downloadDirectory(book) } returns Ok(BookVolumes(book.storageKey,
            listOf(Volume("volume", "Volume", listOf(ChapterInformation("1", "One"))))))
        coEvery { books.downloadSelection(book.storageKey, any()) } returns DownloadSelectionState()
        coEvery { books.submitDownload(book.storageKey, listOf("1")) } coAnswers { reply.await() }
        val model = BookDownloadViewModel(SavedStateHandle(mapOf("bookId" to book.storageKey)), books)
        try {
            runCurrent()
            assertTrue(model.state.ready)
            model.submit()
            runCurrent()
            assertTrue(model.state.submitting)
            val complete = BookDownloadStatus(task = DownloadTaskState(DownloadTaskStatus.Complete))
            statuses.value = complete
            runCurrent()
            reply.complete(DownloadSubmission.Accepted(UUID.randomUUID(), DownloadTaskState(DownloadTaskStatus.Queued)))
            runCurrent()
            assertEquals(complete, model.state.status)
            assertFalse(model.state.locked)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun downloadedChaptersAreNeverSelected() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val book = SourceBookId(Identifier("test", "download"), "book")
        val books = mockk<BookRepository>()
        every { books.downloadInformationFlow(book.storageKey) } returns emptyFlow()
        every { books.downloadStatusFlow(book.storageKey) } returns MutableStateFlow(BookDownloadStatus())
        coEvery { books.downloadDirectory(book) } returns Ok(BookVolumes(book.storageKey, listOf(Volume("volume", "Volume",
            listOf(ChapterInformation("1", "One"), ChapterInformation("2", "Two"), ChapterInformation("3", "Three"))))))
        coEvery { books.downloadSelection(book.storageKey, any()) } returns
            DownloadSelectionState(mapOf("1" to DownloadChapterState(downloaded = true)))
        val model = BookDownloadViewModel(SavedStateHandle(mapOf("bookId" to book.storageKey)), books)
        try {
            runCurrent()
            assertEquals(setOf("2", "3"), model.state.selected)
            model.select(setOf("1", "2"))
            assertEquals(setOf("2"), model.state.selected)
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun lateTaskActionsAreIgnoredOnceTheTaskHasEnded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val book = SourceBookId(Identifier("test", "download"), "book")
        val books = mockk<BookRepository>()
        every { books.downloadInformationFlow(book.storageKey) } returns emptyFlow()
        every { books.downloadStatusFlow(book.storageKey) } returns
            MutableStateFlow(BookDownloadStatus(task = DownloadTaskState(DownloadTaskStatus.Complete)))
        coEvery { books.downloadDirectory(book) } returns Ok(BookVolumes(book.storageKey,
            listOf(Volume("volume", "Volume", listOf(ChapterInformation("1", "One"))))))
        coEvery { books.downloadSelection(book.storageKey, any()) } returns DownloadSelectionState()
        val model = BookDownloadViewModel(SavedStateHandle(mapOf("bookId" to book.storageKey)), books)
        try {
            runCurrent()
            model.cancel()
            model.submit(resume = true)
            runCurrent()
            assertFalse(model.state.submitting)
            coVerify(exactly = 0) { books.dismissDownload(any()) }
            coVerify(exactly = 0) { books.submitDownload(any(), any()) }
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
