package indi.renakoni.nextvol.ui.tts

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.tts.ReadAloudController
import indi.renakoni.nextvol.tts.SpeechEngine
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.SpeechVoice
import indi.renakoni.nextvol.tts.SystemSpeechEngines
import indi.renakoni.nextvol.tts.HttpSpeechRepository
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.speechError
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
data class SpeechSettingsUiState(
    val settings: SpeechSettings = SpeechSettings(),
    val engines: List<SpeechEngine> = emptyList(),
    val voices: List<SpeechVoice> = emptyList(),
    val loading: Boolean = true,
    val error: SpeechError? = null,
    val httpSources: List<SavedHttpSpeechSource> = emptyList(),
    val offlineName: String? = null,
)

@HiltViewModel
class SpeechSettingsViewModel @Inject constructor(
    private val repository: SpeechSettingsRepository,
    private val engines: SystemSpeechEngines,
    val controller: ReadAloudController,
    private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechSettingsUiState())
    val state = mutableState.asStateFlow()
    private var catalog: Job? = null
    private var request = 0L
    private var catalogSelection: Triple<String, String?, OfflineVoiceRef?>? = null

    init {
        viewModelScope.launch {
            repository.changes.collect { settings ->
                mutableState.update { it.copy(settings = settings) }
                val selection = Triple(settings.engine, settings.httpSource, settings.offlineVoice)
                if (catalogSelection != selection) refresh()
            }
        }
    }

    fun refresh() {
        val settings = state.value.settings
        val target = Triple(settings.engine, settings.httpSource, settings.offlineVoice)
        val changed = catalogSelection != target
        catalogSelection = target
        val current = ++request
        catalog?.cancel()
        mutableState.update { it.copy(loading = true,
            voices = if (changed) emptyList() else it.voices, error = if (changed) null else it.error) }
        catalog = viewModelScope.launch {
            var installed = state.value.engines
            try {
                if (settings.offlineVoice != null) {
                    val voice = offline.voices().find { it.reference == settings.offlineVoice }
                    if (current == request) mutableState.update { it.copy(offlineName = voice?.name,
                        error = if (voice == null) SpeechError.OfflineUnavailable else null) }
                    return@launch
                }
                val selected = settings.httpSource
                if (selected != null) {
                    val sources = http.sources()
                    val source = sources.find { it.definition.id == selected }
                    val error = when {
                        source == null -> SpeechError.HttpSourceUnavailable
                        !source.isConfigured -> SpeechError.HttpLogin
                        else -> null
                    }
                    if (current == request) mutableState.update { it.copy(httpSources = sources, error = error) }
                    return@launch
                }
                installed = engines.installed()
                val details = engines.inspect(settings.engine)
                if (current == request) mutableState.update { it.copy(engines = installed, voices = details.voices, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current == request) mutableState.update { it.copy(engines = installed, voices = emptyList(), error = failure.speechError(
                    if (settings.httpSource == null) SpeechError.EngineUnavailable else SpeechError.Storage)) }
            } finally {
                if (current == request) mutableState.update { it.copy(loading = false) }
            }
        }
    }

    fun selectEngine(engine: String) = update { it.copy(engine = engine, voice = "", httpSource = null, offlineVoice = null) }
    fun selectVoice(voice: String) = update { it.copy(voice = voice) }
    fun setRate(value: Float?) = update { it.copy(rate = value) }
    fun setPitch(value: Float?) = update { it.copy(pitch = value) }
    fun setPlaybackSpeed(value: Float) = update { it.copy(playbackSpeed = value) }

    private fun update(change: (SpeechSettings) -> SpeechSettings) {
        viewModelScope.launch {
            try { repository.update(change) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = SpeechError.Storage) } }
        }
    }
}
