package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SPEECH_POOL_FEMALE
import indi.renakoni.nextvol.tts.SPEECH_POOL_MALE
import indi.renakoni.nextvol.tts.SpeechVoiceItem
import indi.renakoni.nextvol.tts.SpeechVoicePair
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechVoicePoolScreen(
    state: SpeechVoicePoolState,
    onBack: () -> Unit,
    onEditItem: (SpeechVoiceItem?) -> Unit,
    onEditPair: (SpeechVoicePair) -> Unit,
    onToggle: (Set<String>, Boolean) -> Unit,
    onOrder: (List<SpeechVoiceItem>) -> Unit,
    onDeleteItems: (Set<String>) -> Unit,
    onDeletePairs: (Set<String>) -> Unit,
    onReassign: (Set<String>) -> Unit,
    onClearPairs: (String?) -> Unit,
) {
    val tokens = NextVolDesign.tokens
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
    val closeSearch = {
        searching = false
        query = ""
        keyboard?.hide()
        Unit
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    BackHandler(searching, closeSearch)
    val male = stringResource(R.string.tts_pool_male)
    val female = stringResource(R.string.tts_pool_female)
    val label: (String) -> String = {
        when (it) {
            SPEECH_POOL_MALE -> male
            SPEECH_POOL_FEMALE -> female
            else -> it
        }
    }
    val search = remember(query) { hnovel.speech.SpeechSearchQuery(query) }
    val visible = remember(ordered, search, male, female) { filterSpeechVoicePool(ordered, search, label) }
    val pairs = remember(state.pairs, search, male, female) { filterSpeechVoicePairs(state.pairs, search, label) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        ordered = moveSpeechVoicePool(ordered, from.key as String, to.key as String)
    }
    val saveOrder = { if (ordered != state.settings.voiceItems) onOrder(ordered) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = tokens.topBarFallbackSurface,
                ),
                title = {
                    if (searching) {
                        Surface(
                            shape = CircleShape,
                            color = tokens.cardSurface,
                            modifier = Modifier.border(0.75.dp, tokens.cardBorder, CircleShape),
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 42.dp)
                                    .padding(start = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focus)
                                        .testTag("speech-pool-search"),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                    decorationBox = { field ->
                                        Box {
                                            if (query.isEmpty()) {
                                                Text(
                                                    text = stringResource(R.string.tts_pool_search),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            field()
                                        }
                                    },
                                )
                                IconButton(onClick = { if (query.isNotEmpty()) query = "" else closeSearch() }) {
                                    Icon(
                                        painter = painterResource(R.drawable.close_24px),
                                        contentDescription = stringResource(if (query.isNotEmpty()) R.string.search_clear_query else R.string.close),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        SpeechPageTitle(stringResource(R.string.tts_pool_title))
                    }
                },
                navigationIcon = {
                    EditorialIconButton(
                        onClick = { if (searching) closeSearch() else onBack() },
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
                actions = {
                    if (!searching) {
                        EditorialIconButton(
                            onClick = { searching = true },
                            painter = painterResource(R.drawable.search_24px),
                            contentDescription = stringResource(R.string.tts_pool_search),
                        )
                    }
                    if (tab == 0) {
                        EditorialIconButton(
                            onClick = { onEditItem(null) },
                            enabled = !state.loading && !state.catalogLoading && !state.saving,
                            painter = painterResource(R.drawable.add_circle_24px),
                            contentDescription = stringResource(R.string.tts_pool_add),
                        )
                    } else {
                        EditorialIconButton(
                            onClick = { clearing = true },
                            enabled = state.pairs.isNotEmpty() && !state.saving,
                            painter = painterResource(R.drawable.delete_forever_24px),
                            contentDescription = stringResource(R.string.tts_pool_clear_pairs),
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            EditorialPillTabRow(
                tabs = listOf(
                    stringResource(R.string.tts_pool_tab),
                    stringResource(R.string.tts_pool_pairs),
                ),
                selectedTabIndex = tab,
                onTabSelected = {
                    tab = it
                    query = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.loading || state.saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(CircleShape))
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("speech-voice-pool"),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.error?.let { error ->
                    item {
                        EditorialCard(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (tab == 0) {
                    if (!state.loading && visible.isEmpty()) {
                        item { PoolEmpty(query, R.string.tts_pool_empty, Modifier.fillParentMaxHeight(0.75f)) }
                    }
                    visible.groupBy { it.group }.forEach { (group, voices) ->
                        val expanded = query.isNotEmpty() || group !in collapsed
                        val enabledCount = voices.count { it.activate }
                        val ids = voices.map { it.id }.toSet()
                        item(key = "group:$group") {
                            ReorderableItem(reorder, "group:$group") { dragging ->
                                val arrowRotation by animateFloatAsState(if (expanded) 0f else 180f, label = "poolGroupArrow")
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 16.dp,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    borderColor = if (dragging) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                                    borderWidth = if (dragging) 1.25.dp else 0.75.dp,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .longPressDraggableHandle(enabled = !state.saving, onDragStopped = saveOrder)
                                            .clickable { collapsed = if (group in collapsed) collapsed - group else collapsed + group }
                                            .padding(start = 14.dp, end = 4.dp)
                                            .heightIn(min = 52.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                                            contentDescription = stringResource(if (expanded) R.string.tts_replace_collapse else R.string.tts_replace_expand),
                                            modifier = Modifier
                                                .size(20.dp)
                                                .rotate(arrowRotation),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = label(group),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.W600,
                                        )
                                        TriStateCheckbox(
                                            state = when (enabledCount) {
                                                0 -> ToggleableState.Off
                                                voices.size -> ToggleableState.On
                                                else -> ToggleableState.Indeterminate
                                            },
                                            onClick = { onToggle(ids, enabledCount != voices.size) },
                                            enabled = !state.saving,
                                            modifier = Modifier.semantics { contentDescription = label(group) },
                                        )
                                        IconButton(onClick = { deletingItems = ids }, enabled = !state.saving) {
                                            Icon(
                                                painter = painterResource(R.drawable.delete_forever_24px),
                                                contentDescription = stringResource(R.string.tts_manage_delete),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (expanded) {
                            items(voices, key = { "voice:${it.id}" }) { voice ->
                                ReorderableItem(reorder, "voice:${voice.id}") { dragging ->
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 16.dp,
                                        borderColor = if (dragging) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                                        borderWidth = if (dragging) 1.25.dp else 0.75.dp,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .longPressDraggableHandle(enabled = !state.saving, onDragStopped = saveOrder)
                                                .clickable(enabled = !state.saving) { onEditItem(voice) }
                                                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                                                .heightIn(min = 52.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = voice.name.ifEmpty { voice.value.label },
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.W500,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Checkbox(
                                                checked = voice.activate,
                                                onCheckedChange = { onToggle(setOf(voice.id), it) },
                                                enabled = !state.saving,
                                                modifier = Modifier.semantics { contentDescription = voice.name },
                                            )
                                            PoolItemMenu(!state.saving, { onEditItem(voice) }, { deletingItems = setOf(voice.id) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (!state.loading && pairs.isEmpty()) {
                        item { PoolEmpty(query, R.string.tts_pool_pairs_empty, Modifier.fillParentMaxHeight(0.75f)) }
                    }
                    pairs.groupBy { it.bookId }.forEach { (_, bookPairs) ->
                        val book = bookPairs.first()
                        item(key = "book:${book.bookId}") {
                            SectionHeader(
                                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                                text = book.bookTitle.ifEmpty { book.bookId },
                            )
                        }
                        items(bookPairs, key = { it.id }) { pair ->
                            EditorialCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 16.dp,
                                onClick = { onEditPair(pair) },
                                enabled = !state.saving,
                                contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = pair.person,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.W600,
                                        )
                                        Text(
                                            text = listOf(label(pair.group), pair.value.label.ifEmpty { pair.value.key })
                                                .joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    PoolItemMenu(
                                        enabled = !state.saving,
                                        onEdit = { onEditPair(pair) },
                                        onDelete = { deletingPairs = setOf(pair.id) },
                                        onReassign = { onReassign(setOf(pair.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (deletingItems.isNotEmpty() || deletingPairs.isNotEmpty() || clearing) {
        val close = {
            deletingItems = emptySet()
            deletingPairs = emptySet()
            clearing = false
        }
        AlertDialog(
            onDismissRequest = close,
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = {
                Text(
                    stringResource(if (clearing) R.string.tts_pool_clear_pairs else R.string.tts_manage_delete),
                    fontWeight = FontWeight.W600,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        if (deletingItems.isNotEmpty()) R.string.tts_pool_delete_items_confirm
                        else R.string.tts_pool_delete_pairs_confirm,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        when {
                            clearing -> onClearPairs(null)
                            deletingItems.isNotEmpty() -> onDeleteItems(deletingItems)
                            else -> onDeletePairs(deletingPairs)
                        }
                        close()
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PoolEmpty(query: String, message: Int, modifier: Modifier = Modifier) {
    EmptyPage(
        modifier = modifier,
        icon = painterResource(if (query.isEmpty()) R.drawable.tts_text_multiple_voices else R.drawable.search_24px),
        title = stringResource(if (query.isEmpty()) R.string.tts_pool_title else R.string.search_no_results),
        description = if (query.isEmpty()) stringResource(message) else "",
    )
}

@Composable
private fun PoolItemMenu(
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReassign: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) {
            Icon(
                painter = painterResource(R.drawable.more_vert_24px),
                contentDescription = stringResource(R.string.tts_source_actions),
            )
        }
        EditorialPopupMenu(expanded = menu, onDismissRequest = { menu = false }) {
            EditorialPopupMenuItem(
                text = stringResource(R.string.tts_pool_edit),
                leadingIcon = painterResource(R.drawable.edit_square_24px),
                onClick = {
                    menu = false
                    onEdit()
                },
            )
            onReassign?.let { action ->
                EditorialPopupMenuItem(
                    text = stringResource(R.string.tts_pool_reassign),
                    leadingIcon = painterResource(R.drawable.autorenew_24px),
                    onClick = {
                        menu = false
                        action()
                    },
                )
            }
            EditorialHairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            EditorialPopupMenuItem(
                text = stringResource(R.string.tts_manage_delete),
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
internal fun speechPoolGroupLabel(group: String): String = when (group) {
    SPEECH_POOL_MALE -> stringResource(R.string.tts_pool_male)
    SPEECH_POOL_FEMALE -> stringResource(R.string.tts_pool_female)
    else -> group
}
