package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.statistics.StatsRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
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
class DetailChapterReadStateTest {
    @Test fun completionUsesTheSavedResultAndRequiresACompleteCatalog() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val key = BookIdentity.book("book").storageKey
        val catalog = setOf("first", "second")
        val repository = mockk<BookRepository>()
        val shelves = mockk<BookshelfRepository>()
        val downloads = mockk<DownloadProgressRepository>()
        val reading = mockk<BookReadingDataRepository>()
        val stats = mockk<StatsRepository>()
        val volumes = BookVolumes(key, listOf(Volume("volume", "Volume", catalog.map { ChapterInformation(it, it) })))
        val directory = MutableStateFlow<Result<BookVolumes, WebRequestError>>(Ok(volumes))
        every { repository.readingAvailability(key) } returns flowOf(BookReadingAvailability(true, false, false, null))
        every { repository.getBookInformationFlow(key, any()) } returns emptyFlow()
        every { repository.getBookVolumesFlow(key, any()) } returns directory
        every { repository.getUserReadingDataFlow(key) } returns emptyFlow()
        every { repository.downloadStatusFlow(key) } returns emptyFlow()
        every { shelves.getBookshelfBookMetadataFlow(key) } returns emptyFlow()
        every { downloads.downloadItemIdList } returns mutableStateListOf()
        coEvery { reading.setChaptersRead(key, any(), catalog, any()) } returns UserReadingData(key, readingProgress = .5f)
        coEvery { stats.markBookFinished(key) } just Runs
        val model = DetailViewModel(repository, shelves, downloads, mockk(), reading, stats)
        val store = ViewModelStore().apply { put("detail", model) }
        try {
            model.init(key)
            withTimeout(5000) { while (model.uiState.bookVolumes == null) delay(10) }
            model.setChaptersRead(setOf("first"), read = true)
            coVerify(exactly = 0) { stats.markBookFinished(any()) }

            coEvery { reading.setChaptersRead(key, any(), catalog, any()) } throws IllegalStateException("Write failed")
            assertThrows(IllegalStateException::class.java) { runBlocking {
                model.setChaptersRead(setOf("second"), read = true)
            } }
            coVerify(exactly = 0) { stats.markBookFinished(any()) }

            coEvery { reading.setChaptersRead(key, any(), catalog, any()) } returns null
            model.setChaptersRead(emptySet(), read = true)
            coVerify(exactly = 0) { stats.markBookFinished(any()) }

            coEvery { reading.setChaptersRead(key, any(), catalog, any()) } returns UserReadingData(key, readingProgress = 1f)
            model.setChaptersRead(setOf("second"), read = true)
            model.setChaptersRead(setOf("first"), read = false)
            coVerify(exactly = 1) { stats.markBookFinished(key) }

            directory.value = Err(WebRequestError("Directory incomplete", "HTTP 502",
                PartialBookVolumesException(volumes, SourceContentException(ContentError.Network, "ruleToc.chapterList", httpStatus = 502))))
            withTimeout(5000) { while (model.uiState.bookVolumes?.isErr != true) delay(10) }
            clearMocks(reading, stats, answers = false)
            assertThrows(IllegalStateException::class.java) { runBlocking {
                model.setChaptersRead(setOf("first"), read = true)
            } }
            coVerify(exactly = 0) { reading.setChaptersRead(any(), any(), any(), any()); stats.markBookFinished(any()) }
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            withTimeout(5000) { job.join() }
            Dispatchers.resetMain()
        }
    }
}
