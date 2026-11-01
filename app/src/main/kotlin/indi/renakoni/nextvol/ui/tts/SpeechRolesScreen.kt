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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun SpeechRolesScreen(state: SpeechRolesState, onBack: () -> Unit, onEdit: (String?) -> Unit,
    onToggle: (Set<String>, Boolean) -> Unit, onOrder: (List<SpeechRoleRule>) -> Unit,
    onDelete: (Set<String>) -> Unit, onRename: (Set<String>, String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var excluded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var ordered by remember(state.settings.roleRules) { mutableStateOf(state.settings.roleRules) }
    var deleting by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var renaming by remember { mutableStateOf<Set<String>?>(null) }
    var groupName by remember { mutableStateOf("") }
    val closeSearch = {
        if (query.isNotEmpty()) query = ""
        else { searching = false; submitted = ""; excluded = emptyList() }
    }
    BackHandler(searching, closeSearch)
    // MultiTTS keeps the submitted result until the next search; edits and new roles stay visible.
    val visible = ordered.filterNot { it.id in excluded }
    val groups = visible.groupBy { it.group }
    val open = expanded ?: groups.keys.take(1)
    val lazyState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyState) { from, to ->
        val first = from.key as String
        val second = to.key as String
        val moved = moveSpeechRoles(ordered, first, second)
        if (moved != ordered) {
            if (first.startsWith("group:")) expanded = open - first.removePrefix("group:") - second.removePrefix("group:")
            ordered = moved
        }
    }
    val deleteLabel = stringResource(R.string.tts_replace_delete)
    val defaultGroup = stringResource(R.string.tts_replace_ungrouped)
    Scaffold(topBar = {
        SpeechRolesTopBar(query, { query = it }, searching, { searching = true }, closeSearch,
            onSubmit = {
                if (query.isNotBlank()) {
                    submitted = query
                    val matching = filterSpeechRoles(ordered, query).map { it.id }.toSet()
                    excluded = ordered.map { it.id }.filterNot { it in matching }
                }
            }, suggestions = speechRoleSearchSuggestions(ordered), onBack = onBack,
            busy = state.loading || state.catalogLoading || state.saving, onAdd = { onEdit(null) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-roles-list"), state = lazyState,
            contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item {
                Text(stringResource(error.messageResource), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            } }
            if (!state.loading && visible.isEmpty()) item {
                Text(stringResource(if (submitted.isEmpty()) R.string.tts_role_empty else R.string.search_no_results),
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            groups.forEach { (group, rules) ->
                val ids = rules.map { it.id }.toSet()
                val label = group.ifEmpty { defaultGroup }
                val enabledCount = rules.count { it.activate }
                item(key = "group:$group") {
                    ReorderableItem(reorderState, key = "group:$group") { dragging ->
                        RoleSwipeRow({ deleting = label to ids }) {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                                shadowElevation = if (dragging) 4.dp else 0.dp) {
                                Row(Modifier.fillMaxWidth().longPressDraggableHandle(enabled = !state.saving,
                                    onDragStopped = { if (ordered != state.settings.roleRules) onOrder(ordered) })
                                    .clickable { expanded = if (group in open) open - group else open + group }
                                    .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { deleting = label to ids; true }) }
                                    .padding(start = 20.dp, end = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(if (group in open) R.drawable.keyboard_arrow_up_24px else R.drawable.arrow_right_24px),
                                        stringResource(if (group in open) R.string.tts_replace_collapse else R.string.tts_replace_expand))
                                    Text(stringResource(R.string.tts_role_group_count, label, enabledCount, rules.size),
                                        Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                                    IconButton(onClick = { groupName = label; renaming = ids }, enabled = !state.saving) {
                                        Icon(painterResource(R.drawable.edit_square_24px), stringResource(R.string.tts_role_edit_group))
                                    }
                                    Checkbox(enabledCount != 0, { onToggle(ids, it) }, enabled = !state.saving,
                                        modifier = Modifier.semantics { contentDescription = label })
                                }
                            }
                        }
                    }
                }
                if (group in open) items(rules, key = { "role:${it.id}" }) { rule ->
                    ReorderableItem(reorderState, key = "role:${rule.id}") { dragging ->
                        RoleSwipeRow({ deleting = rule.label to setOf(rule.id) }) {
                            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = if (dragging) 4.dp else 0.dp) {
                                Row(Modifier.fillMaxWidth().longPressDraggableHandle(enabled = !state.saving,
                                    onDragStopped = { if (ordered != state.settings.roleRules) onOrder(ordered) })
                                    // Original row toggles only its UI cache; persist the same action like its checkbox.
                                    .clickable(enabled = !state.saving) { onToggle(setOf(rule.id), !rule.activate) }
                                    .testTag("speech-role-${rule.id}")
                                    .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) {
                                        deleting = rule.label to setOf(rule.id); true
                                    }) }
                                    .padding(start = 24.dp, end = 12.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(rule.label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(rule.speaker?.label ?: stringResource(R.string.tts_role_voice_missing), Modifier.weight(1.5f),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    IconButton(onClick = { onEdit(rule.id) }, enabled = !state.saving && !state.catalogLoading) {
                                        Icon(painterResource(R.drawable.edit_square_24px), stringResource(R.string.tts_replace_edit))
                                    }
                                    Checkbox(rule.activate, { onToggle(setOf(rule.id), it) }, enabled = !state.saving,
                                        modifier = Modifier.semantics { contentDescription = rule.label })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { (name, ids) ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.tts_replace_delete)) },
            text = { Text(stringResource(R.string.tts_role_delete_confirm, name)) },
            confirmButton = { TextButton(onClick = { onDelete(ids); deleting = null }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
    renaming?.let { ids ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.tts_role_edit_group)) },
            text = { ReplacementField(groupName, { groupName = it }, stringResource(R.string.tts_replace_group), singleLine = true) },
            confirmButton = { TextButton(onClick = { onRename(ids, groupName); renaming = null }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleSwipeRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it != SwipeToDismissBoxValue.Settled) onDelete()
        false
    })
    SwipeToDismissBox(swipe, backgroundContent = {
        Box(Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
            Icon(painterResource(R.drawable.delete_forever_24px), null, tint = MaterialTheme.colorScheme.error)
        }
    }) { content() }
}

@Composable
internal fun SpeechRoleError(failure: SpeechRoleFailure) {
    val reason = stringResource(when (failure.problem) {
        SpeechReplacementProblem.Pattern -> R.string.tts_replace_error_pattern
        SpeechReplacementProblem.Replacement -> R.string.tts_replace_error_target
        SpeechReplacementProblem.Budget -> R.string.tts_replace_error_budget
    })
    Text(stringResource(R.string.tts_replace_error_rule, failure.ruleName, reason), color = MaterialTheme.colorScheme.error)
}
