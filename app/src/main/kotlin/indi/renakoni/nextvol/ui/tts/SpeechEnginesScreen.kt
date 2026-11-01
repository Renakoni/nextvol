package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEnginesScreen(state: SpeechEnginesState, onBack: () -> Unit,
    onEdit: (SpeechEngineConfig?) -> Unit, onVoices: (SpeechEngineConfig) -> Unit,
    onAddVoice: (SpeechEngineConfig) -> Unit, onSave: (SpeechEngineConfig) -> Unit, onOrder: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SpeechEngineConfig?>(null) }
    var local by remember(state.engines) { mutableStateOf(state.engines) }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val moving = local.firstOrNull { it.key == from.key }
        val target = local.indexOfFirst { it.key == to.key }
        if (moving != null && target >= 0) local = local.toMutableList().apply { remove(moving); add(target, moving) }
    }
    val closeSearch = { searching = false; query = "" }
    BackHandler(searching, closeSearch)
    Scaffold(topBar = {
        SpeechSourcesTopBar(query, { query = it }, searching, { searching = true }, closeSearch, onBack,
            state.saving, onImport = {}, onRefresh = null, title = R.string.tts_engines,
            searchLabel = R.string.tts_engine_search, onAdd = { onEdit(null) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, contentPadding = PaddingValues(vertical = 12.dp)) {
            items(local.filter { engine -> query.isBlank() || listOf(engine.name, engine.code, engine.note).any { it.contains(query, true) } }, key = { it.key }) { engine ->
                ReorderableItem(reorder, engine.key) {
                    var menu by remember { mutableStateOf(false) }
                    Surface {
                        Row(Modifier.fillMaxWidth().clickable { onVoices(engine) }.heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(if (engine.type == "http") R.drawable.language_24px else R.drawable.settings_applications_24px),
                                if (engine.type == "http") "HttpTTS" else "innerTTS", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(engine.name.ifBlank { engine.code }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(engine.code, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!engine.enabled || engine.deleted) Text(stringResource(if (engine.deleted) R.string.tts_engine_missing else R.string.tts_engine_disabled),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { onAddVoice(engine) }, enabled = !state.saving && !engine.deleted) {
                                Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_engine_add_speaker))
                            }
                            IconButton(onClick = { onEdit(engine) }, enabled = !state.saving) {
                                Icon(painterResource(R.drawable.edit_square_24px), stringResource(R.string.tts_engine_edit))
                            }
                            Box {
                                IconButton(onClick = { menu = true }, modifier = if (query.isBlank() && !state.saving)
                                    Modifier.longPressDraggableHandle(onDragStopped = { onOrder(local.map { it.key }) }) else Modifier) {
                                    Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
                                }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_engine_voices)) }, onClick = { menu = false; onVoices(engine) })
                                    if (!engine.deleted) DropdownMenuItem(text = { Text(stringResource(if (engine.enabled) R.string.tts_voice_disable else R.string.tts_voice_enable)) },
                                        enabled = !state.saving, onClick = {
                                            menu = false
                                            val candidate = engine.copy(enabled = !engine.enabled)
                                            if (engine.enabled && state.voices(engine).isNotEmpty()) pending = candidate else onSave(candidate)
                                        })
                                    DropdownMenuItem(text = { Text(stringResource(if (engine.type == "http") R.string.tts_engine_delete else R.string.tts_engine_reset)) },
                                        enabled = !state.saving && !engine.deleted, onClick = {
                                            menu = false
                                            pending = if (engine.type == "http") engine.copy(enabled = false, deleted = true)
                                                else engine.copy(name = engine.code, parameters = "", note = "")
                                        })
                                }
                            }
                        }
                    }
                }
            }
            if (!state.loading && local.isEmpty()) item { Text(stringResource(R.string.tts_engine_empty), Modifier.padding(22.dp)) }
            state.error?.let { error -> item { Text(stringResource(error), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
    pending?.let { candidate ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(candidate.name) },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Text(stringResource(if (candidate.deleted) R.string.tts_engine_delete_warning else R.string.tts_engine_impact), Modifier.padding(bottom = 12.dp)) }
                items(state.voices(candidate)) { Text(it, Modifier.padding(vertical = 4.dp)) }
            } },
            confirmButton = { TextButton(onClick = { onSave(candidate); pending = null }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEngineEditorScreen(configuration: SpeechEngineConfig?, creating: Boolean, state: SpeechEnginesState,
    onBack: () -> Unit, onSave: (SpeechEngineConfig) -> Unit) {
    val title = if (creating) R.string.tts_engine_new else R.string.tts_engine_edit
    // Restore the draft after its engine is loaded, so the placeholder cannot reset saved edits.
    if (!creating && state.loading) {
        Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), title, onBack) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }
    val original = configuration ?: SpeechEngineConfig("", "", "http")
    var draft by rememberSaveable(original.key, stateSaver = androidx.compose.runtime.saveable.Saver<SpeechEngineConfig, String>(
        save = { kotlinx.serialization.json.Json.encodeToString(it) }, restore = { kotlinx.serialization.json.Json.decodeFromString(it) })) { mutableStateOf(original) }
    val back = { if (!state.saving) onBack() }
    BackHandler(onBack = back)
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), title, back) },
        bottomBar = {
            if (creating || configuration != null) Surface {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(back, Modifier.weight(1f), enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { onSave(draft.copy(name = draft.name.trim(), url = draft.url.trim(), deleted = false,
                        enabled = if (original.deleted) true else draft.enabled)) }, Modifier.weight(1f), enabled = !state.saving && draft.name.isNotBlank()) {
                        Text(stringResource(R.string.tts_engine_save))
                    }
                }
            }
        }) { padding ->
        if (!creating && configuration == null) {
            Text(stringResource(state.error ?: R.string.tts_engine_missing), Modifier.padding(padding).padding(22.dp),
                color = MaterialTheme.colorScheme.error)
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { EngineField(draft.name, R.string.tts_engine_name, !state.saving) { draft = draft.copy(name = it) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { EngineField(draft.code, R.string.tts_engine_code, creating && !state.saving) { draft = draft.copy(code = it) } }
                Box(Modifier.weight(1f)) { EngineField(if (draft.type == "http") "HttpTTS" else "innerTTS", R.string.tts_engine_type, false) {} }
            } }
            if (draft.type == "http") {
                item { EngineField(draft.url, R.string.tts_engine_url, !state.saving, true) { draft = draft.copy(url = it) } }
                item { EngineField(draft.header, R.string.tts_engine_header, !state.saving, true) { draft = draft.copy(header = it) } }
            }
            if (draft.code == "msctts" && draft.type == "inner") item {
                EngineField(draft.parameters, R.string.tts_engine_parameters, !state.saving, true) { draft = draft.copy(parameters = it) }
            }
            item { EngineField(draft.note, R.string.tts_engine_note, !state.saving, true) { draft = draft.copy(note = it) } }
            state.error?.let { error -> item { Text(stringResource(error), color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun EngineField(value: String, title: Int, enabled: Boolean, multiline: Boolean = false, onChange: (String) -> Unit) {
    TextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = !multiline,
        minLines = if (multiline) 3 else 1, colors = voiceFieldColors(), label = { Text(stringResource(title)) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEngineVoicesScreen(engine: SpeechEngineConfig?, state: SpeechEnginesState, onBack: () -> Unit,
    onEdit: () -> Unit, onAdd: () -> Unit, onOffline: (InstalledOfflineVoice) -> Unit, onHttp: (SavedHttpSpeechSource) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(engine?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onBack) { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back)) } },
        actions = {
            IconButton(onAdd, enabled = engine != null && !engine.deleted) { Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_engine_add_speaker)) }
            IconButton(onEdit, enabled = engine != null) { Icon(painterResource(R.drawable.edit_square_24px), stringResource(R.string.tts_engine_edit)) }
        }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (engine != null) {
                if (engine.type == "http") items(state.http.filter { it.importedEngineCode == engine.code }, key = { it.definition.id }) { source ->
                    val status = when {
                        source.engineDeleted -> R.string.tts_engine_missing
                        !source.engineEnabled -> R.string.tts_engine_disabled
                        !source.enabled -> R.string.tts_voice_disabled
                        !source.isConfigured -> R.string.tts_needs_key
                        else -> state.httpResults[source.definition.id]
                    }
                    ListItem(headlineContent = { Text(source.definition.name) }, modifier = Modifier.clickable { onHttp(source) },
                        supportingContent = status?.let { message -> ({ Text(stringResource(message),
                            color = if (message == R.string.tts_offline_verified) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }) })
                } else items(state.offline.filter { (it.configuration?.engine ?: "sherpa") == engine.code }, key = { it.reference.id }) { voice ->
                    val status = state.availability[voice.reference]?.takeIf { !it.canRun || it.message in setOf(
                        R.string.tts_offline_verified, R.string.tts_offline_initialization_failed, R.string.tts_offline_synthesis_failed) }
                    ListItem(headlineContent = { Text(voice.name) }, modifier = Modifier.clickable { onOffline(voice) },
                        supportingContent = status?.let { result -> ({ Text(stringResource(result.message, result.detail),
                            color = if (result.message == R.string.tts_offline_verified) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }) })
                }
                if (state.voices(engine).isEmpty()) item { Text(stringResource(R.string.tts_engine_no_speakers), Modifier.padding(22.dp)) }
            }
            state.error?.let { error -> item { Text(stringResource(error), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
}
