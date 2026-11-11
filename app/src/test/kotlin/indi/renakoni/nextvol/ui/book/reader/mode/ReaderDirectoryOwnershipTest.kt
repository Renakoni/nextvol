package indi.renakoni.nextvol.ui.book.reader.mode

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.ReaderViewModel
import indi.renakoni.nextvol.ui.book.reader.content.ContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeFactory
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import indi.renakoni.nextvol.data.book.ChapterSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderDirectoryOwnershipTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()
    private val readerJobs = mutableListOf<Job>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        try {
            store.clear()
            // Clearing the owner requests cancellation; its IO children must also finish.
            await { readerJobs.all { it.isCompleted } }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun replacingBookCancelsThePreviousDirectoryCollection() {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val first = MutableSharedFlow<Result<BookVolumes, WebRequestError>>(extraBufferCapacity = 8)
        val second = MutableSharedFlow<Result<BookVolumes, WebRequestError>>(extraBufferCapacity = 8)
        val chapters = mockk<ChapterSource> {
            every { getBookVolumesFlow("first", any()) } returns flow {
                firstStarted.complete(Unit)
                try {
                    emitAll(first)
                } finally {
                    firstCancelled.complete(Unit)
                }
            }
            every { getBookVolumesFlow("second", any()) } returns flow {
                secondStarted.complete(Unit)
                emitAll(second)
            }
        }
        val dao = mockk<UserDataDao> {
            every { getFlow(any()) } returns kotlinx.coroutines.flow.flowOf(null)
            every { getFlow(UserDataPath.Reader.IsUsingFlipPage.path) } returns kotlinx.coroutines.flow.flowOf(null)
        }
        coEvery { dao.get(UserDataPath.ReadingBooks.path) } returns null
        coEvery { dao.insert(UserDataPath.ReadingBooks.path, "", "StringList", any()) } just Runs
        val controller = object : ReaderModeController {
            override val uiState: ContentUiState = mockk { every { readingChapterId } returns "" }
            override fun changeBookId(id: String) = Unit
            override fun changeChapter(id: String) = Unit
            override fun loadNextChapter() = Unit
            override fun loadPrevChapter() = Unit
        }
        val factory = mockk<ReaderModeFactory> {
            every { create(any(), any(), any(), any()) } returns controller
        }
        val reader = ReaderViewModel(
            statsRepository = mockk(relaxed = true),
            chapterSource = chapters,
            readingData = mockk(relaxed = true),
            userDataRepository = UserDataRepository(dao),
            modeFactory = factory,
            readAloud = mockk(),
        )
        store.put("reader", reader)
        readerJobs += reader.viewModelScope.coroutineContext[Job]!!

        reader.bookId = "first"
        await { firstStarted.isCompleted }
        reader.bookId = "second"
        await { secondStarted.isCompleted }
        await { firstCancelled.isCompleted }

        second.tryEmit(Ok(BookVolumes("second", emptyList())))
        await { reader.uiState.bookVolumes?.get()?.bookId == "second" }
        first.tryEmit(Ok(BookVolumes("first", emptyList())))
        scheduler.runCurrent()

        assertEquals("second", reader.uiState.bookVolumes?.get()?.bookId)
        coVerify(timeout = 2_000, exactly = 2) {
            dao.insert(UserDataPath.ReadingBooks.path, "", "StringList", any())
        }
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(2_000) {
            while (!condition()) {
                scheduler.runCurrent()
                delay(5)
            }
        }
    }

    @Test
    fun nonCooperativeOldDirectoryCannotPublishAfterTheNewBook() =
        assertNonCooperativeDirectoryCannotPublish(clearOwner = false)

    @Test
    fun nonCooperativeDirectoryCannotPublishAfterTheOwnerIsCleared() =
        assertNonCooperativeDirectoryCannotPublish(clearOwner = true)

    private fun assertNonCooperativeDirectoryCannotPublish(clearOwner: Boolean) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val staleFlow = object : Flow<Result<BookVolumes, WebRequestError>> {
            override suspend fun collect(collector: FlowCollector<Result<BookVolumes, WebRequestError>>) {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    collector.emit(Ok(BookVolumes("first", emptyList())))
                    finished.complete(Unit)
                }
            }
        }
        val chapters = mockk<ChapterSource> {
            every { getBookVolumesFlow("first", any()) } returns staleFlow
            every { getBookVolumesFlow("second", any()) } returns kotlinx.coroutines.flow.flowOf(Ok(BookVolumes("second", emptyList())))
        }
        val dao = mockk<UserDataDao>(relaxed = true) {
            every { getFlow(any()) } returns kotlinx.coroutines.flow.flowOf(null)
        }
        // A missing preference is null; relaxed String mocks return an invalid blank book ID.
        coEvery { dao.get(UserDataPath.ReadingBooks.path) } returns null
        coEvery { dao.insert(UserDataPath.ReadingBooks.path, "", "StringList", any()) } just Runs
        val factory = mockk<ReaderModeFactory> {
            every { create(any(), any(), any(), any()) } returns mockk(relaxed = true)
        }
        val reader = ReaderViewModel(mockk(relaxed = true), chapters, mockk(relaxed = true), UserDataRepository(dao), factory, mockk())
        store.put("reader", reader)
        readerJobs += reader.viewModelScope.coroutineContext[Job]!!
        try {
            reader.bookId = "first"
            await { started.isCompleted }
            if (clearOwner) {
                store.clear()
            } else {
                reader.bookId = "second"
                await { reader.uiState.bookVolumes?.get()?.bookId == "second" }
            }
            release.complete(Unit)
            await { finished.isCompleted }
            if (clearOwner) assertNull(reader.uiState.bookVolumes)
            else assertEquals("second", reader.uiState.bookVolumes?.get()?.bookId)
        } finally {
            release.complete(Unit)
        }
    }
}
