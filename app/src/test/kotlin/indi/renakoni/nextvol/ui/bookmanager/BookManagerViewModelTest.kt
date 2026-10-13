package indi.renakoni.nextvol.ui.bookmanager

import android.app.Application
import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.storage.BookStorageUsage
import indi.renakoni.nextvol.data.storage.StorageBook
import indi.renakoni.nextvol.data.storage.StorageBookQuery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BookManagerViewModelTest {
    private fun book(id: String, bytes: Long) = StorageBook(BookStorageUsage(id, bytes, 0, 0, 0), 0, 0, null, 0, 0)

    @Test fun cachedBooksAppearBeforeRefreshAndSelectionAndSortSurviveLaterLoads() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fresh = CompletableDeferred<List<StorageBook>>()
        val refreshing = CompletableDeferred<Unit>()
        val query = mockk<StorageBookQuery>()
        coEvery { query.getCachedBooks() } returns listOf(book("kept", 1), book("gone", 2))
        coEvery { query.refreshBooks() } coAnswers {
            refreshing.complete(Unit)
            fresh.await()
        }
        val repository = mockk<BookRepository>()
        every { repository.getBookInformationFlow(any<String>()) } returns emptyFlow()
        val model = BookManagerViewModel(repository, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), query)
        try {
            withTimeout(5000) { refreshing.await() }
            assertEquals(listOf("kept", "gone"), model.localBookManagerUiState.bookList.map { it.id })
            assertTrue(model.localBookManagerUiState.isLoading)
            model.selectAllLocalBooks()
            model.setLocalBookSort(LocalBookSort.ChapterCount)
            model.setLocalBookReverse(true)
            fresh.complete(listOf(book("kept", 3), book("new", 4)))
            withTimeout(5000) { model.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(setOf("kept"), model.localBookManagerUiState.selectedIds)
            assertTrue(model.localBookManagerUiState.isSelecting)
            assertEquals(3L, model.localBookManagerUiState.bookList.first().size)
            assertEquals(LocalBookSort.ChapterCount, model.localBookManagerUiState.sort)
            assertTrue(model.localBookManagerUiState.sortReverse)

            coEvery { query.refreshBooks() } returns emptyList()
            model.loadLocalBooks()
            withTimeout(5000) { model.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertTrue(model.localBookManagerUiState.selectedIds.isEmpty())
            assertFalse(model.localBookManagerUiState.isSelecting)
        } finally {
            model.viewModelScope.coroutineContext[Job]!!.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
