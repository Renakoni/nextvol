package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val avatars: Map<String, String> = emptyMap(),
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
            val avatars = withContext(Dispatchers.IO) { voices.mapNotNull { voice -> repository.avatar(voice)?.path?.let { voice.reference.id to it } }.toMap() }
            mutableState.update { it.copy(voices = voices, availability = results, avatars = avatars) }
        }
    }
    fun refreshCatalog() = work { repository.refreshCatalog(); refresh() }
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
    fun delete(voice: InstalledOfflineVoice) = delete(listOf(voice))
    fun delete(voices: List<InstalledOfflineVoice>) = work {
        if (voices.isEmpty()) return@work
        val ids = voices.map { it.reference.id }.toSet()
        val active = controller.state.value.request
        if (state.value.selected?.id in ids || active?.previewSettings?.offlineVoice?.id in ids)
            controller.command(indi.renakoni.nextvol.tts.SpeechAction.Stop)
        withContext(NonCancellable) {
            repository.delete(voices.map { it.reference }.toSet())
            settings.update { if (it.offlineVoice?.id in ids) it.copy(offlineVoice = null, httpSource = null) else it }
        }
    }
    fun enable(voices: List<InstalledOfflineVoice>, enabled: Boolean) = work {
        if (voices.isEmpty()) return@work
        val ids = voices.map { it.reference.id }.toSet()
        withContext(NonCancellable) {
            val updated = repository.enable(voices.map { it.reference }.toSet(), enabled)
            settings.update { selected -> updated.find { it.reference.id == selected.offlineVoice?.id && it.reference.id in ids }
                ?.let { selected.copy(offlineVoice = it.reference) } ?: selected }
        }
        if (!enabled && (state.value.selected?.id in ids || controller.state.value.request?.previewSettings?.offlineVoice?.id in ids))
            controller.command(indi.renakoni.nextvol.tts.SpeechAction.Stop)
    }
    fun copy(voices: List<InstalledOfflineVoice>, pattern: String) = work {
        if (voices.isNotEmpty()) repository.copyVoices(voices.map { it.reference }.toSet()) { String.format(pattern, it) }
    }
    private fun work(action: suspend () -> Unit) = viewModelScope.launch {
        mutableState.update { it.copy(error = false) }
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = true) } }
    }
}

internal fun LazyListScope.offlineSpeechSources(
    state: OfflineSpeechState, voices: List<InstalledOfflineVoice>, group: Int, chosen: Set<String>, selecting: Boolean,
    onChoose: (String) -> Unit, onSelect: (InstalledOfflineVoice) -> Unit, onPreview: (InstalledOfflineVoice) -> Unit,
    onEdit: (InstalledOfflineVoice, Boolean) -> Unit, onEnable: (List<InstalledOfflineVoice>, Boolean) -> Unit,
    onDelete: (List<InstalledOfflineVoice>) -> Unit,
) {
    if (state.error) item { Text(stringResource(R.string.tts_error_storage), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error) }
    if (state.voices.isEmpty()) item { Text(stringResource(R.string.tts_offline_empty), Modifier.padding(22.dp)) }
    voices.groupBy { when (group) { 1 -> it.configuration?.locale.orEmpty(); 2 -> ""; else -> it.configuration?.engine ?: "sherpa" } }
        .forEach { (title, grouped) ->
            item(key = "offline-group:$title") {
                Text(title.ifBlank { stringResource(R.string.tts_offline_sources) }, Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            items(grouped, key = { "offline:${it.reference.id}" }) { voice ->
                val status = state.availability[voice.reference]
                var actions by remember { mutableStateOf(false) }
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpeechAvatar(voice.metadata.avatar, voice.configuration?.gender ?: 0,
                            Modifier.padding(start = 12.dp).size(36.dp), state.avatars[voice.reference.id])
                        if (selecting) Row(Modifier.weight(1f).clickable { onChoose(voice.reference.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(voice.reference.id in chosen, { onChoose(voice.reference.id) })
                            Text(voice.name, style = MaterialTheme.typography.bodyLarge)
                        } else SourceChoice(voice.name, state.selected?.id == voice.reference.id, status?.canRun == true,
                            { onSelect(voice) }, Modifier.weight(1f), onPreview = { onPreview(voice) }, previewEnabled = status?.canRun == true)
                        Box {
                            IconButton(onClick = { actions = true }) {
                                Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
                            }
                            DropdownMenu(actions, { actions = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_edit_source)) }, onClick = { actions = false; onEdit(voice, false) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_voice_copy)) }, onClick = { actions = false; onEdit(voice, true) })
                                DropdownMenuItem(text = { Text(stringResource(if (voice.enabled) R.string.tts_voice_disable else R.string.tts_voice_enable)) },
                                    onClick = { actions = false; onEnable(listOf(voice), !voice.enabled) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_remove_source)) }, onClick = { actions = false; onDelete(listOf(voice)) })
                            }
                        }
                    }
                    if (voice.metadata.description.isNotBlank()) Text(voice.metadata.description, Modifier.padding(horizontal = 22.dp),
                        style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    status?.takeUnless { it.canRun }?.let {
                        Text(stringResource(it.message, it.detail), Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.canRun) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
}
