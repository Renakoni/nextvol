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
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class OfflineSpeechState(
    val voices: List<InstalledOfflineVoice> = emptyList(),
    val selected: OfflineVoiceRef? = null,
    val supported: Boolean = true,
    val missing: Map<OfflineVoiceRef, String> = emptyMap(),
    val error: Boolean = false,
)

@HiltViewModel
class OfflineSpeechSourcesViewModel @Inject constructor(
    private val repository: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val controller: ReadAloudController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OfflineSpeechState())
    private var refreshing: Job? = null
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch { settings.changes.collect { value -> mutableState.update { it.copy(selected = value.offlineVoice) } } }
        viewModelScope.launch { repository.changes.collect { refresh() } }
    }
    fun refresh() {
        refreshing?.cancel()
        refreshing = work {
            val voices = repository.voices()
            val missing = withContext(Dispatchers.IO) { voices.mapNotNull { voice -> repository.problem(voice)?.let { voice.reference to it } }.toMap() }
            mutableState.update { it.copy(voices = voices, missing = missing) }
        }
    }
    fun select(voice: InstalledOfflineVoice) = work {
        check(state.value.supported && voice.reference !in state.value.missing)
        check(repository.voices().any { it.reference == voice.reference })
        settings.update { it.copy(offlineVoice = voice.reference, httpSource = null) }
    }
    fun preview(voice: InstalledOfflineVoice, text: String) = work {
        check(state.value.supported && voice.reference !in state.value.missing)
        controller.preview(text, settings.get().copy(offlineVoice = voice.reference, httpSource = null))
    }
    fun delete(voice: InstalledOfflineVoice) = work {
        if (state.value.selected == voice.reference || controller.state.value.request?.previewSettings?.offlineVoice == voice.reference)
            controller.command(indi.renakoni.nextvol.tts.SpeechAction.Stop)
        // The lease remains held until native cleanup actually finishes.
        repository.delete(voice.reference)
        settings.update { if (it.offlineVoice == voice.reference) it.copy(offlineVoice = null) else it }
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
            if (!state.supported) Text(stringResource(R.string.tts_offline_device), Modifier.padding(22.dp), style = MaterialTheme.typography.bodyMedium)
            if (state.error) Text(stringResource(R.string.tts_error_storage), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error)
            if (state.voices.isEmpty() && state.supported) Text(stringResource(R.string.tts_offline_empty), Modifier.padding(22.dp), style = MaterialTheme.typography.bodyMedium)
            state.voices.forEach { voice ->
                val enabled = state.supported && voice.reference !in state.missing
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceChoice(voice.name, state.selected == voice.reference, enabled, { onSelect(voice) }, Modifier.weight(1f),
                        onPreview = { onPreview(voice) }, previewEnabled = enabled)
                    IconButton(onClick = { onDelete(voice) }) {
                        Icon(painterResource(R.drawable.delete_forever_24px), stringResource(R.string.tts_offline_delete, voice.name))
                    }
                }
                state.missing[voice.reference]?.let { path ->
                    Text(stringResource(R.string.tts_offline_missing, path), Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
