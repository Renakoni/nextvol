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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SpeechEnginesState(
    val engines: List<SpeechEngineConfig> = emptyList(),
    val offline: List<InstalledOfflineVoice> = emptyList(),
    val http: List<SavedHttpSpeechSource> = emptyList(),
    val availability: Map<OfflineVoiceRef, OfflineAvailability> = emptyMap(),
    val httpResults: Map<String, Int> = emptyMap(),
    val loading: Boolean = true, val saving: Boolean = false, val error: Int? = null,
) {
    fun voices(engine: SpeechEngineConfig): List<String> = if (engine.type == "http")
        http.filter { it.importedEngineCode == engine.code }.map { it.definition.name }
        else offline.filter { (it.configuration?.engine ?: "sherpa") == engine.code }.map { it.name }
}

internal val SpeechEngineConfig.key get() = "$type:$code"

@HiltViewModel
class SpeechEnginesViewModel @Inject constructor(
    private val repository: OfflineEngineRepository,
    private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val controller: ReadAloudController,
    private val availability: OfflineAvailabilityChecker,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechEnginesState())
    val state = mutableState.asStateFlow()
    private var inspected: String? = null
    private var inspecting: Job? = null
    init {
        viewModelScope.launch {
            combine(repository.changes, http.changes, offline.changes) { _, _, _ -> Unit }.collect {
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(loading = false, error = R.string.tts_error_storage) } }
            }
        }
        viewModelScope.launch { http.synthesisResults.collect {
            mutableState.update { state -> state.copy(httpResults = httpResults(state.http)) }
        } }
        viewModelScope.launch { availability.changes.drop(1).collect { inspected?.let(::inspect) } }
    }

    private suspend fun refresh() {
        val previous = state.value
        val voices = offline.voices()
        val installed = repository.all()
        val sources = http.sources()
        val engines = (installed + http.engines()).filter { engine ->
            !engine.deleted || sources.any { it.importedEngineCode == engine.code }
        }.sortedBy { it.order }
        mutableState.update { it.copy(engines = engines, offline = voices, http = sources, loading = false, httpResults = httpResults(sources)) }
        inspected?.let { code ->
            val before = previous.engines.find { it.type == "inner" && it.code == code }
            val after = engines.find { it.type == "inner" && it.code == code }
            if (previous.offline != voices || before?.enabled != after?.enabled || before?.parameters != after?.parameters) inspect(code)
        }
    }

    private fun httpResults(sources: List<SavedHttpSpeechSource>): Map<String, Int> = http.synthesisResults.value.mapNotNull { (id, result) ->
        if (sources.any { it.definition.id == id && result.source.sameExecution(it) }) id to result.message else null
    }.toMap()

    fun inspect(code: String) {
        inspected = code
        inspecting?.cancel()
        inspecting = viewModelScope.launch {
            try {
                val voices = state.value.offline.filter { (it.configuration?.engine ?: "sherpa") == code }
                val statuses = withContext(Dispatchers.IO) { voices.associate { it.reference to availability.installed(it, offline) } }
                mutableState.update { it.copy(availability = it.availability + statuses) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = R.string.tts_error_storage) } }
        }
    }

    fun save(configuration: SpeechEngineConfig, creating: Boolean, onSaved: () -> Unit) = work {
        val config = if (creating) {
            val code = configuration.code.trim()
            require(code.matches(Regex("[a-zA-Z0-9_]+")))
            configuration.copy(code = if (code.startsWith("http_")) code else "http_$code")
        } else configuration
        val old = state.value.engines.find { it.key == config.key }
        val executionChanged = old != null && (old.enabled != config.enabled || old.deleted != config.deleted ||
            old.url != config.url || old.header != config.header || old.parameters != config.parameters)
        withContext(NonCancellable) {
            if (config.type == "http") http.saveEngine(config, creating) else repository.save(config)
            if (executionChanged) stopAffected(config)
        }
        onSaved()
    }

    fun order(keys: List<String>) = work {
        val ranks = keys.withIndex().associate { it.value to it.index }
        withContext(NonCancellable) { repository.order(ranks); http.orderEngines(ranks) }
    }

    private suspend fun stopAffected(engine: SpeechEngineConfig) {
        val active = controller.state.value.request?.previewSettings ?: settings.get()
        val used = if (engine.type == "http") {
            val ids = http.sources().filter { it.importedEngineCode == engine.code }.map { it.definition.id }.toSet()
            active.httpSource in ids
        } else {
            val ids = offline.voices().filter { (it.configuration?.engine ?: "sherpa") == engine.code }.map { it.reference.id }.toSet()
            active.offlineVoice?.id in ids
        }
        if (used) controller.command(SpeechAction.Stop)
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
