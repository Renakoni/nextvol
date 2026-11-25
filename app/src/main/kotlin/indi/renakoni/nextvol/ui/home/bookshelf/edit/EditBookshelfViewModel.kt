package indi.renakoni.nextvol.ui.home.bookshelf.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditBookshelfViewModel @Inject constructor(
    private val bookshelfRepository: BookshelfRepository
) : ViewModel() {
    var bookshelf: Bookshelf by mutableStateOf(Bookshelf())
        private set

    fun init(id: Int?) {
        if (id == null || id < 0 || bookshelf.id == id) return
        viewModelScope.launch(Dispatchers.IO) {
            bookshelf = bookshelfRepository.getBookshelf(id) ?: Bookshelf()
        }
    }

    fun onNameChange(name: String) {
        bookshelf = bookshelf.copy(
            name = name
        )
    }

    fun onAutoCacheChange(autoCache: Boolean) {
        bookshelf = bookshelf.copy(
            autoCache = autoCache
        )
    }

    fun onSystemUpdateReminderChange(systemUpdateReminder: Boolean) {
        bookshelf = bookshelf.copy(
            systemUpdateReminder = systemUpdateReminder
        )
    }

    fun onSortTypeChange(sortType: BookshelfSortType) {
        bookshelf = bookshelf.copy(
            sortType = sortType
        )
    }

    suspend fun save(id: Int) {
        val edited = bookshelf
        if (id < 0) {
            bookshelfRepository.addBookshelf(edited)
        } else {
            bookshelfRepository.updateBookshelf(id) { current ->
                current.copy(
                    name = edited.name,
                    sortType = edited.sortType,
                    autoCache = edited.autoCache,
                    systemUpdateReminder = edited.systemUpdateReminder,
                )
            }
        }
    }

    suspend fun delete(id: Int) = bookshelfRepository.deleteBookshelf(id)
}
