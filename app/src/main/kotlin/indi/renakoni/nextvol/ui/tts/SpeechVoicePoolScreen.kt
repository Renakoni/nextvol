package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechVoicePoolScreen(state: SpeechVoicePoolState, onBack: () -> Unit,
    onEditItem: (SpeechVoiceItem?) -> Unit, onEditPair: (SpeechVoicePair) -> Unit,
    onToggle: (Set<String>, Boolean) -> Unit, onOrder: (List<SpeechVoiceItem>) -> Unit, onDeleteItems: (Set<String>) -> Unit,
    onDeletePairs: (Set<String>) -> Unit, onReassign: (Set<String>) -> Unit,
    onClearPairs: (String?) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var deletingItems by remember { mutableStateOf(emptySet<String>()) }
    var deletingPairs by remember { mutableStateOf(emptySet<String>()) }
    var clearing by remember { mutableStateOf(false) }
    var ordered by remember(state.settings.voiceItems) { mutableStateOf(state.settings.voiceItems) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val closeSearch = { searching = false; query = ""; keyboard?.hide(); Unit }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    BackHandler(searching, closeSearch)
    val male = stringResource(R.string.tts_pool_male)
    val female = stringResource(R.string.tts_pool_female)
    val label: (String) -> String = { when (it) { SPEECH_POOL_MALE -> male; SPEECH_POOL_FEMALE -> female; else -> it } }
    val visible = remember(ordered, query, male, female) { filterSpeechVoicePool(ordered, query, label) }
    val pairs = remember(state.pairs, query, male, female) { filterSpeechVoicePairs(state.pairs, query, label) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        ordered = moveSpeechVoicePool(ordered, from.key as String, to.key as String)
    }
    val saveOrder = { if (ordered != state.settings.voiceItems) onOrder(ordered) }
    Scaffold(topBar = {
        TopAppBar(title = {
            if (searching) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus).testTag("speech-pool-search"),
                        singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        decorationBox = { field -> Box {
                            if (query.isEmpty()) Text(stringResource(R.string.tts_pool_search), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        } })
                    IconButton(onClick = { if (query.isNotEmpty()) query = "" else closeSearch() }) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(if (query.isNotEmpty()) R.string.search_clear_query else R.string.close))
                    }
                }
            }
            else SpeechPageTitle(stringResource(R.string.tts_pool_title))
        }, navigationIcon = { IconButton(onClick = { if (searching) closeSearch() else onBack() }) {
            Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
        } }, actions = {
            if (!searching) IconButton(onClick = { searching = true }) {
                Icon(painterResource(R.drawable.search_24px), stringResource(R.string.tts_pool_search))
            }
            if (tab == 0) IconButton(onClick = { onEditItem(null) }, enabled = !state.loading && !state.catalogLoading && !state.saving) {
                Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_pool_add))
            } else IconButton(onClick = { clearing = true }, enabled = state.pairs.isNotEmpty() && !state.saving) {
                Icon(painterResource(R.drawable.delete_forever_24px), stringResource(R.string.tts_pool_clear_pairs))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(tab, divider = {}) {
                listOf(R.string.tts_pool_tab, R.string.tts_pool_pairs).forEachIndexed { index, title ->
                    Tab(tab == index, { tab = index; query = "" }, text = { Text(stringResource(title)) })
                }
            }
            if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize().testTag("speech-voice-pool"), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                state.error?.let { error -> item { Text(stringResource(error), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) } }
                if (tab == 0) {
                    if (!state.loading && visible.isEmpty()) item { PoolEmpty(query, R.string.tts_pool_empty) }
                    visible.groupBy { it.group }.forEach { (group, voices) ->
                        val expanded = query.isNotEmpty() || group !in collapsed
                        val enabled = voices.count { it.activate }
                        val ids = voices.map { it.id }.toSet()
                        item(key = "group:$group") {
                            ReorderableItem(reorder, "group:$group") {
                                Row(Modifier.fillMaxWidth().longPressDraggableHandle(enabled = !state.saving, onDragStopped = saveOrder)
                                    .clickable { collapsed = if (group in collapsed) collapsed - group else collapsed + group }
                                    .padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(if (expanded) R.drawable.keyboard_arrow_up_24px else R.drawable.arrow_right_24px),
                                        stringResource(if (expanded) R.string.tts_replace_collapse else R.string.tts_replace_expand))
                                    Text(label(group), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TriStateCheckbox(when (enabled) { 0 -> ToggleableState.Off; voices.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                                        { onToggle(ids, enabled != voices.size) }, enabled = !state.saving,
                                        modifier = Modifier.semantics { contentDescription = label(group) })
                                    IconButton(onClick = { deletingItems = ids }, enabled = !state.saving) {
                                        Icon(painterResource(R.drawable.delete_forever_24px), stringResource(R.string.tts_manage_delete))
                                    }
                                }
                            }
                        }
                        if (expanded) items(voices, key = { "voice:${it.id}" }) { voice ->
                            ReorderableItem(reorder, "voice:${voice.id}") {
                                ListItem(headlineContent = { Text(voice.name.ifEmpty { voice.value.label }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(voice.activate, { onToggle(setOf(voice.id), it) }, enabled = !state.saving,
                                            modifier = Modifier.semantics { contentDescription = voice.name })
                                        PoolItemMenu(!state.saving, { onEditItem(voice) }, { deletingItems = setOf(voice.id) })
                                    } }, modifier = Modifier.longPressDraggableHandle(enabled = !state.saving, onDragStopped = saveOrder)
                                        .clickable(enabled = !state.saving) { onEditItem(voice) })
                            }
                        }
                    }
                } else {
                    if (!state.loading && pairs.isEmpty()) item { PoolEmpty(query, R.string.tts_pool_pairs_empty) }
                    pairs.groupBy { it.bookId }.forEach { (_, bookPairs) ->
                        val book = bookPairs.first()
                        item(key = "book:${book.bookId}") { Text(book.bookTitle.ifEmpty { book.bookId }, Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(bookPairs, key = { it.id }) { pair ->
                            ListItem(headlineContent = { Text(pair.person) },
                                supportingContent = { Text(listOf(label(pair.group), pair.value.label.ifEmpty { pair.value.key }).joinToString(" · ")) },
                                trailingContent = { PoolItemMenu(!state.saving, { onEditPair(pair) }, { deletingPairs = setOf(pair.id) },
                                    { onReassign(setOf(pair.id)) }) }, modifier = Modifier.clickable(enabled = !state.saving) { onEditPair(pair) })
                        }
                    }
                }
            }
        }
    }
    if (deletingItems.isNotEmpty() || deletingPairs.isNotEmpty() || clearing) {
        val close = { deletingItems = emptySet<String>(); deletingPairs = emptySet<String>(); clearing = false }
        AlertDialog(onDismissRequest = close, title = { Text(stringResource(if (clearing) R.string.tts_pool_clear_pairs else R.string.tts_manage_delete)) },
            text = { Text(stringResource(if (deletingItems.isNotEmpty()) R.string.tts_pool_delete_items_confirm else R.string.tts_pool_delete_pairs_confirm)) },
            confirmButton = { TextButton(onClick = {
                when { clearing -> onClearPairs(null); deletingItems.isNotEmpty() -> onDeleteItems(deletingItems); else -> onDeletePairs(deletingPairs) }
                close()
            }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun PoolEmpty(query: String, message: Int) {
    Text(stringResource(if (query.isEmpty()) message else R.string.search_no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PoolItemMenu(enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onReassign: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) { Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_source_actions)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.tts_pool_edit)) }, onClick = { menu = false; onEdit() })
            onReassign?.let { action -> DropdownMenuItem(text = { Text(stringResource(R.string.tts_pool_reassign)) }, onClick = { menu = false; action() }) }
            DropdownMenuItem(text = { Text(stringResource(R.string.tts_manage_delete)) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
internal fun speechPoolGroupLabel(group: String): String = when (group) {
    SPEECH_POOL_MALE -> stringResource(R.string.tts_pool_male)
    SPEECH_POOL_FEMALE -> stringResource(R.string.tts_pool_female)
    else -> group
}
