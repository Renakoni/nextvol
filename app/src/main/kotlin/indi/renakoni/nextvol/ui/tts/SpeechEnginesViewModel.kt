package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpeechEnginesState(
    val engines: List<SpeechEngineConfig> = emptyList(),
    val loading: Boolean = true, val saving: Boolean = false, val error: Int? = null,
)

@HiltViewModel
class SpeechEnginesViewModel @Inject constructor(
    private val repository: SpeechEngineRepository,
    private val http: HttpSpeechRepository,
    private val editing: SpeechEngineEditing,
    private val loginService: SpeechEngineLoginService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechEnginesState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            combine(repository.changes, http.changes) { _, _ -> Unit }.collect {
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(loading = false, error = R.string.tts_error_storage) } }
            }
        }
    }

    private suspend fun refresh() {
        val installed = repository.all()
        val sources = http.sources()
        val engines = (http.engines() + installed).distinctBy { it.key }.filter { engine ->
            !engine.deleted || sources.any { it.importedEngineCode == engine.code }
        }.sortedBy { it.order }
        mutableState.update { it.copy(engines = engines, loading = false) }
    }

    fun save(configuration: SpeechEngineConfig, creating: Boolean, onSaved: () -> Unit) = work {
        editing.save(configuration, creating, state.value.engines)
        onSaved()
    }

    fun login(configuration: SpeechEngineConfig, onLoggedIn: (SpeechEngineConfig) -> Unit) = work {
        onLoggedIn(loginService.login(configuration))
    }

    fun order(keys: List<String>) = work { editing.order(keys) }

    private fun work(action: suspend () -> Unit) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { mutableState.update { it.copy(error = R.string.tts_engine_invalid) } }
            catch (_: Exception) { mutableState.update { it.copy(error = R.string.save_failed) } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}
