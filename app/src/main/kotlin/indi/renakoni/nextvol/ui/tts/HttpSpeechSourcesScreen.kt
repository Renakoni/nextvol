package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpSpeechSourcesScreen(
    state: HttpSpeechSourcesState, onBack: () -> Unit, onImport: () -> Unit,
    onSelect: (String?) -> Unit, onDelete: (SavedHttpSpeechSource) -> Unit,
    onSites: (SavedHttpSpeechSource, String) -> Unit, onConfirmImport: () -> Unit, onDismissImport: () -> Unit,
    onEdit: (SavedHttpSpeechSource, String, String, String) -> Unit,
    onConfigure: (SavedHttpSpeechSource, Map<String, String>) -> Unit = { _, _ -> },
    onPreview: ((String?) -> Unit)? = null,
    playback: ReadAloudState = ReadAloudState(),
    onCommand: (SpeechAction) -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    onSleepTimer: ((Int?) -> Unit)? = null,
    onChapterSleepTimer: ((Int) -> Unit)? = null,
    systemSettings: (@Composable () -> Unit)? = null,
) {
    var showPlayback by remember { mutableStateOf(false) }
    val hasPlayback = playback.request != null && playback.phase != SpeechPhase.Stopped
    var configuring by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var importMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var sites by remember { mutableStateOf("") }
    var definition by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.tts_sources), style = MaterialTheme.typography.displayLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back)) }
        }, actions = {
            if (onRefresh != null && state.selected == null) IconButton(onClick = onRefresh, enabled = !state.busy) {
                Icon(painterResource(R.drawable.refresh_24px), stringResource(R.string.tts_refresh))
            }
            if (state.busy) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp), strokeWidth = 2.dp)
            else Box {
                IconButton(onClick = { importMenu = true }) {
                    Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_library_actions))
                }
                DropdownMenu(importMenu, onDismissRequest = { importMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_import_sources)) }, onClick = { importMenu = false; onImport() })
                }
            }
        })
    }, bottomBar = {
        if (hasPlayback) SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).selectableGroup().testTag("speech-sources-list"),
            contentPadding = PaddingValues(vertical = 16.dp)) {
            state.error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            item {
                SettingsCategory {
                    SourceChoice(stringResource(R.string.tts_system_speech), state.selected == null, !state.busy, { onSelect(null) },
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
                        onPreview = onPreview?.let { { it(null) } })
                    if (state.selected == null) systemSettings?.invoke()
                }
            }
            if (state.sources.isEmpty() && !state.busy) item { Text(stringResource(R.string.tts_online_empty), Modifier.padding(16.dp)) }
            state.sources.filter { it.isBuiltIn }.groupBy { it.group!! }.forEach { (group, sources) ->
                item(key = "group:$group") {
                    val account = sources.first()
                    val current = sources.find { it.definition.id == state.selected }
                    SettingsCategory {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Button) { expanded = if (expanded == group) null else group }
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(group, style = MaterialTheme.typography.headlineSmall)
                                Text(if (!account.isConfigured) stringResource(R.string.tts_needs_key)
                                    else current?.definition?.name ?: pluralStringResource(R.plurals.tts_voice_count, sources.size, sources.size),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (account.credentialKeys.isNotEmpty()) IconButton(onClick = { configuring = account }, enabled = !state.busy) {
                                Icon(painterResource(R.drawable.outline_settings_24px), stringResource(R.string.tts_configure_service, group))
                            }
                            Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null, Modifier.rotate(if (expanded == group) 0f else 180f))
                        }
                    }
                }
                if (expanded == group) items(sources, key = { it.definition.id }) { source ->
                    SourceChoice(source.definition.name, state.selected == source.definition.id, !state.busy, {
                        if (source.isConfigured) onSelect(source.definition.id) else configuring = source
                    }, Modifier.padding(horizontal = 16.dp),
                        onPreview = onPreview?.let { { it(source.definition.id) } }, previewEnabled = source.isConfigured)
                }
            }
            items(state.sources.filterNot { it.isBuiltIn }, key = { it.definition.id }) { source ->
                SettingsCategory {
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                        SourceChoice(source.definition.name, state.selected == source.definition.id, !state.busy,
                            { onSelect(source.definition.id) }, Modifier.weight(1f),
                            onPreview = onPreview?.let { { it(source.definition.id) } }, previewEnabled = source.isConfigured)
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !state.busy) {
                                Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_edit_source)) }, onClick = {
                                    menu = false; definition = source; name = source.definition.name
                                    url = source.definition.url; header = source.definition.text("header")
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_source_sites)) }, onClick = {
                                    menu = false; editing = source; sites = source.origins.joinToString("\n")
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_remove_source)) }, onClick = { menu = false; onDelete(source) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (showPlayback && hasPlayback) ReadAloudSheet(playback, onCommand, onSettings = null, onDismiss = { showPlayback = false },
        onSleepTimer = onSleepTimer, onChapterSleepTimer = onChapterSleepTimer)
    configuring?.let { source ->
        SpeechCredentialsDialog(source, state.busy, state.error,
            onSave = { values -> onConfigure(source, values) }, onDismiss = { configuring = null })
        val savedAtOpen = remember(source.definition.id) { state.configurationSaved }
        LaunchedEffect(state.configurationSaved) {
            if (state.configurationSaved != savedAtOpen) configuring = null
        }
    }
    state.pending?.let { pending ->
        AlertDialog(onDismissRequest = onDismissImport, title = { Text(pluralStringResource(R.plurals.tts_import_count, pending.size, pending.size)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item { Text(stringResource(R.string.tts_online_privacy), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.invalid > 0) item { Text(pluralStringResource(R.plurals.tts_import_invalid, state.invalid, state.invalid), color = MaterialTheme.colorScheme.error) }
                    items(pending, key = { it.definition.id }) { source ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(source.definition.name, style = MaterialTheme.typography.bodyLarge)
                            if (source.origins.isNotEmpty()) Text(source.origins.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (source.definition.usesMicrosoftTranslator) Text(stringResource(R.string.tts_microsoft_sites),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = onConfirmImport, enabled = pending.isNotEmpty() && !state.busy) {
                Text(stringResource(R.string.tts_import_sources))
            } }, dismissButton = { TextButton(onClick = onDismissImport, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
    }
    editing?.let { source ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.tts_source_sites)) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(sites, { sites = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
                state.deniedOrigins[source.definition.id].orEmpty().forEach { origin ->
                    if (origin !in sites.lineSequence().map(String::trim).toSet()) TextButton(onClick = {
                        sites = (sites.trim() + "\n" + origin).trim()
                    }) { Text(stringResource(R.string.tts_allow_source_site, origin)) }
                }
                if (source.definition.usesMicrosoftTranslator) Text(stringResource(R.string.tts_microsoft_sites),
                    style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { onSites(source, sites); editing = null }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    definition?.let { source ->
        AlertDialog(onDismissRequest = { definition = null }, title = { Text(stringResource(R.string.tts_edit_source)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.tts_source_name)) }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.tts_source_request)) }, minLines = 2, maxLines = 5)
                    OutlinedTextField(header, { header = it }, label = { Text(stringResource(R.string.tts_source_headers)) }, minLines = 2, maxLines = 5)
                }
            }, confirmButton = { TextButton(onClick = { onEdit(source, name, url, header); definition = null }, enabled = name.isNotBlank() && url.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            } }, dismissButton = { TextButton(onClick = { definition = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SourceChoice(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    onPreview: (() -> Unit)? = null, previewEnabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (onPreview != null) SpeechPreviewButton(name, enabled && previewEnabled, onPreview)
    }
}

@Composable
private fun SpeechCredentialsDialog(source: SavedHttpSpeechSource, busy: Boolean, error: Int?,
    onSave: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var values by remember(source.definition.id) { mutableStateOf(source.credentials) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(source.group.orEmpty()) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                source.credentialKeys.forEach { key ->
                    OutlinedTextField(values[key].orEmpty(), { values = values + (key to it) }, label = { Text(key) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        visualTransformation = if (key == "AppKey" || key == "AccessKeyId") VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (source.credentials.isNotEmpty()) TextButton(onClick = { onSave(emptyMap()) }, enabled = !busy) {
                    Text(stringResource(R.string.tts_clear_credentials))
                }
            }
        }, confirmButton = {
            TextButton(onClick = { onSave(values) }, enabled = !busy && source.credentialKeys.all { !values[it].isNullOrBlank() }) {
                Text(stringResource(R.string.confirm))
            }
        }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
