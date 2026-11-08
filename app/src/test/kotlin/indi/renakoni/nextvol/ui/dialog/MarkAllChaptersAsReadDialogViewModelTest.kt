package indi.renakoni.nextvol.ui.dialog

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.reading.ReaderRecordStore
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.ui.book.reader.ReaderReadingRecords
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class MarkAllChaptersAsReadDialogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val bookRepository = mockk<BookRepository>()
    private val statsRepository = mockk<StatsRepository>()
    private var data = UserReadingData(id = "book")
    private lateinit var model: MarkAllChaptersAsReadDialogViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { bookRepository.updateUserReadingData("book", any()) } coAnswers {
            data = secondArg<(UserReadingData) -> UserReadingData>()(data)
        }
        coEvery { bookRepository.getUserReadingData("book") } coAnswers { data }
        coEvery { statsRepository.markBookFinished("book") } coAnswers {
            assertEquals(1f, data.readingProgress, 0f)
        }
        model = MarkAllChaptersAsReadDialogViewModel(bookRepository, statsRepository)
        store.put("dialog", model)
    }

    @After fun cleanup() {
        store.clear()
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private suspend fun loadChapters(ids: List<String> = listOf("first", "second")) {
        val volumes = BookVolumes("book", listOf(Volume("volume", "Volume", ids.map { ChapterInformation(it, it) })))
        every { bookRepository.getBookVolumesFlow("book", any()) } returns flowOf(Ok(volumes))
        model.load("book")
        awaitUpdates()
    }

    private suspend fun awaitUpdates() {
        model.viewModelScope.coroutineContext.job.children.toList().joinAll()
    }

    @Test fun markingUnreadChaptersUpdatesTheTotalImmediatelyAndFinishesAfterTheLastOne() = runTest(dispatcher) {
        loadChapters()

        model.markChaptersAsRead(listOf("first"))
        awaitUpdates()

        assertEquals(0.5f, data.readingProgress, 0f)
        assertEquals(mapOf("first" to 1f), data.maxChapterReadingProgressMap)
        assertEquals(emptyMap<String, Float>(), data.currentChapterReadingProgressMap)
        coVerify(exactly = 0) { statsRepository.markBookFinished(any()) }

        model.markChaptersAsRead(listOf("second"))
        awaitUpdates()

        assertEquals(1f, data.readingProgress, 0f)
        assertEquals(mapOf("first" to 1f, "second" to 1f), data.maxChapterReadingProgressMap)
        coVerify(exactly = 1) { statsRepository.markBookFinished("book") }
    }

    @Test fun markingARangeUsesHistoricalMaximaAndPreservesResumePositions() = runTest(dispatcher) {
        val previous = UserReadingData("book", readingProgress = 0.5f,
            lastReadChapterId = "second", lastReadChapterTitle = "Second chapter",
            currentChapterReadingProgressMap = mapOf("first" to 0.1f, "second" to 0.2f),
            maxChapterReadingProgressMap = mapOf("first" to 0.25f, "second" to 0.75f))
        data = previous
        loadChapters()

        model.markChaptersAsRead(listOf("first"))
        awaitUpdates()

        assertEquals(0.875f, data.readingProgress, 0f)
        assertEquals(mapOf("first" to 1f, "second" to 0.75f), data.maxChapterReadingProgressMap)
        assertEquals(previous.currentChapterReadingProgressMap, data.currentChapterReadingProgressMap)
        assertEquals(previous.lastReadChapterId, data.lastReadChapterId)
        assertEquals(previous.lastReadChapterTitle, data.lastReadChapterTitle)
        coVerify(exactly = 0) { statsRepository.markBookFinished(any()) }
    }

    @Test fun manualAndReaderWritesUseTheSameCatalogAndHistory() = runTest(dispatcher) {
        loadChapters(listOf("first", "second", "first"))
        val recordStore = mockk<ReaderRecordStore>(relaxed = true)
        coEvery { recordStore.updateChapterProgress("book", any(), any(), any()) } answers {
            data = arg<(UserReadingData) -> UserReadingData>(3)(data)
            true
        }
        val records = ReaderReadingRecords(recordStore, backgroundScope, backgroundScope,
            { "book" }, { "First" }, { setOf("first", "second") }, ioDispatcher = dispatcher)
        val original = UserReadingData("book",
            maxChapterReadingProgressMap = mapOf("second" to 0f, "removed" to 1f))
        data = original
        model.markChaptersAsRead(listOf("first"))
        awaitUpdates()
        val manual = data
        data = original
        records.saveProgress("first", 1f)
        records.awaitProgress()

        assertEquals(.5f, manual.readingProgress, 0f)
        assertEquals(manual.readingProgress, data.readingProgress, 0f)
        assertEquals(manual.maxChapterReadingProgressMap, data.maxChapterReadingProgressMap)
        assertEquals(1f, data.maxChapterReadingProgressMap["removed"])
        coVerify(exactly = 0) { statsRepository.markBookFinished(any()) }
    }

    @Test fun markingAllUsesTheCatalogWithoutChangingResumeOrRemovedHistory() = runTest(dispatcher) {
        loadChapters(listOf("first", "second", "first"))
        val original = UserReadingData("book", lastReadChapterId = "second",
            currentChapterReadingProgressMap = mapOf("second" to .2f),
            maxChapterReadingProgressMap = mapOf("removed" to .7f))
        data = original
        model.markAllChaptersAsRead()
        awaitUpdates()
        assertEquals(1f, data.readingProgress, 0f)
        assertEquals(mapOf("first" to 1f, "second" to 1f, "removed" to .7f), data.maxChapterReadingProgressMap)
        assertEquals(original.currentChapterReadingProgressMap, data.currentChapterReadingProgressMap)
        assertEquals(original.lastReadChapterId, data.lastReadChapterId)
        coVerify(exactly = 1) { statsRepository.markBookFinished("book") }
    }
}
