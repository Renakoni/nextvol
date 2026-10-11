package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onOk
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.home.bookshelf.toBookshelfUiState
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookshelfHomeViewModel @Inject constructor(
    private val bookshelfRepository: BookshelfRepository,
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository,
) : ViewModel() {
    private val _uiState = MutableBookshelfHomeUiState(
        changePage = ::changePage,
        changeLayout = ::changeLayout,
        changeSortType = ::changeSortType,
        changeSortReversed = ::changeSortReversed,
        changeBookSelectState = ::changeBookSelectState,
        enableReorderMode = ::enableReorderMode,
        disableReorderMode = ::disableReorderMode,
        moveBook = ::moveBook,
        enableBookshelfReorderMode = ::enableBookshelfReorderMode,
        disableBookshelfReorderMode = ::disableBookshelfReorderMode,
        moveBookshelf = ::moveBookshelf,
        onEnableSelectMode = ::enableSelectMode,
        onDisableSelectMode = ::disableSelectMode,
        onSelectAll = ::selectAllBooks,
        onPin = ::pinSelectedBooks,
        onRemove = ::removeSelectedBooks,
    )
    val uiState: BookshelfHomeUiState = _uiState
    private val bookshelfOrderUserData = userDataRepository.intListUserData(UserDataPath.BookshelfOrder.path)
    private val bookshelfLayoutUserData = userDataRepository.stringUserData(UserDataPath.Settings.Display.BookshelfLayout.path)
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            bookshelfLayoutUserData.getFlow().collect { value ->
                _uiState.layout = BookshelfLayout.entries.firstOrNull { it.name == value } ?: BookshelfLayout.List
            }
        }
    }

    fun changeLayout(layout: BookshelfLayout) {
        viewModelScope.launch {
            bookshelfLayoutUserData.set(layout.name)
        }
    }

    fun load() {
        // The main-graph model survives tab changes and already observes shelf updates.
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val bookshelfIdsFlow = bookshelfRepository.getAllBookshelvesFlow()
            val savedOrderFlow = bookshelfOrderUserData.getFlowWithDefault(emptyList())
            combine(bookshelfIdsFlow, savedOrderFlow) { bookshelves, savedOrder ->
                val stableIndexMap = savedOrder.withIndex().associate { it.value to it.index }
                bookshelves.sortedBy {
                    stableIndexMap[it.id] ?: Int.MAX_VALUE
                }
            }.map { list ->
                list.map {
                    it.toBookshelfUiState(bookRepository, bookshelfRepository)
                }
            }.collect { bookshelfUiStates ->
                if (_uiState.selectedBookshelf == null)
                    bookshelfUiStates.getOrNull(0)?.let {
                        changePage(it.id)
                    }
                _uiState.bookshelfList = bookshelfUiStates
            }
        }
    }

    fun changePage(bookshelfId: Int) {
        _uiState.selectedBookshelfId = bookshelfId
    }

    fun changeSortType(sortType: BookshelfSortType) {
        viewModelScope.launch(Dispatchers.IO) {
            bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) {
                it.copy(
                    sortType = sortType
                )
            }
        }
    }

    fun changeSortReversed(sortReversed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) {
                it.copy(
                    sortReversed = sortReversed
                )
            }
        }
    }

    fun enableReorderMode(bookshelfId: Int = _uiState.selectedBookshelfId) {
        val bookshelf = _uiState.bookshelfList.firstOrNull { it.id == bookshelfId } ?: return
        _uiState.selectedBookshelfId = bookshelfId
        if (bookshelf.sortType != BookshelfSortType.Default) return
        _uiState.reorderBookIds.clear()
        _uiState.reorderBookIds.addAll(bookshelf.allBookFlows)
        _uiState.reorderMode = true
    }

    fun disableReorderMode() {
        if (_uiState.reorderMode) {
            val reorderedIds = _uiState.reorderBookIds.toList()
            viewModelScope.launch(Dispatchers.IO) {
                bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) { oldBookshelf ->
                    oldBookshelf.copy(
                        allBookIds = reorderedIds.map { it.first }
                    )
                }
            }
        }
        _uiState.reorderMode = false
        _uiState.reorderBookIds.clear()
    }

    fun moveBook(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in _uiState.reorderBookIds.indices || toIndex !in _uiState.reorderBookIds.indices) return
        val item = _uiState.reorderBookIds.removeAt(fromIndex)
        _uiState.reorderBookIds.add(toIndex, item)
    }

    fun enableBookshelfReorderMode() {
        _uiState.reorderBookshelfIds.clear()
        _uiState.reorderBookshelfIds.addAll(_uiState.bookshelfList.map { it.id })
        _uiState.reorderBookshelfMode = true
    }

    fun disableBookshelfReorderMode() {
        disableBookshelfReorderMode(_uiState.reorderBookshelfIds.toList())
    }

    fun disableBookshelfReorderMode(reorderedIds: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.reorderBookshelfMode) {
                _uiState.reorderBookshelfIds.clear()
                _uiState.reorderBookshelfIds.addAll(reorderedIds)
                bookshelfOrderUserData.set(reorderedIds)
            }
            _uiState.reorderBookshelfMode = false
            _uiState.reorderBookshelfIds.clear()
        }
    }

    fun moveBookshelf(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in _uiState.reorderBookshelfIds.indices || toIndex !in _uiState.reorderBookshelfIds.indices) return
        val item = _uiState.reorderBookshelfIds.removeAt(fromIndex)
        _uiState.reorderBookshelfIds.add(toIndex, item)
    }

    fun enableSelectMode() {
        _uiState.selectMode = true
        _uiState.selectedBookIds.clear()
    }

    fun disableSelectMode() {
        _uiState.selectMode = false
        _uiState.selectedBookIds.clear()
    }

    fun changeBookSelectState(bookId: String) {
        if (_uiState.selectedBookIds.contains(bookId))
            _uiState.selectedBookIds.remove(bookId)
        else _uiState.selectedBookIds.add(bookId)
        if (_uiState.selectedBookIds.isEmpty()) disableSelectMode()
    }

    fun selectAllBooks() {
        val allBookIds = _uiState.selectedBookshelf?.allBookFlows?.map {
            it.first
        } ?: return
        if (_uiState.selectedBookIds.size == allBookIds.size) {
            _uiState.selectedBookIds.clear()
            return
        }
        _uiState.selectedBookIds.clear()
        _uiState.selectedBookIds.addAll(allBookIds)
    }

    fun pinSelectedBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            val pinnedBookIds = _uiState.selectedBookshelf?.pinnedBookFlows?.map {
                it.first
            } ?: return@launch
            val newPinnedBooksIds = _uiState.selectedBookIds
                .filter { pinnedBookIds.contains(it) }
                .let { removeList ->
                    (pinnedBookIds + (_uiState.selectedBookIds))
                        .toMutableList()
                        .apply {
                            removeAll { removeList.contains(it) }
                        }
                }
                .distinct()

            bookshelfRepository.updateBookshelf(_uiState.selectedBookshelfId) {
                it.copy(
                    pinnedBookIds = newPinnedBooksIds
                )
            }
            disableSelectMode()
        }
    }


    fun removeSelectedBooks() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.selectedBookIds.forEach {
                bookshelfRepository.deleteBookFromBookshelf(
                    _uiState.selectedBookshelfId,
                    it
                )
            }
            _uiState.selectedBookIds.clear()
        }
    }

    @Suppress("UNUSED")
    fun markSelectedBooks(bookshelfIds: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.selectedBookIds.forEach { bookId ->
                bookshelfIds.forEach { bookshelfId ->
                    bookRepository.getBookInformationFlow(bookId).last().onOk {
                        bookshelfRepository.addBookIntoBookShelf(
                            bookshelfId,
                            it
                        )
                    }
                }
            }
            _uiState.selectedBookIds.clear()
            _uiState.selectMode = false
        }
    }
}
