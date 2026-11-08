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
                val previous = formattingGroups.associateBy { it.id }
                formattingGroups = map.map { (id, rules) ->
                    previous[id]?.copy(size = rules.size)
                        ?: FormattingGroup(id, bookRepository.getBookInformationFlow(id), rules.size)
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
        val target = bookId
        val rule = rules.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            formattingRepository.updateRule(
                bookId = target,
                formattingRule = rule.copy(isEnabled = !rule.isEnabled)
            )
        }
    }
}
