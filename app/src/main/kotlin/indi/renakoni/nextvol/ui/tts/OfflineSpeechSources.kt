package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import indi.renakoni.nextvol.tts.searchVoice
import indi.renakoni.nextvol.tts.matcha.*
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import indi.renakoni.nextvol.tts.offline.OfflineAvailabilityChecker
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class OfflineSpeechState(
    val voices: List<InstalledOfflineVoice> = emptyList(),
    val avatars: Map<String, String> = emptyMap(),
    val engineNames: Map<String, String> = emptyMap(),
    val selected: OfflineVoiceRef? = null,
    val availability: Map<OfflineVoiceRef, OfflineAvailability> = emptyMap(),
    val error: Boolean = false,
    val loaded: Boolean = false,
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
    private var inspecting: Job? = null
    private var activeEngine: String? = null
    private val inspectedEngines = mutableSetOf<String>()
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch { settings.changes.collect { value -> mutableState.update { it.copy(selected = value.offlineVoice) } } }
        viewModelScope.launch { combine(repository.changes, engines.changes) { _, _ -> Unit }.collect { refresh() } }
        viewModelScope.launch { availability.changes.drop(1).collect { inspectedEngines.clear(); refreshDetails() } }
    }
    fun refresh() {
        refreshing?.cancel()
        refreshing = work {
            val voices = repository.voices()
            val engineNames = engines.all().associate { it.code to it.name }
            mutableState.update { it.copy(voices = voices, engineNames = engineNames, loaded = true,
                availability = it.availability.filterKeys { ref -> voices.any { voice -> voice.reference == ref } },
                avatars = it.avatars.filterKeys { id -> voices.any { voice -> voice.reference.id == id } }) }
            inspectedEngines.clear()
            refreshDetails()
        }
    }
    fun openEngine(engine: String?) {
        if (activeEngine == engine) return
        activeEngine = engine
        refreshDetails()
    }
    private fun refreshDetails() {
        inspecting?.cancel()
        val engine = activeEngine ?: return
        if (engine in inspectedEngines) return
        val voices = state.value.voices.filter { (it.configuration?.engine ?: "sherpa") == engine }
        if (voices.isEmpty()) return
        inspecting = work {
            val results = withContext(Dispatchers.IO) { voices.associate { ensureActive(); it.reference to availability.installed(it, repository) } }
            val avatars = withContext(Dispatchers.IO) { voices.mapNotNull { voice ->
                ensureActive()
                repository.avatar(voice)?.path?.let { voice.reference.id to it }
            }.toMap() }
            mutableState.update { it.copy(availability = it.availability + results,
                avatars = it.avatars - voices.map { voice -> voice.reference.id }.toSet() + avatars) }
            inspectedEngines += engine
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
    private fun work(action: suspend () -> Unit) = viewModelScope.launch {
        mutableState.update { it.copy(error = false) }
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(error = true, loaded = true) } }
    }
}

internal fun LazyListScope.offlineSpeechSources(
    state: OfflineSpeechState, engine: String, query: String,
    onSelect: (InstalledOfflineVoice) -> Unit, onPreview: (InstalledOfflineVoice) -> Unit,
    onEdit: (InstalledOfflineVoice, Boolean) -> Unit, onEnable: (List<InstalledOfflineVoice>, Boolean) -> Unit,
    onDelete: (List<InstalledOfflineVoice>) -> Unit,
) {
    val search = hnovel.speech.SpeechVoiceSearch(query)
    val voices = state.voices.filter { voice ->
        (voice.configuration?.engine ?: "sherpa") == engine &&
            search.matches(voice.searchVoice(state.selected?.id == voice.reference.id))
    }
    if (voices.isEmpty() && query.isNotEmpty()) item {
        Text(stringResource(R.string.search_no_results), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(voices, key = { "offline:${it.reference.id}" }) { voice ->
        val status = state.availability[voice.reference]
        var actions by remember { mutableStateOf(false) }
        SpeechSourceRow(voice.name, state.selected?.id == voice.reference.id, status?.canRun == true,
            onClick = { onSelect(voice) }, avatar = {
                SpeechAvatar(voice.metadata.avatar, voice.configuration?.gender ?: 0,
                    Modifier.size(40.dp), state.avatars[voice.reference.id])
            }, onPreview = { onPreview(voice) }, description = voice.metadata.description.ifBlank { voice.configuration?.code.orEmpty() },
            problem = status?.takeUnless { it.canRun }?.let { stringResource(it.message, it.detail) }, actions = {
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
            })
    }
}
