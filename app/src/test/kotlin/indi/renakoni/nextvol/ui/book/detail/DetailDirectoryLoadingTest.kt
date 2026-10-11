package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.github.michaelbull.result.*
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DetailDirectoryLoadingTest {
    @Test fun coldDetailsPublishInformationBeforeStartingDirectoryAndCanCancelOrRetryIt() = checkLoading(false)
    @Test fun informationFailureStillAllowsDirectoryLoading() = checkLoading(true)

    private fun checkLoading(failInformation: Boolean) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val key = SourceBookId(Identifier("rules", "directory"), "book").storageKey
        val information = BookInformation(key, "Book", author = "Author", description = "", publishingHouse = "",
            wordCount = WordCount(0), lastUpdated = UNKNOWN_BOOK_UPDATE_TIME, isComplete = false)
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val repository = mockk<BookRepository>()
        val shelves = mockk<BookshelfRepository>(relaxed = true)
        val downloads = mockk<DownloadProgressRepository>()
        every { repository.readingAvailability(key) } returns flowOf(BookReadingAvailability(true, true, false, null))
        every { repository.getBookInformationFlow(key, any()) } returns flow {
            release.await()
            emit(if (failInformation) Err(WebRequestError("Information", "Unavailable")) else Ok(information))
        }
        coEvery { repository.bookInformationForDisplay(any()) } returns information
        every { repository.getBookVolumesFlow(key, any()) } returns flow {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        every { repository.getUserReadingDataFlow(key) } returns emptyFlow()
        every { repository.downloadStatusFlow(key) } returns emptyFlow()
        coEvery { shelves.getBookshelfBookMetadata(key) } returns null
        every { shelves.getBookshelfBookMetadataFlow(key) } returns flowOf(null)
        every { downloads.downloadItemIdList } returns mutableStateListOf()
        val model = DetailViewModel(repository, shelves, downloads, mockk<WorkManager>(relaxed = true), mockk())
        val store = ViewModelStore().apply { put("detail", model) }
        try {
            model.init(key)
            withTimeout(5000) { while (!model.uiState.readingAvailable) delay(10) }
            verify(exactly = 0) { repository.getBookVolumesFlow(key, any()) }
            release.complete(Unit)
            withTimeout(5000) { started.await() }
            assertEquals(failInformation, model.uiState.bookInformation!!.isErr)
            assertNull(model.uiState.bookVolumes)
            model.retryVolumes()
            withTimeout(5000) { cancelled.await() }
            verify(timeout = 5000, exactly = 2) { repository.getBookVolumesFlow(key, any()) }
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            withTimeout(5000) { job.join() }
            Dispatchers.resetMain()
        }
    }
}
