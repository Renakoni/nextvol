package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.tts.SpeechSchemes
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.SpeechVoicePoolRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SpeechSchemesState(val schemes: SpeechSchemes = SpeechSchemes(), val titles: Map<String, String> = emptyMap(),
    val loading: Boolean = true, val saving: Boolean = false, val error: Boolean = false)

@HiltViewModel
class SpeechSchemesViewModel @Inject constructor(saved: SavedStateHandle, private val repository: SpeechSettingsRepository,
    private val pairs: SpeechVoicePoolRepository, bookshelf: BookshelfRepository, local: LocalBookDataSource) : ViewModel() {
    val bookId: String? = saved["bookId"]
    private val mutableState = MutableStateFlow(SpeechSchemesState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.schemes, bookshelf.getAllBookshelvesFlow()) { schemes, shelves ->
                val previous = state.value.titles
                val ids = (shelves.flatMap { it.allBookIds } + schemes.bindings.keys + listOfNotNull(bookId)).toSet()
                schemes to ids.associateWith { previous[it] ?: local.getBookInformation(it)?.title.orEmpty() }
            }.collect { (schemes, titles) -> mutableState.update { it.copy(schemes = schemes, titles = titles, loading = false) } }
        }
    }

    fun create(name: String, copyFrom: String?, onSaved: () -> Unit) = save(onSaved) {
        repository.create(name, copyFrom)
    }
    fun rename(id: String, name: String, onSaved: () -> Unit) = save(onSaved) { repository.rename(id, name) }
    fun default(id: String) = save { repository.setDefault(id) }
    fun bind(id: String?) = save { repository.bind(requireNotNull(bookId), id) }
    fun delete(id: String, replacement: String, onSaved: () -> Unit) = save(onSaved) {
        withContext(NonCancellable) {
            repository.delete(id, replacement)
            pairs.clear(schemeId = id)
        }
    }

    private fun save(onSaved: () -> Unit = {}, operation: suspend () -> Unit) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, error = false) }
        viewModelScope.launch {
            try { operation(); onSaved() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = true) } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}
