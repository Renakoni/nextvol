package indi.renakoni.nextvol.ui.home.reading

import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.home.reading.home.ReadingHomeViewModel
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingHomeViewModelTest {
    private val store = ViewModelStore()
    private val books = mockk<BookRepository>()
    private val history = MutableStateFlow(listOf("book"))
    private val progress = MutableStateFlow(UserReadingData("book"))
    private val replies = Channel<Result<BookInformation, WebRequestError>>(Channel.UNLIMITED)
    private val starts = AtomicInteger()
    private val repliesTaken = AtomicInteger()
    private val activeProgress = AtomicInteger()
    private val information = mockk<BookInformation>()
    private lateinit var model: ReadingHomeViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val users = mockk<UserDataRepository> {
            every { stringListUserData(UserDataPath.ReadingBooks.path) } returns mockk {
                every { getFlowWithDefault(any()) } returns history
            }
        }
        every { books.getBookInformationFlow(any<String>(), any()) } answers {
            flow { starts.incrementAndGet(); emit(replies.receive()); repliesTaken.incrementAndGet() }
        }
        every { books.getUserReadingDataFlow(any()) } answers {
            progress.onStart { activeProgress.incrementAndGet() }.onCompletion { activeProgress.decrementAndGet() }
        }
        model = ReadingHomeViewModel(books, users)
        store.put("reading", model)
    }

    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test fun headerAndHistoryShareResultsAndReturningRefreshesWithoutClearingContent() = runBlocking {
        val state = model.recentReadingBooks.single().second
        val header = launch { state.collect() }
        val row = launch { state.collect() }
        replies.send(Ok(information))
        await { state.value?.isOk == true && activeProgress.get() == 1 }
        assertEquals(1, starts.get())
        val displayed = state.value
        header.cancel(); row.cancel()
        await { activeProgress.get() == 0 }
        assertEquals(displayed, state.value)

        val returned = launch { state.collect() }
        await { starts.get() == 2 }
        assertEquals(displayed, state.value)
        progress.value = progress.value.copy(readingProgress = 0.5f)
        await { state.value?.get()?.userReadingData?.readingProgress == 0.5f }
        replies.send(Err(WebRequestError("offline", "retry later")))
        await { repliesTaken.get() == 2 }
        assertSame(information, state.value?.get()?.bookInformation)
        returned.cancel()
        await { activeProgress.get() == 0 }
        val refreshed = mockk<BookInformation>()
        val again = launch { state.collect() }
        replies.send(Ok(refreshed))
        await { state.value?.get()?.bookInformation === refreshed }
        assertEquals(3, starts.get())
        again.cancel()
    }

    @Test fun historyChangesRetainSurvivorsAndRemoveTheirObserversOnDeletion() = runBlocking {
        val original = model.recentReadingBooks.single().second
        val collector = launch { original.collect() }
        replies.send(Ok(information))
        await { activeProgress.get() == 1 }
        history.value = listOf("book", "other", "book", "")
        assertEquals(listOf("book", "other"), model.recentReadingBooks.map { it.first })
        assertSame(original, model.recentReadingBooks.first().second)
        assertEquals(1, starts.get())

        history.value = listOf("other")
        await { activeProgress.get() == 0 }
        history.value = listOf("other", "book")
        val replacement = model.recentReadingBooks.first().second
        assertNotSame(original, replacement)
        assertNull(replacement.value)
        val replacementCollector = launch { replacement.collect() }
        await { activeProgress.get() == 1 }
        store.clear()
        await { activeProgress.get() == 0 }
        collector.cancel(); replacementCollector.cancel()
    }
}
