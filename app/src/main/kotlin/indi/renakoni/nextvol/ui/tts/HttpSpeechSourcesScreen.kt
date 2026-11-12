package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import indi.renakoni.nextvol.tts.searchVoice
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import indi.renakoni.nextvol.tts.voiceSelection
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HttpSpeechSourcesScreen(
    state: HttpSpeechSourcesState, onBack: () -> Unit, onImport: () -> Unit,
    onSelect: (String?) -> Unit, onDelete: (SavedHttpSpeechSource) -> Unit,
    onConfirmImport: () -> Unit, onDismissImport: () -> Unit,
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
    offlineVoices: List<InstalledOfflineVoice> = emptyList(),
    offlineRow: (@Composable (InstalledOfflineVoice, VoiceRowManagement?) -> Unit)? = null,
    onOrderHttp: (List<String>) -> Unit = {},
    onOrderOffline: (List<String>) -> Unit = {},
    onDeleteHttp: (List<SavedHttpSpeechSource>) -> Unit = {},
    onDeleteOffline: (List<InstalledOfflineVoice>) -> Unit = {},
    onEngine: (String) -> Unit = {},
    onVoiceEditor: ((SavedHttpSpeechSource, Boolean) -> Unit)? = null,
    onEnableVoice: (SavedHttpSpeechSource, Boolean) -> Unit = { _, _ -> },
    selection: SpeechSettings = SpeechSettings(),
    onDialogue: (String?) -> Unit = {},
    onSystemGroup: () -> Unit = {},
    focusVoiceKey: String? = null,
    focusOfflineEngine: String? = null,
    configureCredentials: Boolean = false,
    onAddToPool: (List<SpeechVoiceRef>) -> Unit = {},
    onBackgroundMusic: (() -> Unit)? = null,
) {
    var showPlayback by remember { mutableStateOf(false) }
    val hasPlayback = playback.request != null && playback.phase != SpeechPhase.Stopped
    var configuring by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var definition by remember { mutableStateOf<SavedHttpSpeechSource?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    val sourceRow: @Composable (SavedHttpSpeechSource, VoiceRowManagement?) -> Unit = { source, management ->
        val metadata = source.speakerData()
        val selected = selection.voiceSelection(SpeechVoiceRef(httpSource = source.definition.id))
        SpeechSourceRow(source.definition.name, selected != null, !state.busy && (source.available || selected != null),
            onClick = { if (source.isConfigured || selected != null) onSelect(source.definition.id) else configuring = source },
            onLongClick = { onDialogue(source.definition.id) }, selection = selected, management = management,
            avatar = {
                SpeechAvatar((metadata["avatar"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (metadata["gender"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    Modifier.size(40.dp), state.avatars[source.definition.id])
            }, onPreview = onPreview?.let { { it(source.definition.id) } },
            previewEnabled = !state.busy && source.available && source.isConfigured,
            previewPlaying = playback.isActive && playback.previews(SpeechVoiceRef(httpSource = source.definition.id)),
            description = (metadata["desc"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank {
                (metadata["code"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            },
            problem = when {
                source.engineDeleted -> stringResource(R.string.tts_engine_missing)
                !source.engineEnabled -> stringResource(R.string.tts_engine_disabled)
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
                            source.importedEngineCode?.let { code ->
                                DropdownMenuItem(text = { Text(stringResource(if (source.engineDeleted) R.string.tts_engine_repair else R.string.tts_engine_edit)) },
                                    onClick = { menu = false; onEngine(code) })
                            }
                            DropdownMenuItem(text = { Text(stringResource(if (source.enabled) R.string.tts_voice_disable else R.string.tts_voice_enable)) },
                                onClick = { menu = false; onEnableVoice(source, !source.enabled) })
                            if (!source.isBuiltIn) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_remove_source)) }, onClick = { menu = false; onDelete(source) })
                            }
                        }
                    }
                } else if (!source.isBuiltIn) SpeechSourceActions(!state.busy,
                    onEdit = {
                        definition = source; name = source.definition.name
                        url = source.definition.url; header = source.definition.text("header")
                    },
                    onDelete = { onDelete(source) })
            })
    }
    val onlineTitle = stringResource(R.string.tts_online_sources)
    fun groupId(source: SavedHttpSpeechSource) = source.importedEngineCode?.let { "engine:$it" }
        ?: source.category?.let { "category:$it" } ?: "custom"
    val httpGroups = state.sources.groupBy(::groupId)
    val groups = listOf("system" to stringResource(R.string.tts_system_speech)) +
        offlineGroups.map { (code, name) -> "offline:$code" to name } +
        httpGroups.map { (id, sources) -> "http:$id" to (sources.first().category ?: onlineTitle) }
    // Pager's count and key callbacks must observe the same asynchronously loaded catalog.
    val pagerGroups by rememberUpdatedState(groups)
    val pager = rememberPagerState { pagerGroups.size }
    val scope = rememberCoroutineScope()
    val currentGroup by remember {
        derivedStateOf {
            // Use the measured page key while Pager relocates it after a catalog change.
            val key = pager.layoutInfo.visiblePagesInfo.firstOrNull { it.index == pager.currentPage }?.key
            pagerGroups.firstOrNull { it.first == key }?.first
                ?: pagerGroups[pager.currentPage.coerceIn(pagerGroups.indices)].first
        }
    }
    val currentPage = groups.indexOfFirst { it.first == currentGroup }
    var managing by rememberSaveable(currentGroup) { mutableStateOf(false) }
    var checked by remember(currentGroup) { mutableStateOf(emptySet<String>()) }
    val exitManagement = { managing = false; checked = emptySet<String>() }
    BackHandler(managing && !searching, exitManagement)
    var focusHandled by rememberSaveable(focusVoiceKey) { mutableStateOf(false) }
    LaunchedEffect(catalogReady, focusVoiceKey, focusOfflineEngine) {
        if (catalogReady && focusVoiceKey != null && !focusHandled) {
            val source = state.sources.find { "http:${it.definition.id}" == focusVoiceKey }
            val group = when {
                source != null -> "http:${groupId(source)}"
                focusOfflineEngine != null -> "offline:$focusOfflineEngine"
                focusVoiceKey.startsWith("http:") -> groups.firstOrNull { it.first.startsWith("http:") }?.first
                focusVoiceKey.startsWith("offline:") -> groups.firstOrNull { it.first.startsWith("offline:") }?.first
                else -> "system"
            }
            val page = groups.indexOfFirst { it.first == group }
            if (page >= 0) pager.scrollToPage(page)
            if (configureCredentials && source?.credentialKeys?.isNotEmpty() == true) configuring = source
            focusHandled = true
        }
    }
    val closeSearch = { searching = false; query = "" }
    BackHandler(searching, closeSearch)
    LaunchedEffect(currentGroup) { query = "" }
    LaunchedEffect(catalogReady, currentGroup) {
        if (catalogReady) onOfflineGroup(currentGroup.takeIf { it.startsWith("offline:") }?.removePrefix("offline:"))
        if (catalogReady && currentGroup == "system") onSystemGroup()
    }
    val searchQuery = query.trim()
    val filteredSources = remember(state.sources, selection, searchQuery) {
        val search = hnovel.speech.SpeechVoiceSearch(searchQuery)
        state.sources.filter {
            val voice = it.searchVoice(selection.voiceSelection(SpeechVoiceRef(httpSource = it.definition.id)) != null)
            search.matches(voice)
        }
    }
    val filteredOffline = remember(offlineVoices, selection, searchQuery) {
        val search = hnovel.speech.SpeechVoiceSearch(searchQuery)
        offlineVoices.filter {
            val voice = it.searchVoice(selection.voiceSelection(SpeechVoiceRef(offlineVoice = it.reference)) != null)
            search.matches(voice)
        }
    }
    val currentHttp = filteredSources.filter { "http:${groupId(it)}" == currentGroup }
    val currentOffline = filteredOffline.filter { "offline:${it.configuration?.engine ?: "sherpa"}" == currentGroup }
    val selectableKeys = (currentHttp.map { "http:${it.definition.id}" } +
        currentOffline.map { "offline:${it.reference.id}" }).toSet()
    val visibleChecked = checked.intersect(selectableKeys)
    LaunchedEffect(state.sources, offlineVoices) {
        val existing = state.sources.map { "http:${it.definition.id}" } + offlineVoices.map { "offline:${it.reference.id}" }
        checked = checked.intersect(existing.toSet())
    }
    Scaffold(topBar = {
        SpeechSourcesTopBar(query, { query = it }, searching, { searching = true }, closeSearch, onBack, state.busy,
            onImport, onRefresh, onManage = if (currentGroup != "system") ({ managing = true }) else null,
            selectedCount = if (managing) visibleChecked.size else null, onExitManagement = exitManagement,
            onBackgroundMusic = onBackgroundMusic)
    }, bottomBar = {
        if (managing) SpeechVoiceManagementBar(selectableKeys.isNotEmpty(), checked.isNotEmpty(), visibleChecked.isNotEmpty(),
            state.busy, onAll = { checked = selectableKeys }, onNone = { checked = emptySet() },
            onInverse = { checked = (checked - selectableKeys) + (selectableKeys - checked) },
            onDelete = {
                if (currentGroup.startsWith("offline:")) onDeleteOffline(currentOffline.filter { "offline:${it.reference.id}" in visibleChecked })
                else onDeleteHttp(currentHttp.filter { "http:${it.definition.id}" in visibleChecked })
            }, onAddToPool = {
                onAddToPool(currentHttp.filter { "http:${it.definition.id}" in visibleChecked }.map {
                    SpeechVoiceRef(httpSource = it.definition.id, label = it.definition.name)
                } + currentOffline.filter { "offline:${it.reference.id}" in visibleChecked }.map {
                    SpeechVoiceRef(offlineVoice = it.reference, label = it.name)
                })
            })
        else if (hasPlayback) SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!catalogReady) return@Column
            PrimaryScrollableTabRow(selectedTabIndex = currentPage, edgePadding = 16.dp, divider = {},
                modifier = Modifier.testTag("speech-source-tabs").semantics { testTagsAsResourceId = true }) {
                groups.forEachIndexed { index, (_, title) ->
                    Tab(selected = currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(title, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
            HorizontalPager(pager, Modifier.weight(1f), key = { pagerGroups[it].first }) { page ->
                val group = pagerGroups.getOrNull(page)?.first ?: return@HorizontalPager
                val sources = filteredSources.filter { "http:${groupId(it)}" == group }.associateBy { "http:${it.definition.id}" }
                val offline = filteredOffline.filter { "offline:${it.configuration?.engine ?: "sherpa"}" == group }
                    .associateBy { "offline:${it.reference.id}" }
                val keys = sources.keys.toList() + offline.keys.toList()
                var ordered by remember(keys) { mutableStateOf(keys) }
                LaunchedEffect(state.error) { if (state.error != null) ordered = keys }
                val list = rememberLazyListState()
                val reorder = rememberReorderableLazyListState(list) { from, to ->
                    val fromIndex = ordered.indexOf(from.key)
                    val toIndex = ordered.indexOf(to.key)
                    if (fromIndex >= 0 && toIndex >= 0) ordered = ordered.toMutableList().apply {
                        java.util.Collections.swap(this, fromIndex, toIndex)
                    }
                }
                val saveOrder = {
                    if (ordered != keys) {
                        if (group.startsWith("offline:")) onOrderOffline(ordered.map { it.removePrefix("offline:") })
                        else onOrderHttp(ordered.map { it.removePrefix("http:") })
                    }
                }
                LazyColumn(Modifier.fillMaxSize().selectableGroup().testTag("speech-sources-list"),
                    state = list, contentPadding = PaddingValues(vertical = 12.dp)) {
                    state.error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(22.dp)) } }
                    if (managing && group == currentGroup && searchQuery.isNotEmpty()) item {
                        Text(stringResource(R.string.tts_manage_search_scope), Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        group == "system" -> item {
                            val selected = selection.voiceSelection(SpeechVoiceRef(engine = selection.engine, voice = selection.voice))
                            SpeechSourceRow(stringResource(R.string.tts_system_speech), selected != null,
                                !state.busy, { onSelect(null) }, onLongClick = { onDialogue(null) }, selection = selected, avatar = {
                                    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                        Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.headphones_24px), null) }
                                    }
                                }, onPreview = onPreview?.let { { it(null) } },
                                previewPlaying = playback.isActive && playback.previews(SpeechVoiceRef(engine = selection.engine, voice = selection.voice)))
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                systemSettings?.invoke(searchQuery)
                            }
                        }
                        else -> {
                            if (ordered.isEmpty() && searchQuery.isNotEmpty() && !state.busy) item {
                                Text(stringResource(R.string.search_no_results), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(ordered, key = { it }) { key ->
                                ReorderableItem(reorder, key) {
                                    val management = if (managing && group == currentGroup) VoiceRowManagement(
                                        checked = key in checked, selectable = !state.busy,
                                        onToggle = { checked = if (key in checked) checked - key else checked + key },
                                        dragHandle = Modifier.longPressDraggableHandle(enabled = !state.busy, onDragStopped = saveOrder),
                                    ) else null
                                    sources[key]?.let { sourceRow(it, management) }
                                    offline[key]?.let { offlineRow?.invoke(it, management) }
                                }
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
            onSave = { values -> onConfigure(source, values) }, onDismiss = {
                configuring = null
            })
        val savedAtOpen = remember(source.definition.id) { state.configurationSaved }
        LaunchedEffect(state.configurationSaved) {
            if (state.configurationSaved != savedAtOpen) {
                configuring = null
            }
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
    definition?.let { source ->
        val close = { definition = null }
        AlertDialog(onDismissRequest = { if (!state.busy) close() }, title = {
            Text(stringResource(R.string.tts_edit_source))
        },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.tts_source_name)) }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.tts_source_request)) }, minLines = 2, maxLines = 5)
                    OutlinedTextField(header, { header = it }, label = { Text(stringResource(R.string.tts_source_headers)) }, minLines = 2, maxLines = 5)
                }
            }, confirmButton = { TextButton(onClick = {
                onEdit(source, name, url, header)
                definition = null
            }, enabled = !state.busy && name.isNotBlank() && url.isNotBlank()) {
                Text(stringResource(R.string.confirm))
            } }, dismissButton = { TextButton(onClick = close, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SpeechSourceActions(enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.tts_edit_source)) }, onClick = { menu = false; onEdit() })
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
