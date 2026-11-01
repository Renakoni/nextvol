package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.speakerData
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch

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
    systemSettings: (@Composable (String) -> Unit)? = null,
    offlineGroups: Map<String, String> = emptyMap(),
    catalogReady: Boolean = true,
    onOfflineGroup: (String?) -> Unit = {},
    offlineSettings: (LazyListScope.(String, String) -> Unit)? = null,
    onVoiceEditor: ((SavedHttpSpeechSource, Boolean) -> Unit)? = null,
    onEnableVoice: (SavedHttpSpeechSource, Boolean) -> Unit = { _, _ -> },
    initialEditId: String? = null,
    onInitialEditorClosed: () -> Unit = {},
    onEditEngine: (SavedHttpSpeechSource, String, String, String) -> Unit = onEdit,
) {
    var showPlayback by remember { mutableStateOf(false) }
    val hasPlayback = playback.request != null && playback.phase != SpeechPhase.Stopped
    var configuring by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var sites by remember { mutableStateOf("") }
    var definition by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var readOnly by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    var openedInitialEditor by remember(initialEditId) { mutableStateOf(false) }
    LaunchedEffect(initialEditId, state.sources) {
        if (!openedInitialEditor && initialEditId != null) {
            state.sources.find { it.definition.id == initialEditId }?.let { source ->
                openedInitialEditor = true
                if (source.isBuiltIn && source.credentialKeys.isNotEmpty()) configuring = source
                else if (!source.isBuiltIn) {
                    definition = source
                    val engine = source.importedEngine.takeIf { source.importedEngineCode != null }
                    name = (engine?.get("name") as? JsonPrimitive)?.contentOrNull ?: source.definition.name
                    url = (engine?.get("url") as? JsonPrimitive)?.contentOrNull ?: source.definition.url
                    header = (engine?.get("header") as? JsonPrimitive)?.contentOrNull ?: source.definition.text("header")
                } else readOnly = source
            }
        }
    }
    val sourceRow: @Composable (SavedHttpSpeechSource) -> Unit = { source ->
        val metadata = source.speakerData()
        SpeechSourceRow(source.definition.name, state.selected == source.definition.id, !state.busy && source.enabled,
            onClick = { if (source.isConfigured) onSelect(source.definition.id) else configuring = source },
            avatar = {
                SpeechAvatar((metadata["avatar"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (metadata["gender"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    Modifier.size(40.dp), state.avatars[source.definition.id])
            }, onPreview = onPreview?.let { { it(source.definition.id) } },
            previewEnabled = !state.busy && source.enabled && source.isConfigured,
            description = (metadata["desc"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank {
                (metadata["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            },
            problem = when {
                !source.enabled -> stringResource(R.string.tts_voice_disabled)
                !source.isConfigured -> stringResource(R.string.tts_needs_key)
                else -> null
            }, actions = {
                if (onVoiceEditor != null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.tts_edit_source)) }, onClick = { menu = false; onVoiceEditor(source, false) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.tts_voice_copy)) }, onClick = { menu = false; onVoiceEditor(source, true) })
                            DropdownMenuItem(text = { Text(stringResource(if (source.enabled) R.string.tts_voice_disable else R.string.tts_voice_enable)) },
                                onClick = { menu = false; onEnableVoice(source, !source.enabled) })
                            if (!source.isBuiltIn) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_source_sites)) }, onClick = { menu = false; editing = source; sites = source.origins.joinToString("\n") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_remove_source)) }, onClick = { menu = false; onDelete(source) })
                            }
                        }
                    }
                } else if (!source.isBuiltIn) SpeechSourceActions(!state.busy,
                    onEdit = {
                        definition = source; name = source.definition.name
                        url = source.definition.url; header = source.definition.text("header")
                    },
                    onSites = { editing = source; sites = source.origins.joinToString("\n") },
                    onDelete = { onDelete(source) })
            })
    }
    val onlineTitle = stringResource(R.string.tts_online_sources)
    val httpGroups = state.sources.groupBy { it.category ?: onlineTitle }
    val groups = listOf("system" to stringResource(R.string.tts_system_speech)) +
        offlineGroups.map { (code, name) -> "offline:$code" to name } + httpGroups.keys.map { "http:$it" to it }
    // Pager's count and key callbacks must observe the same asynchronously loaded catalog.
    val pagerGroups by rememberUpdatedState(groups)
    val pager = rememberPagerState { pagerGroups.size }
    val scope = rememberCoroutineScope()
    val currentGroup = groups[pager.currentPage.coerceIn(groups.indices)].first
    val closeSearch = { searching = false; query = "" }
    BackHandler(searching, closeSearch)
    LaunchedEffect(currentGroup) { query = "" }
    LaunchedEffect(catalogReady, currentGroup) {
        if (catalogReady) onOfflineGroup(currentGroup.takeIf { it.startsWith("offline:") }?.removePrefix("offline:"))
    }
    val searchQuery = query.trim()
    val filteredSources = state.sources.filter { source ->
        val metadata = source.speakerData()
        (listOf(source.definition.name, source.category.orEmpty()) + listOf("locale", "note", "desc").map {
            (metadata[it] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }).any { it.contains(searchQuery, true) }
    }
    Scaffold(topBar = {
        SpeechSourcesTopBar(query, { query = it }, searching, { searching = true }, closeSearch, onBack, state.busy,
            onImport, onRefresh)
    }, bottomBar = {
        if (hasPlayback) SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!catalogReady) return@Column
            PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage.coerceIn(groups.indices), edgePadding = 16.dp, divider = {},
                modifier = Modifier.testTag("speech-source-tabs").semantics { testTagsAsResourceId = true }) {
                groups.forEachIndexed { index, (_, title) ->
                    Tab(selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(title, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            HorizontalPager(pager, Modifier.weight(1f), key = { pagerGroups[it].first }) { page ->
                val group = pagerGroups.getOrNull(page)?.first ?: return@HorizontalPager
                LazyColumn(Modifier.fillMaxSize().selectableGroup().testTag("speech-sources-list"),
                    contentPadding = PaddingValues(vertical = 12.dp)) {
                    state.error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(22.dp)) } }
                    when {
                        group == "system" -> item {
                            SpeechSourceRow(stringResource(R.string.tts_system_speech), state.selected == null && !state.offlineSelected,
                                !state.busy, { onSelect(null) }, avatar = {
                                    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.headphones_24px), null) }
                                    }
                                }, onPreview = onPreview?.let { { it(null) } })
                            if (state.selected == null && !state.offlineSelected) Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                systemSettings?.invoke(searchQuery)
                            }
                        }
                        group.startsWith("offline:") -> offlineSettings?.invoke(this, group.removePrefix("offline:"), searchQuery)
                        else -> {
                            val sources = filteredSources.filter { "http:${it.category ?: onlineTitle}" == group }
                            if (sources.isEmpty() && searchQuery.isNotEmpty() && !state.busy) item {
                                Text(stringResource(R.string.search_no_results), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(sources, key = { "http:${it.definition.id}" }) { source -> sourceRow(source) }
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
            onSave = { values -> onConfigure(source, values) }, onDismiss = {
                configuring = null
                if (source.definition.id == initialEditId) onInitialEditorClosed()
            })
        val savedAtOpen = remember(source.definition.id) { state.configurationSaved }
        LaunchedEffect(state.configurationSaved) {
            if (state.configurationSaved != savedAtOpen) {
                configuring = null
                if (source.definition.id == initialEditId) onInitialEditorClosed()
            }
        }
    }
    readOnly?.let { source ->
        val close = { readOnly = null; onInitialEditorClosed() }
        AlertDialog(onDismissRequest = close, title = { Text(source.category ?: source.definition.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tts_engine_preset_online))
                if (source.origins.isNotEmpty()) Text(source.origins.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.confirm)) } })
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
        val close = {
            definition = null
            if (source.definition.id == initialEditId) onInitialEditorClosed()
        }
        val savedAtOpen = remember(source.definition.id) { state.configurationSaved }
        LaunchedEffect(state.configurationSaved) {
            if (source.definition.id == initialEditId && state.configurationSaved != savedAtOpen) close()
        }
        AlertDialog(onDismissRequest = { if (!state.busy) close() }, title = {
            Text(stringResource(if (source.definition.id == initialEditId) R.string.tts_engine_edit else R.string.tts_edit_source))
        },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.tts_source_name)) }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.tts_source_request)) }, minLines = 2, maxLines = 5)
                    OutlinedTextField(header, { header = it }, label = { Text(stringResource(R.string.tts_source_headers)) }, minLines = 2, maxLines = 5)
                    if (source.definition.id == initialEditId) state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                }
            }, confirmButton = { TextButton(onClick = {
                if (source.definition.id == initialEditId && source.importedEngineCode != null) onEditEngine(source, name, url, header)
                else onEdit(source, name, url, header)
                if (source.definition.id != initialEditId) definition = null
            }, enabled = !state.busy && name.isNotBlank() && url.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            } }, dismissButton = { TextButton(onClick = close, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SpeechSourceActions(enabled: Boolean, onEdit: () -> Unit, onSites: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.tts_edit_source)) }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text(stringResource(R.string.tts_source_sites)) }, onClick = { menu = false; onSites() })
            DropdownMenuItem(text = { Text(stringResource(R.string.tts_remove_source)) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun SpeechCredentialsDialog(source: SavedHttpSpeechSource, busy: Boolean, error: Int?,
    onSave: (Map<String, String>) -> Unit, onDismiss: () -> Unit) {
    var values by remember(source.definition.id) { mutableStateOf(source.credentials) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(source.category.orEmpty()) },
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
