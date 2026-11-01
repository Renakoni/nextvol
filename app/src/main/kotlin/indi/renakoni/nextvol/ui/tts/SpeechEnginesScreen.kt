package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hnovel.speech.OfflineEngineConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.HttpSpeechRepository
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.ReadAloudController
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.offline.OfflineEngineRepository
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpeechEnginesState(
    val engines: List<OfflineEngineConfig> = emptyList(),
    val http: List<SavedHttpSpeechSource> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
class SpeechEnginesViewModel @Inject constructor(
    private val repository: OfflineEngineRepository,
    private val http: HttpSpeechRepository,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
    private val controller: ReadAloudController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechEnginesState())
    val state = mutableState.asStateFlow()
    init { viewModelScope.launch { repository.changes.collect { refresh() } } }

    fun refresh() = viewModelScope.launch {
        try {
            val engines = repository.all()
            val online = http.sources().distinctBy { source -> when {
                source.isBuiltIn || source.engineReference != null -> "builtin" to source.category
                source.importedEngineCode != null -> "engine" to source.importedEngineCode
                else -> "source" to source.definition.id
            } }
            mutableState.update { it.copy(engines = engines, http = online, loading = false, failed = false) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, failed = true) } }
    }

    fun save(configuration: OfflineEngineConfig, onSaved: () -> Unit) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, failed = false) }
        viewModelScope.launch {
            try {
                val previous = state.value.engines.single { it.code == configuration.code }
                if (previous.parameters != configuration.parameters) {
                    val selected = settings.get().offlineVoice
                    val preview = controller.state.value.request?.previewSettings?.offlineVoice
                    if (offline.voices().any { it.configuration?.engine == configuration.code &&
                            (it.reference == selected || it.reference == preview) }) {
                        controller.command(SpeechAction.Stop)
                    }
                }
                repository.save(configuration)
                onSaved()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(failed = true) } }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEnginesScreen(state: SpeechEnginesState, onBack: () -> Unit,
    onEdit: (String) -> Unit, onHttp: (String) -> Unit) {
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_engines, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-engines-list"), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
            if (state.failed) item { Text(stringResource(R.string.tts_error_storage), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error) }
            item {
                SettingsCategory(title = stringResource(R.string.tts_engines_builtin)) {
                    state.engines.forEach { engine ->
                        SettingsClickableEntry(Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                            title = engine.name, description = engine.code,
                            trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                            onClick = { onEdit(engine.code) })
                    }
                }
            }
            if (state.http.isNotEmpty()) item {
                SettingsCategory(title = stringResource(R.string.tts_engines_http)) {
                    state.http.forEach { source ->
                        SettingsClickableEntry(Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                            title = source.category ?: source.definition.name,
                            trailingContent = { Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp)) },
                            onClick = { onHttp(source.definition.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEngineEditorScreen(configuration: OfflineEngineConfig?, state: SpeechEnginesState,
    onBack: () -> Unit, onSave: (OfflineEngineConfig) -> Unit) {
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_engine_edit, onBack) }) { padding ->
        if (configuration == null) {
            Column(Modifier.fillMaxWidth().padding(padding).padding(22.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text(stringResource(R.string.tts_error_storage), color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }
        var name by rememberSaveable(configuration.code) { mutableStateOf(configuration.name) }
        var parameters by rememberSaveable(configuration.code) { mutableStateOf(configuration.parameters) }
        var note by rememberSaveable(configuration.code) { mutableStateOf(configuration.note) }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-engine-editor"),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(configuration.code, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !state.saving,
                label = { Text(stringResource(R.string.tts_engine_name)) }, singleLine = true, isError = name.isBlank()) }
            item { OutlinedTextField(parameters, { parameters = it }, Modifier.fillMaxWidth(), enabled = !state.saving,
                label = { Text(stringResource(R.string.tts_engine_parameters)) }, minLines = 4,
                supportingText = { Text(stringResource(R.string.tts_engine_parameters_hint)) }) }
            item { OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), enabled = !state.saving,
                label = { Text(stringResource(R.string.tts_engine_note)) }, minLines = 2) }
            if (state.failed) item { Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error) }
            item { Button(onClick = { onSave(configuration.copy(name = name.trim(), parameters = parameters, note = note)) },
                enabled = !state.saving && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tts_engine_save))
            } }
        }
    }
}
