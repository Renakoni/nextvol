package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.ChapterSource
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDirectoryProgressTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))
    @After fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun progressUsesTheExistingDirectoryCollectionWithoutWaitingForIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val collectionCount = AtomicInteger()
        val directory = Channel<Pair<BookVolumes, CompletableDeferred<Unit>>>()
        val chapters = mockk<ChapterSource> {
            every { getBookVolumesFlow("book", any()) } returns flow {
                collectionCount.incrementAndGet()
                started.complete(Unit)
                for ((volumes, applied) in directory) {
                    emit(Ok(volumes))
                    applied.complete(Unit)
                }
            }
        }
        val data = AtomicReference(UserReadingData("book", readingProgress = 0.4f))
        val writes = Channel<UserReadingData>(Channel.UNLIMITED)
        val readingData = mockk<BookReadingDataAccess>(relaxed = true)
        coEvery { readingData.updateChapterProgress("book", any(), any(), any()) } coAnswers {
            val updated = arg<(UserReadingData) -> UserReadingData>(3)(data.get())
            data.set(updated)
            writes.send(updated)
            true
        }
        coEvery { readingData.getUserReadingData("book") } answers { data.get() }
        val saveProgress = slot<(String, Float) -> Unit>()
        val contentState = object : ContentUiState by mockk(relaxed = true) {
            override val bookId = "book"
            override val readingChapterId = "chapter"
            override val readingChapterContent: Result<ChapterContentUiState, WebRequestError>? =
                Ok(ChapterContentUiState("chapter", "Title", emptyList(), null, null))
        }
        val controller = mockk<ReaderModeController>(relaxed = true) {
            every { uiState } returns contentState
        }
        val factory = mockk<ReaderModeFactory> {
            every { create(any(), any(), any(), capture(saveProgress)) } returns controller
        }
        val dao = mockk<UserDataDao>(relaxed = true) {
            every { getFlow(any()) } returns flowOf(null)
        }
        coEvery { dao.get(any()) } returns null
        val reader = ReaderViewModel(mockk(relaxed = true), chapters, readingData, UserDataRepository(dao), factory, mockk())
        store.put("reader", reader)
        scheduler.runCurrent()
        withTimeout(5_000) {
            reader.bookId = "book"
            started.await()

            saveProgress.captured("chapter", 0.5f)
            assertEquals(0.4f, writes.receive().readingProgress)
            for ((count, expected) in listOf(0 to 0f, 2 to 0.25f, 4 to 0.125f)) {
                val applied = CompletableDeferred<Unit>()
                val ids = List(count) { if (it == 0) "chapter" else "$it" }
                directory.send(BookVolumes("book", listOf(Volume("volume", "Title",
                    ids.map { ChapterInformation(it, it) }))) to applied)
                while (!applied.isCompleted) {
                    scheduler.runCurrent()
                    delay(1)
                }
                saveProgress.captured("chapter", 0.5f)
                assertEquals(expected, writes.receive().readingProgress)
            }
            assertEquals(1, collectionCount.get())
            verify(exactly = 1) { chapters.getBookVolumesFlow("book", any()) }
        }
    }

}
