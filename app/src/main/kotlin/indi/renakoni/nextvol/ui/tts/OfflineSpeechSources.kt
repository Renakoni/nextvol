package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.ReadAloudController
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.matcha.*
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class OfflineSpeechState(
    val voices: List<InstalledOfflineVoice> = emptyList(),
    val selected: OfflineVoiceRef? = null,
    val availability: Map<OfflineVoiceRef, OfflineAvailability> = emptyMap(),
    val error: Boolean = false,
)

@HiltViewModel
class OfflineSpeechSourcesViewModel @Inject constructor(
    private val repository: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val controller: ReadAloudController,
    private val availability: OfflineAvailabilityChecker,
    private val engines: OfflineEngineRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineSpeechState())
    private var refreshing: Job? = null
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch { settings.changes.collect { value -> mutableState.update { it.copy(selected = value.offlineVoice) } } }
        viewModelScope.launch { combine(repository.changes, availability.changes, engines.changes) { _, _, _ -> Unit }.collect { refresh() } }
    }
    fun refresh() {
        refreshing?.cancel()
        refreshing = work {
            val voices = repository.voices()
            val results = withContext(Dispatchers.IO) { voices.associate { it.reference to availability.installed(it, repository) } }
            mutableState.update { it.copy(voices = voices, availability = results) }
        }
    }
    fun select(voice: InstalledOfflineVoice) = work {
        if (!canRun(voice)) return@work
        check(repository.voices().any { it.reference == voice.reference })
        settings.update { it.copy(offlineVoice = voice.reference, httpSource = null) }
    }
    fun preview(voice: InstalledOfflineVoice, text: String) = work {
        if (!canRun(voice)) return@work
        check(repository.voices().any { it.reference == voice.reference })
        controller.preview(text, settings.get().copy(offlineVoice = voice.reference, httpSource = null))
    }
    private suspend fun canRun(voice: InstalledOfflineVoice): Boolean {
        val result = withContext(Dispatchers.IO) { availability.installed(voice, repository) }
        mutableState.update { it.copy(availability = it.availability + (voice.reference to result)) }
        return result.canRun
    }
    fun delete(voice: InstalledOfflineVoice) = work {
        if (state.value.selected == voice.reference || controller.state.value.request?.previewSettings?.offlineVoice == voice.reference)
            controller.command(indi.renakoni.nextvol.tts.SpeechAction.Stop)
        // The lease remains held until native cleanup actually finishes.
        withContext(NonCancellable) {
            repository.delete(voice.reference)
            settings.update { if (it.offlineVoice == voice.reference) it.copy(offlineVoice = null) else it }
        }
    }
    private fun work(action: suspend () -> Unit) = viewModelScope.launch {
        mutableState.update { it.copy(error = false) }
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = true) } }
    }
}

@Composable
internal fun OfflineSpeechSources(state: OfflineSpeechState, onSelect: (InstalledOfflineVoice) -> Unit,
    onPreview: (InstalledOfflineVoice) -> Unit, onDelete: (InstalledOfflineVoice) -> Unit) {
    SettingsCategory(title = stringResource(R.string.tts_offline_sources)) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
            if (state.error) Text(stringResource(R.string.tts_error_storage), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error)
            if (state.voices.isEmpty()) Text(stringResource(R.string.tts_offline_empty), Modifier.padding(22.dp), style = MaterialTheme.typography.bodyMedium)
            state.voices.forEach { voice ->
                val status = state.availability[voice.reference]
                val enabled = status?.canRun == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceChoice(voice.name, state.selected == voice.reference, enabled, { onSelect(voice) }, Modifier.weight(1f),
                        onPreview = { onPreview(voice) }, previewEnabled = enabled)
                    IconButton(onClick = { onDelete(voice) }) {
                        Icon(painterResource(R.drawable.delete_forever_24px), stringResource(R.string.tts_offline_delete, voice.name))
                    }
                }
                status?.let {
                    Text(stringResource(it.message, it.detail), Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.canRun) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
