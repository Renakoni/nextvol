package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.statistics.StatsRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
    @Test fun completionFollowsSuccessfulReadWritesAndFailureCanBeRetried() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val key = BookIdentity.book("book").storageKey
        val catalog = setOf("first", "second")
        val repository = mockk<BookRepository>()
        val shelves = mockk<BookshelfRepository>()
        val downloads = mockk<DownloadProgressRepository>()
        val reading = mockk<BookReadingDataRepository>()
        val stats = mockk<StatsRepository>()
        every { repository.readingAvailability(key) } returns flowOf(BookReadingAvailability(true, false, false, null))
        every { repository.getBookInformationFlow(key, any()) } returns emptyFlow()
        every { repository.getBookVolumesFlow(key, any()) } returns flowOf(Ok(BookVolumes(key,
            listOf(Volume("volume", "Volume", catalog.map { ChapterInformation(it, it) })))))
        every { repository.getUserReadingDataFlow(key) } returns emptyFlow()
        every { repository.downloadStatusFlow(key) } returns emptyFlow()
        every { shelves.getBookshelfBookMetadataFlow(key) } returns emptyFlow()
        every { downloads.downloadItemIdList } returns mutableStateListOf()
        var data = UserReadingData(key, readingProgress = .5f)
        var failSave = false
        coEvery { reading.setChaptersRead(key, any(), catalog, any()) } coAnswers {
            if (failSave) error("Write failed")
        }
        coEvery { reading.getUserReadingData(key) } coAnswers { data }
        coEvery { stats.markBookFinished(key) } just Runs
        val model = DetailViewModel(repository, shelves, downloads, mockk(), reading, stats)
        val store = ViewModelStore().apply { put("detail", model) }
        try {
            model.init(key)
            withTimeout(5000) { while (model.uiState.bookVolumes == null) delay(10) }
            model.setChaptersRead(setOf("first"), read = true)
            assertEquals(.5f, model.uiState.userReadingData!!.readingProgress)
            coVerify(exactly = 0) { stats.markBookFinished(any()) }

            data = data.copy(readingProgress = 1f)
            failSave = true
            try {
                model.setChaptersRead(setOf("second"), read = true)
                fail("A failed save must reach the selection UI")
            } catch (_: IllegalStateException) { }
            assertEquals(.5f, model.uiState.userReadingData!!.readingProgress)
            coVerify(exactly = 0) { stats.markBookFinished(any()) }

            failSave = false
            model.setChaptersRead(setOf("second"), read = true)
            assertEquals(1f, model.uiState.userReadingData!!.readingProgress)
            coVerifyOrder {
                reading.setChaptersRead(key, setOf("second"), catalog, true)
                reading.getUserReadingData(key)
                stats.markBookFinished(key)
            }
            model.setChaptersRead(emptySet(), read = true)
            model.setChaptersRead(setOf("missing"), read = true)
            model.setChaptersRead(setOf("first"), read = false)
            coVerify(exactly = 1) { stats.markBookFinished(key) }
        } finally {
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            withTimeout(5000) { job.join() }
            Dispatchers.resetMain()
        }
    }
}
