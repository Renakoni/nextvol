package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechReplacementProblem
import indi.renakoni.nextvol.tts.SpeechRoleFailure
import indi.renakoni.nextvol.tts.SpeechRoleRule
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun SpeechRolesScreen(
    state: SpeechRolesState,
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onToggle: (Set<String>, Boolean) -> Unit,
    onOrder: (List<SpeechRoleRule>) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onRename: (Set<String>, String) -> Unit,
) {
    val tokens = NextVolDesign.tokens
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
        if (query.isNotEmpty()) {
            query = ""
        } else {
            searching = false
            submitted = ""
            excluded = emptyList()
        }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SpeechRolesTopBar(
                query = query,
                onQuery = { query = it },
                searching = searching,
                onSearch = { searching = true },
                onCloseSearch = closeSearch,
                onSubmit = {
                    if (query.isNotBlank()) {
                        submitted = query
                        val matching = filterSpeechRoles(ordered, query).map { it.id }.toSet()
                        excluded = ordered.map { it.id }.filterNot { it in matching }
                    }
                },
                suggestions = speechRoleSearchSuggestions(ordered),
                onBack = onBack,
                busy = state.loading || state.catalogLoading || state.saving,
                onAdd = { onEdit(null) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("speech-roles-list"),
            state = lazyState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
            }
            state.error?.let { error ->
                item {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!state.loading && visible.isEmpty()) {
                item {
                    EmptyPage(
                        modifier = Modifier.fillParentMaxHeight(0.75f),
                        icon = painterResource(R.drawable.person_edit_24px),
                        title = stringResource(if (submitted.isEmpty()) R.string.tts_roles else R.string.search_no_results),
                        description = if (submitted.isEmpty()) stringResource(R.string.tts_role_empty) else "",
                    )
                }
            }
            groups.forEach { (group, rules) ->
                val ids = rules.map { it.id }.toSet()
                val label = group.ifEmpty { defaultGroup }
                val enabledCount = rules.count { it.activate }
                val isOpen = group in open
                item(key = "group:$group") {
                    ReorderableItem(reorderState, key = "group:$group") { dragging ->
                        val arrowRotation by animateFloatAsState(if (isOpen) 0f else 180f, label = "roleGroupArrow")
                        RoleSwipeRow({ deleting = label to ids }) {
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
                                        .longPressDraggableHandle(
                                            enabled = !state.saving,
                                            onDragStopped = { if (ordered != state.settings.roleRules) onOrder(ordered) },
                                        )
                                        .clickable { expanded = if (isOpen) open - group else open + group }
                                        .semantics {
                                            customActions = listOf(CustomAccessibilityAction(deleteLabel) {
                                                deleting = label to ids
                                                true
                                            })
                                        }
                                        .padding(start = 14.dp, end = 6.dp)
                                        .heightIn(min = 54.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                                        contentDescription = stringResource(
                                            if (isOpen) R.string.tts_replace_collapse else R.string.tts_replace_expand,
                                        ),
                                        modifier = Modifier
                                            .size(20.dp)
                                            .rotate(arrowRotation),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = stringResource(R.string.tts_role_group_count, label, enabledCount, rules.size),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.W600,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = {
                                            groupName = label
                                            renaming = ids
                                        },
                                        enabled = !state.saving,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.edit_square_24px),
                                            contentDescription = stringResource(R.string.tts_role_edit_group),
                                            modifier = Modifier.size(19.dp),
                                        )
                                    }
                                    Checkbox(
                                        checked = enabledCount != 0,
                                        onCheckedChange = { onToggle(ids, it) },
                                        enabled = !state.saving,
                                        modifier = Modifier.semantics { contentDescription = label },
                                    )
                                }
                            }
                        }
                    }
                }
                if (isOpen) {
                    items(rules, key = { "role:${it.id}" }) { rule ->
                        ReorderableItem(reorderState, key = "role:${rule.id}") { dragging ->
                            RoleSwipeRow({ deleting = rule.label to setOf(rule.id) }) {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 16.dp,
                                    borderColor = if (dragging) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                                    borderWidth = if (dragging) 1.25.dp else 0.75.dp,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .longPressDraggableHandle(
                                                enabled = !state.saving,
                                                onDragStopped = { if (ordered != state.settings.roleRules) onOrder(ordered) },
                                            )
                                            // Original row toggles only its UI cache; persist the same action like its checkbox.
                                            .clickable(enabled = !state.saving) { onToggle(setOf(rule.id), !rule.activate) }
                                            .testTag("speech-role-${rule.id}")
                                            .semantics {
                                                customActions = listOf(CustomAccessibilityAction(deleteLabel) {
                                                    deleting = rule.label to setOf(rule.id)
                                                    true
                                                })
                                            }
                                            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
                                            .heightIn(min = 56.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                        ) {
                                            Text(
                                                text = rule.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.W500,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = rule.speaker?.label ?: stringResource(R.string.tts_role_voice_missing),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (rule.speaker != null) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        IconButton(
                                            onClick = { onEdit(rule.id) },
                                            enabled = !state.saving && !state.catalogLoading,
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.edit_square_24px),
                                                contentDescription = stringResource(R.string.tts_replace_edit),
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }
                                        Checkbox(
                                            checked = rule.activate,
                                            onCheckedChange = { onToggle(setOf(rule.id), it) },
                                            enabled = !state.saving,
                                            modifier = Modifier.semantics { contentDescription = rule.label },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { (name, ids) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.tts_replace_delete), fontWeight = FontWeight.W600) },
            text = {
                Text(
                    text = stringResource(R.string.tts_role_delete_confirm, name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(ids)
                        deleting = null
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    renaming?.let { ids ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = { Text(stringResource(R.string.tts_role_edit_group), fontWeight = FontWeight.W600) },
            text = {
                ReplacementField(
                    value = groupName,
                    onChange = { groupName = it },
                    label = stringResource(R.string.tts_replace_group),
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRename(ids, groupName)
                        renaming = null
                    },
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleSwipeRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it != SwipeToDismissBoxValue.Settled) onDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                Icon(painterResource(R.drawable.delete_forever_24px), null, tint = MaterialTheme.colorScheme.error)
            }
        },
    ) { content() }
}

@Composable
internal fun SpeechRoleError(failure: SpeechRoleFailure) {
    val reason = stringResource(
        when (failure.problem) {
            SpeechReplacementProblem.Pattern -> R.string.tts_replace_error_pattern
            SpeechReplacementProblem.Replacement -> R.string.tts_replace_error_target
            SpeechReplacementProblem.Budget -> R.string.tts_replace_error_budget
        },
    )
    Text(stringResource(R.string.tts_replace_error_rule, failure.ruleName, reason), color = MaterialTheme.colorScheme.error)
}
