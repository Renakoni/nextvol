package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.matcha.*
import indi.renakoni.nextvol.tts.offline.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SpeechEnginesState(
    val engines: List<SpeechEngineConfig> = emptyList(),
    val loading: Boolean = true, val saving: Boolean = false, val error: Int? = null,
)

internal val SpeechEngineConfig.key get() = "$type:$code"

@HiltViewModel
class SpeechEnginesViewModel @Inject constructor(
    private val repository: OfflineEngineRepository,
    private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val controller: ReadAloudController,
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
        val config = if (creating) {
            val code = configuration.code.trim()
            require(code.matches(Regex("[a-zA-Z0-9_]+")))
            configuration.copy(code = if (configuration.type != "http" || code.startsWith("http_")) code else "http_$code")
        } else configuration
        val old = state.value.engines.find { it.key == config.key }
        val executionChanged = old != null && (old.enabled != config.enabled || old.deleted != config.deleted ||
            old.url != config.url || old.header != config.header || old.parameters != config.parameters)
        withContext(NonCancellable) {
            saveConfiguration(config, creating)
            if (executionChanged) stopAffected(config)
        }
        onSaved()
    }

    fun login(configuration: SpeechEngineConfig, onLoggedIn: (SpeechEngineConfig) -> Unit) = work {
        onLoggedIn(loginService.login(configuration))
    }

    private suspend fun saveConfiguration(config: SpeechEngineConfig, creating: Boolean) {
        if (config.isOnline) {
            http.saveEngine(config, creating)
        } else if (creating) {
            require(repository.get(config.code) == null)
            repository.register(config)
        } else repository.save(config)
    }

    fun order(keys: List<String>) = work {
        val ranks = keys.withIndex().associate { it.value to it.index }
        withContext(NonCancellable) { repository.order(ranks); http.orderEngines(ranks) }
    }

    private suspend fun stopAffected(engine: SpeechEngineConfig) {
        val active = controller.state.value.request?.let { it.previewSettings ?: settings.forBook(it.bookId) } ?: settings.get()
        val onlineUsed = if (engine.isOnline) {
            val ids = http.sources().filter { it.importedEngineCode == engine.code }.map { it.definition.id }.toSet()
            active.httpSource in ids
        } else false
        val offlineUsed = run {
            val ids = offline.voices().filter { (it.configuration?.engine ?: "sherpa") == engine.code }.map { it.reference.id }.toSet()
            active.offlineVoice?.id in ids
        }
        if (onlineUsed || offlineUsed) controller.command(SpeechAction.Stop)
    }

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
