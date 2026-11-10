package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.get
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadTaskState
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DetailCapabilitiesTest {
    @Test fun detailAcknowledgesObservedUpdatesButNeverMissingDates() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val key = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("rules", "metadata"), "book").storageKey
        val book = BookInformation(key, "Book", author = "Author", description = "", publishingHouse = "",
            wordCount = WordCount(12300), lastUpdated = UNKNOWN_BOOK_UPDATE_TIME, isComplete = false)
        val information = MutableStateFlow<Result<BookInformation, io.nightfish.lightnovelreader.api.error.WebRequestError>>(Ok(book))
        val sourceDate = LocalDateTime.of(2020, 1, 1, 0, 0)
        val repository = mockk<BookRepository>()
        val shelves = mockk<BookshelfRepository>(relaxed = true)
        val downloads = mockk<DownloadProgressRepository>()
        val work = mockk<WorkManager>(relaxed = true)
        every { repository.getBookInformationFlow(key, any()) } returns information
        coEvery { repository.bookInformationForDisplay(any()) } coAnswers {
            firstArg<BookInformation>().let { if (it.lastUpdated.year > 1970) it.copy(lastUpdated = sourceDate) else it }
        }
        every { repository.readingAvailability(key) } returns flowOf(BookReadingAvailability(false, false, false, null))
        every { repository.getUserReadingDataFlow(key) } returns emptyFlow()
        every { repository.downloadChanges(key) } returns emptyFlow()
        every { repository.downloadStatusFlow(key) } returns emptyFlow()
        every { work.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        coEvery { shelves.getBookshelfBookMetadata(key) } returns mockk { every { bookShelfIds } returns listOf(1) }
        every { shelves.getBookshelfBookMetadataFlow(key) } returns flowOf(null)
        every { downloads.downloadItemIdList } returns mutableStateListOf()
        val model = DetailViewModel(repository, shelves, downloads, work, mockk(), mockk())
        val store = ViewModelStore().apply { put("detail", model) }
        suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
        try {
            model.init(key)
            until { model.uiState.bookInformation != null }
            coVerify(exactly = 0) { shelves.updateBookshelfBookMetadataLastUpdateTime(any(), any()) }
            val observed = LocalDateTime.of(2026, 9, 28, 12, 0)
            information.value = Ok(book.copy(lastUpdated = observed))
            until { model.uiState.bookInformation?.get()?.lastUpdated == sourceDate }
            coVerify(exactly = 1) { shelves.updateBookshelfBookMetadataLastUpdateTime(key, observed) }
            coVerify(exactly = 0) { shelves.updateBookshelfBookMetadataLastUpdateTime(key, sourceDate) }
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            withTimeout(5000) { job.join() }
            Dispatchers.resetMain()
        }
    }

    @Test fun metadataDetailsNeverRequestDirectoriesOrEnqueueReadingWorkAndOfflineDirectoriesRemainUsable() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val key = SourceBookId(ZLibrarySources.ID, "1/abcdef").storageKey
        val book = BookInformation(key, "Metadata book", author = "Author", description = "Description",
            publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.of(1970, 1, 1, 0, 0), isComplete = false)
        val availability = MutableStateFlow(BookReadingAvailability(false, false, true, 1))
        val repository = mockk<BookRepository>()
        val shelves = mockk<BookshelfRepository>()
        val downloads = mockk<DownloadProgressRepository>()
        val work = mockk<WorkManager>(relaxed = true)
        every { repository.readingAvailability(key) } returns availability
        every { repository.getBookInformationFlow(key, any()) } returns flowOf(Ok(book))
        coEvery { repository.bookInformationForDisplay(book) } returns book
        every { repository.getUserReadingDataFlow(key) } returns emptyFlow()
        every { repository.getBookVolumesFlow(key, any()) } returns flowOf(Ok(BookVolumes(key, emptyList())))
        val downloadStatus = BookDownloadStatus(task = DownloadTaskState(DownloadTaskStatus.Interrupted))
        every { repository.downloadStatusFlow(key) } returns flowOf(downloadStatus)
        every { work.getWorkInfosForUniqueWorkFlow(any()) } returns flowOf(emptyList())
        coEvery { shelves.getBookshelfBookMetadata(key) } returns null
        every { shelves.getBookshelfBookMetadataFlow(key) } returns flowOf(null)
        every { downloads.downloadItemIdList } returns mutableStateListOf()
        val model = DetailViewModel(repository, shelves, downloads, work, mockk(), mockk())
        val store = ViewModelStore().apply { put("detail", model) }
        suspend fun until(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }
        try {
            model.init(key)
            until { model.uiState.bookInformation != null && model.uiState.metadataOnly && model.uiState.downloadState == downloadStatus }
            assertFalse(model.uiState.readingAvailable)
            assertNull(model.uiState.bookVolumes)
            assertFalse(model.uiState.canCache)
            assertNull(model.exportToEpub(key, "Metadata book").first())
            verify(exactly = 0) { repository.getBookVolumesFlow(any<String>(), any()) }
            verify(exactly = 0) { repository.cacheBook(any()) }
            coVerify(exactly = 0) { repository.submitDownload(any(), any()) }
            verify(exactly = 0) { work.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) }
            model.retryInformation()
            until { model.uiState.bookInformation != null }
            verify(exactly = 2) { repository.getBookInformationFlow(key, any()) }
            availability.value = BookReadingAvailability(false, true, false, null)
            until { model.uiState.bookVolumes != null }
            assertTrue(model.uiState.readingAvailable)
            assertFalse(model.uiState.canCache)
            verify(exactly = 1) { repository.getBookVolumesFlow(key, any()) }

            val failure = io.nightfish.lightnovelreader.api.error.WebRequestError("Directory", "No chapters")
            every { repository.getBookVolumesFlow(key, any()) } returns flowOf(Err(failure))
            model.retryVolumes()
            until { model.uiState.bookVolumes?.getError() == failure }
            val cancelled = CompletableDeferred<Unit>()
            every { repository.getBookVolumesFlow(key, any()) } returns flow {
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            model.retryVolumes()
            until { model.uiState.bookVolumes == null }
            verify(timeout = 5000, exactly = 3) { repository.getBookVolumesFlow(key, any()) }
            availability.value = BookReadingAvailability(false, false, true, 2)
            withTimeout(5000) { cancelled.await() }
            until { !model.uiState.readingAvailable }
            model.retryVolumes()
            assertNull(model.uiState.bookVolumes)
            verify(exactly = 3) { repository.getBookVolumesFlow(key, any()) }
            verify(exactly = 2) { repository.getBookInformationFlow(key, any()) }
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            withTimeout(5000) { job.join() }
            Dispatchers.resetMain()
        }
    }
}
