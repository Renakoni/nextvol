package indi.renakoni.nextvol.ui.dialog

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.statistics.StatsRepository
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

    private suspend fun loadChapters() {
        val ids = listOf("first", "second")
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
}
