package indi.renakoni.nextvol.ui.home.settings.textformatting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.format.FormatRepository
import indi.renakoni.nextvol.data.format.FormattingGroup
import indi.renakoni.nextvol.data.format.FormattingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FormattingViewModel @Inject constructor(
    private val formattingRepository: FormatRepository,
    bookRepository: BookRepository
) : ViewModel() {
    var formattingGroups by mutableStateOf(emptyList<FormattingGroup>())
        private set
    var bookId = ""
    var rules by mutableStateOf(emptyList<FormattingRule>())
        private set

    init {
        viewModelScope.launch {
            formattingRepository.rules.collect { map ->
                formattingGroups = map.map {
                    FormattingGroup(it.key, bookRepository.getBookInformationFlow(it.key), it.value.size)
                }
                rules = map[bookId].orEmpty()
            }
        }
    }

    fun loadBookFormattingRules(bookId: String) {
        this.bookId = bookId
        rules = formattingRepository.rules.value[bookId].orEmpty()
    }
    fun onToggle(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            formattingRepository.updateRule(
                bookId = bookId,
                formattingRule = rules
                    .firstOrNull { it.id == id }
                    ?.let {
                        it.copy(
                            isEnabled = !it.isEnabled
                        )
                    } ?: return@launch
            )
        }
    }
}
