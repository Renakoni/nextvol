package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SavedHttpSpeechSource
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.searchVoice
import indi.renakoni.nextvol.tts.speakerData
import indi.renakoni.nextvol.tts.voiceSelection
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HttpSpeechSourcesScreen(
    state: HttpSpeechSourcesState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSelect: (String?) -> Unit,
    onDelete: (SavedHttpSpeechSource) -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImport: () -> Unit,
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
    onVoiceEditor: ((SavedHttpSpeechSource, Boolean) -> Unit)? = null,
    selection: SpeechSettings = SpeechSettings(),
    onDialogue: (String?) -> Unit = {},
    onSystemGroup: () -> Unit = {},
    focusVoiceKey: String? = null,
    focusOfflineEngine: String? = null,
    configureCredentials: Boolean = false,
    onAddToPool: (List<SpeechVoiceRef>) -> Unit = {},
    onBackgroundMusic: (() -> Unit)? = null,
    sovitsInstalled: Boolean = false,
    onImportWeights: () -> Unit = {},
) {
    val tokens = NextVolDesign.tokens
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
        SpeechSourceRow(
            name = source.definition.name,
            selected = selected != null,
            enabled = !state.busy && (source.available || selected != null),
            onClick = { if (source.isConfigured || selected != null) onSelect(source.definition.id) else configuring = source },
            onLongClick = { onDialogue(source.definition.id) },
            selection = selected,
            management = management,
            avatar = {
                SpeechAvatar(
                    (metadata["avatar"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (metadata["gender"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    Modifier.size(40.dp),
                    state.avatars[source.definition.id],
                )
            },
            onPreview = onPreview?.let { { it(source.definition.id) } },
            previewEnabled = !state.busy && source.available && source.isConfigured,
            previewPlaying = playback.isActive && playback.previews(SpeechVoiceRef(httpSource = source.definition.id)),
            description = (metadata["desc"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            problem = when {
                source.engineDeleted -> stringResource(R.string.tts_engine_missing)
                !source.engineEnabled -> stringResource(R.string.tts_engine_disabled)
                !source.enabled -> stringResource(R.string.tts_voice_disabled)
                !source.isConfigured -> stringResource(R.string.tts_needs_key)
                else -> null
            },
            actions = {
                if (onVoiceEditor != null) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
                        }
                        EditorialPopupMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            EditorialPopupMenuHeader(text = source.definition.name)
                            EditorialPopupMenuItem(
                                text = stringResource(R.string.tts_edit_source),
                                leadingIcon = painterResource(R.drawable.edit_square_24px),
                                onClick = {
                                    menu = false
                                    onVoiceEditor(source, false)
                                },
                            )
                            EditorialPopupMenuItem(
                                text = stringResource(R.string.tts_voice_copy),
                                leadingIcon = painterResource(R.drawable.add_circle_24px),
                                onClick = {
                                    menu = false
                                    onVoiceEditor(source, true)
                                },
                            )
                            if (!source.isBuiltIn) {
                                EditorialHairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                EditorialPopupMenuItem(
                                    text = stringResource(R.string.tts_remove_source),
                                    leadingIcon = painterResource(R.drawable.delete_forever_24px),
                                    destructive = true,
                                    onClick = {
                                        menu = false
                                        onDelete(source)
                                    },
                                )
                            }
                        }
                    }
                } else if (!source.isBuiltIn) {
                    SpeechSourceActions(
                        enabled = !state.busy,
                        onEdit = {
                            definition = source
                            name = source.definition.name
                            url = source.definition.url
                            header = source.definition.text("header")
                        },
                        onDelete = { onDelete(source) },
                    )
                }
            },
        )
    }
    val onlineTitle = stringResource(R.string.tts_online_sources)
    fun groupId(source: SavedHttpSpeechSource) = source.importedEngineCode?.let { "engine:$it" }
        ?: source.category?.let { "category:$it" } ?: "custom"
    val httpGroups = state.sources.groupBy(::groupId)
    val groups = listOf("system" to stringResource(R.string.tts_system_speech)) +
        (if (sovitsInstalled) listOf("offline:gpt-sovits" to stringResource(R.string.tts_runtime_sovits)) else emptyList()) +
        offlineGroups.filterKeys { it != "gpt-sovits" }.map { (code, name) -> "offline:$code" to name } +
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
    val exitManagement = {
        managing = false
        checked = emptySet()
    }
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
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(searching, closeSearch)
    LaunchedEffect(currentGroup) { query = "" }
    LaunchedEffect(catalogReady, currentGroup) {
        if (catalogReady) onOfflineGroup(currentGroup.takeIf { it.startsWith("offline:") }?.removePrefix("offline:"))
        if (catalogReady && currentGroup == "system") onSystemGroup()
    }
    val searchQuery = query.trim()
    val search = remember(searchQuery) { hnovel.speech.SpeechVoiceSearch(searchQuery) }
    val filteredSources = remember(state.sources, selection, search) {
        state.sources.filter {
            val voice = it.searchVoice(selection.voiceSelection(SpeechVoiceRef(httpSource = it.definition.id)) != null)
            search.matches(voice)
        }
    }
    val filteredOffline = remember(offlineVoices, selection, search) {
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SpeechSourcesTopBar(
                query = query,
                onQuery = { query = it },
                searching = searching,
                onSearch = { searching = true },
                onCloseSearch = closeSearch,
                onBack = onBack,
                busy = state.busy,
                onImport = if (currentGroup == "offline:gpt-sovits") onImportWeights else onImport,
                onRefresh = onRefresh,
                importLabel = if (currentGroup == "offline:gpt-sovits") R.string.sovits_import_weights else R.string.tts_import_sources,
                onManage = if (currentGroup != "system") ({ managing = true }) else null,
                selectedCount = if (managing) visibleChecked.size else null,
                onExitManagement = exitManagement,
                onBackgroundMusic = onBackgroundMusic,
            )
        },
        bottomBar = {
            if (managing) {
                SpeechVoiceManagementBar(
                    hasVoices = selectableKeys.isNotEmpty(),
                    hasSelection = checked.isNotEmpty(),
                    hasVisibleSelection = visibleChecked.isNotEmpty(),
                    busy = state.busy,
                    onAll = { checked = selectableKeys },
                    onNone = { checked = emptySet() },
                    onInverse = { checked = (checked - selectableKeys) + (selectableKeys - checked) },
                    onDelete = {
                        if (currentGroup.startsWith("offline:")) {
                            onDeleteOffline(currentOffline.filter { "offline:${it.reference.id}" in visibleChecked })
                        } else {
                            onDeleteHttp(currentHttp.filter { "http:${it.definition.id}" in visibleChecked })
                        }
                    },
                    onAddToPool = {
                        onAddToPool(
                            currentHttp.filter { "http:${it.definition.id}" in visibleChecked }.map {
                                SpeechVoiceRef(httpSource = it.definition.id, label = it.definition.name)
                            } + currentOffline.filter { "offline:${it.reference.id}" in visibleChecked }.map {
                                SpeechVoiceRef(offlineVoice = it.reference, label = it.name)
                            },
                        )
                    },
                )
            } else if (hasPlayback) {
                SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!catalogReady) return@Column
            PrimaryScrollableTabRow(
                selectedTabIndex = currentPage,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = { EditorialHairlineDivider() },
                modifier = Modifier
                    .testTag("speech-source-tabs")
                    .semantics { testTagsAsResourceId = true },
            ) {
                groups.forEachIndexed { index, (_, title) ->
                    Tab(
                        selected = currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = {
                            Text(
                                text = title,
                                modifier = Modifier.widthIn(max = 160.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (currentPage == index) FontWeight.W600 else FontWeight.W400,
                            )
                        },
                    )
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
                    if (fromIndex >= 0 && toIndex >= 0) {
                        ordered = ordered.toMutableList().apply {
                            java.util.Collections.swap(this, fromIndex, toIndex)
                        }
                    }
                }
                val saveOrder = {
                    if (ordered != keys) {
                        if (group.startsWith("offline:")) onOrderOffline(ordered.map { it.removePrefix("offline:") })
                        else onOrderHttp(ordered.map { it.removePrefix("http:") })
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .selectableGroup()
                        .testTag("speech-sources-list"),
                    state = list,
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    state.error?.let { message ->
                        item {
                            EditorialCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                                contentPadding = PaddingValues(14.dp),
                            ) {
                                Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (managing && group == currentGroup && searchQuery.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.tts_manage_search_scope),
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    when {
                        group == "system" -> item {
                            val selected = selection.voiceSelection(SpeechVoiceRef(engine = selection.engine, voice = selection.voice))
                            SpeechSourceRow(
                                name = stringResource(R.string.tts_system_speech),
                                selected = selected != null,
                                enabled = !state.busy,
                                onClick = { onSelect(null) },
                                onLongClick = { onDialogue(null) },
                                selection = selected,
                                avatar = {
                                    EditorialIconBadge(
                                        painter = painterResource(R.drawable.headphones_24px),
                                        size = 40.dp,
                                        iconSize = 20.dp,
                                    )
                                },
                                onPreview = onPreview?.let { { it(null) } },
                                previewPlaying = playback.isActive && playback.previews(SpeechVoiceRef(engine = selection.engine, voice = selection.voice)),
                            )
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                systemSettings?.invoke(searchQuery)
                            }
                        }
                        else -> {
                            if (group == "offline:gpt-sovits" && ordered.isEmpty() && searchQuery.isEmpty()) {
                                item {
                                    EmptyPage(
                                        modifier = Modifier.fillParentMaxSize(),
                                        icon = painterResource(R.drawable.headphones_24px),
                                        title = stringResource(R.string.tts_runtime_sovits),
                                        description = stringResource(R.string.sovits_empty),
                                        content = {
                                            FilledTonalButton(
                                                onClick = onImportWeights,
                                                enabled = !state.busy,
                                                shape = CircleShape,
                                            ) {
                                                Icon(painterResource(R.drawable.input_24px), null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text(stringResource(R.string.sovits_import_weights))
                                            }
                                        },
                                    )
                                }
                            }
                            if (ordered.isEmpty() && searchQuery.isNotEmpty() && !state.busy) {
                                item {
                                    EmptyPage(
                                        modifier = Modifier.fillParentMaxHeight(0.75f),
                                        icon = painterResource(R.drawable.search_24px),
                                        title = stringResource(R.string.search_no_results),
                                        description = "",
                                    )
                                }
                            }
                            items(ordered, key = { it }) { key ->
                                ReorderableItem(reorder, key) {
                                    val management = if (managing && group == currentGroup) {
                                        VoiceRowManagement(
                                            checked = key in checked,
                                            selectable = !state.busy,
                                            onToggle = { checked = if (key in checked) checked - key else checked + key },
                                            dragHandle = Modifier.longPressDraggableHandle(enabled = !state.busy, onDragStopped = saveOrder),
                                        )
                                    } else {
                                        null
                                    }
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

    if (showPlayback && hasPlayback) {
        ReadAloudSheet(
            state = playback,
            onCommand = onCommand,
            onSettings = null,
            onDismiss = { showPlayback = false },
            onSleepTimer = onSleepTimer,
            onChapterSleepTimer = onChapterSleepTimer,
        )
    }

    configuring?.let { source ->
        SpeechCredentialsDialog(
            source = source,
            busy = state.busy,
            error = state.error,
            onSave = { values -> onConfigure(source, values) },
            onDismiss = { configuring = null },
        )
        val savedAtOpen = remember(source.definition.id) { state.configurationSaved }
        LaunchedEffect(state.configurationSaved) {
            if (state.configurationSaved != savedAtOpen) {
                configuring = null
            }
        }
    }

    state.pending?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissImport,
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = {
                Text(
                    pluralStringResource(R.plurals.tts_import_count, pending.size, pending.size),
                    fontWeight = FontWeight.W600,
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.tts_online_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.invalid > 0) {
                        item {
                            Text(
                                text = pluralStringResource(R.plurals.tts_import_invalid, state.invalid, state.invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    items(pending, key = { it.definition.id }) { source ->
                        EditorialCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = source.definition.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.W600,
                                )
                                if (source.origins.isNotEmpty()) {
                                    Text(
                                        text = source.origins.joinToString("\n"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (source.definition.usesMicrosoftTranslator) {
                                    Text(
                                        text = stringResource(R.string.tts_microsoft_sites),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmImport,
                    enabled = pending.isNotEmpty() && !state.busy,
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.tts_import_sources))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissImport, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    definition?.let { source ->
        val close = { definition = null }
        AlertDialog(
            onDismissRequest = { if (!state.busy) close() },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.tts_edit_source), fontWeight = FontWeight.W600) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(stringResource(R.string.tts_source_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(stringResource(R.string.tts_source_request)) },
                        minLines = 2,
                        maxLines = 5,
                    )
                    OutlinedTextField(
                        value = header,
                        onValueChange = { header = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        label = { Text(stringResource(R.string.tts_source_headers)) },
                        minLines = 2,
                        maxLines = 5,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onEdit(source, name, url, header)
                        definition = null
                    },
                    enabled = !state.busy && name.isNotBlank() && url.isNotBlank(),
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = close, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SpeechSourceActions(enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions))
        }
        EditorialPopupMenu(expanded = menu, onDismissRequest = { menu = false }) {
            EditorialPopupMenuItem(
                text = stringResource(R.string.tts_edit_source),
                leadingIcon = painterResource(R.drawable.edit_square_24px),
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            EditorialHairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            EditorialPopupMenuItem(
                text = stringResource(R.string.tts_remove_source),
                leadingIcon = painterResource(R.drawable.delete_forever_24px),
                destructive = true,
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun SpeechCredentialsDialog(
    source: SavedHttpSpeechSource,
    busy: Boolean,
    error: Int?,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var values by remember(source.definition.id) { mutableStateOf(source.credentials) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        title = { Text(source.category.orEmpty(), fontWeight = FontWeight.W600) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                source.credentialKeys.forEach { key ->
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { values = values + (key to it) },
                        label = { Text(key) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (key == "AppKey" || key == "AccessKeyId") {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                error?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (source.credentials.isNotEmpty()) {
                    TextButton(onClick = { onSave(emptyMap()) }, enabled = !busy) {
                        Text(stringResource(R.string.tts_clear_credentials), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(values) },
                enabled = !busy && source.credentialKeys.all { !values[it].isNullOrBlank() },
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
