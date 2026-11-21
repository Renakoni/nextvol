package indi.renakoni.nextvol.ui.dialog

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Err
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.statistics.StatsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfBookMetadata
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class AddToBookshelfDialogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val bookshelfRepository = mockk<BookshelfRepository>()
    private val bookRepository = mockk<BookRepository>()
    private val statsRepository = mockk<StatsRepository>()
    private val navController = mockk<NavController>()
    private val bookId = BookIdentity.bookKey("fixture-book")
    private val information = BookInformation(bookId, "Fixture", author = "Author", description = "",
        publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.of(2026, 1, 1, 0, 0), isComplete = false)
    private val shelves = listOf(Bookshelf(id = 1, name = "First"), Bookshelf(id = 2, name = "Second"))
    private var metadata: BookshelfBookMetadata? = null
    private lateinit var model: AddToBookshelfDialogViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        coEvery { bookshelfRepository.getAllBookshelfIds() } returns shelves.map { it.id }
        shelves.forEach { shelf -> coEvery { bookshelfRepository.getBookshelf(shelf.id) } returns shelf }
        coEvery { bookshelfRepository.getBookshelfBookMetadata(any()) } coAnswers {
            BookIdentity.book(firstArg())
            metadata
        }
        coEvery { bookshelfRepository.addBookIntoBookShelf(any(), any()) } coAnswers { persistShelf(firstArg()) }
        coEvery { bookshelfRepository.deleteBookFromBookshelf(any(), any()) } coAnswers {
            val removed = firstArg<Int>()
            metadata = metadata?.let { old ->
                old.copy(bookShelfIds = old.bookShelfIds.filterNot { it == removed }).takeIf { it.bookShelfIds.isNotEmpty() }
            }
        }
        coEvery { statsRepository.markBookFavorited(any()) } returns Unit
        every { bookRepository.getBookInformationFlow(bookId, any()) } returns flowOf(Ok(information))
        every { navController.popBackStack() } returns true
        model = AddToBookshelfDialogViewModel(bookshelfRepository, bookRepository, statsRepository)
        model.navController = navController
        store.put("dialog", model)
    }

    @After fun cleanup() {
        store.clear()
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private suspend fun awaitInitialization() {
        while (true) {
            val jobs = model.viewModelScope.coroutineContext.job.children.toList()
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    private fun persistShelf(id: Int) {
        metadata = BookshelfBookMetadata(bookId, information.lastUpdated, (metadata?.bookShelfIds.orEmpty() + id).distinct())
    }

    @Test fun savingBlocksDismissalSelectionChangesAndDuplicateSubmission() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { bookshelfRepository.addBookIntoBookShelf(1, information) } coAnswers {
            started.complete(Unit); release.await(); persistShelf(1)
        }
        model.bookId = bookId
        awaitInitialization()
        model.onSelectBookshelf(1)
        model.processAddToBookshelfRequest()
        started.await()
        try {
            model.onSelectBookshelf(2)
            model.onDeselectBookshelf(1)
            model.onDismissAddToBookshelfRequest()
            model.processAddToBookshelfRequest()
            assertTrue(model.addToBookshelfDialogUiState.isSaving)
            assertEquals(listOf(1), model.addToBookshelfDialogUiState.selectedBookshelfIds.toList())
            verify(exactly = 0) { navController.popBackStack() }
        } finally { release.complete(Unit) }
        awaitInitialization()
        assertFalse(model.addToBookshelfDialogUiState.isSaving)
        coVerify(exactly = 1) { bookshelfRepository.addBookIntoBookShelf(1, information) }
        verify(exactly = 1) { navController.popBackStack() }
    }

    @Test fun bookInformationFailureDoesNotRemoveTheOldFavorite() = runTest(dispatcher) {
        metadata = BookshelfBookMetadata(bookId, information.lastUpdated, listOf(1))
        every { bookRepository.getBookInformationFlow(bookId, any()) } returns flowOf(Err(WebRequestError("Failure", "Fixture")))
        model.bookId = bookId
        awaitInitialization()
        model.onDeselectBookshelf(1)
        model.onSelectBookshelf(2)
        model.processAddToBookshelfRequest()
        awaitInitialization()
        assertEquals(R.string.save_failed, model.addToBookshelfDialogUiState.errorMessage)
        assertEquals(listOf(1), metadata!!.bookShelfIds)
        coVerify(exactly = 0) { bookshelfRepository.deleteBookFromBookshelf(any(), any()) }
        verify(exactly = 0) { navController.popBackStack() }
    }

    @Test fun statisticsFailureRetriesWithoutAddingTheBookAgain() = runTest(dispatcher) {
        coEvery { statsRepository.markBookFavorited(bookId) } throws IOException("Stats failed")
        model.bookId = bookId
        awaitInitialization()
        model.onSelectBookshelf(1)
        model.processAddToBookshelfRequest()
        awaitInitialization()
        assertEquals(R.string.save_failed, model.addToBookshelfDialogUiState.errorMessage)
        verify(exactly = 0) { navController.popBackStack() }
        coEvery { statsRepository.markBookFavorited(bookId) } returns Unit
        model.processAddToBookshelfRequest()
        awaitInitialization()
        coVerify(exactly = 1) { bookshelfRepository.addBookIntoBookShelf(1, information) }
        coVerify(exactly = 2) { statsRepository.markBookFavorited(bookId) }
        verify(exactly = 1) { navController.popBackStack() }
    }

    @Test fun partialSaveRetainsTheOriginalShelfAndRetriesOnlyRemainingAdditions() = runTest(dispatcher) {
        coEvery { bookshelfRepository.getAllBookshelfIds() } returns listOf(1, 2, 3)
        coEvery { bookshelfRepository.getBookshelf(3) } returns Bookshelf(id = 3, name = "Third")
        coEvery { bookshelfRepository.addBookIntoBookShelf(3, information) } throws IOException("Third shelf failed")
        metadata = BookshelfBookMetadata(bookId, information.lastUpdated, listOf(1))
        model.bookId = bookId
        awaitInitialization()
        model.onDeselectBookshelf(1)
        model.onSelectBookshelf(2)
        model.onSelectBookshelf(3)
        model.processAddToBookshelfRequest()
        awaitInitialization()
        assertEquals(listOf(1, 2), metadata!!.bookShelfIds)
        coVerify(exactly = 0) { bookshelfRepository.deleteBookFromBookshelf(any(), any()) }
        verify(exactly = 0) { navController.popBackStack() }
        coEvery { bookshelfRepository.addBookIntoBookShelf(3, information) } coAnswers { persistShelf(3) }
        model.processAddToBookshelfRequest()
        awaitInitialization()
        assertEquals(listOf(2, 3), metadata!!.bookShelfIds)
        coVerify(exactly = 1) { bookshelfRepository.addBookIntoBookShelf(2, information) }
        coVerify(exactly = 0) { statsRepository.markBookFavorited(any()) }
        verify(exactly = 1) { navController.popBackStack() }
    }

}
