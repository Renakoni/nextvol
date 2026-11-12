package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.ManagedSource
import indi.renakoni.nextvol.data.web.rules.SourceGroup

@Composable
internal fun SourceManagementAction(title: String, icon: Int, enabled: Boolean, modifier: Modifier,
    onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 100.dp),
        shape = MaterialTheme.shapes.extraLarge, contentPadding = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon), null)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
internal fun SourceGroupFilters(groups: List<SourceGroup>, installed: List<ManagedSource>,
    selected: String?, onSelect: (String?) -> Unit) {
    val ungroupedCount = installed.count { it.preferences.groupIds.isEmpty() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") { FilterChip(selected == null, { onSelect(null) },
            label = { Text(stringResource(R.string.sources_filter_all)) }) }
        if (ungroupedCount > 0) item(key = "ungrouped") {
            SourceGroupFilter(stringResource(R.string.source_group_ungrouped), ungroupedCount, selected == "") { onSelect("") }
        }
        items(groups, key = { it.id }) { group ->
            SourceGroupFilter(group.name, installed.count { group.id in it.preferences.groupIds }, selected == group.id) { onSelect(group.id) }
        }
    }
}

@Composable
private fun SourceGroupFilter(name: String, count: Int, selected: Boolean, onSelect: () -> Unit) {
    val memberCount = pluralStringResource(R.plurals.source_group_member_count, count, count)
    FilterChip(selected, onSelect, modifier = Modifier.semantics { contentDescription = "$name, $memberCount" },
        label = { Text(name, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (count > 0) ({
            Badge(containerColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                Text(count.toString())
            }
        }) else null)
}

@Composable
internal fun SourceGroupsDialog(groups: List<SourceGroup>, installed: List<ManagedSource>, busy: Boolean,
    message: Int?, revision: Long, members: Set<String>, onDismiss: () -> Unit,
    onSave: (Set<String>, Set<String>) -> Unit, onCreate: (String, Set<String>, Set<String>) -> Unit) {
    var creating by rememberSaveable(revision) { mutableStateOf(false) }
    var added by rememberSaveable(revision) { mutableStateOf(emptyList<String>()) }
    var removed by rememberSaveable(revision) { mutableStateOf(emptyList<String>()) }
    val sources = installed.filter { it.key in members }
    if (creating) {
        SourceGroupNameDialog(groups, null, busy, message, onDismiss = { creating = false },
            onSave = { onCreate(it, added.toSet(), removed.toSet()) })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.source_group_move)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.source_group_members_help), style = MaterialTheme.typography.bodyMedium)
                if (message == R.string.sources_action_failed) Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(groups, key = { it.id }) { group ->
                        val count = sources.count { group.id in it.preferences.groupIds }
                        val selected = when {
                            group.id in added -> ToggleableState.On
                            group.id in removed || count == 0 -> ToggleableState.Off
                            count == sources.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        ListItem(headlineContent = { Text(group.name) },
                            supportingContent = if (selected == ToggleableState.Indeterminate) ({
                                Text(stringResource(R.string.source_group_partial_members))
                            }) else null,
                            leadingContent = { TriStateCheckbox(selected, onClick = null, enabled = !busy) },
                            modifier = Modifier.triStateToggleable(selected, enabled = !busy, role = Role.Checkbox) {
                                if (selected == ToggleableState.On) {
                                    added = added - group.id; removed = (removed + group.id).distinct()
                                } else {
                                    added = (added + group.id).distinct(); removed = removed - group.id
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                }
                OutlinedButton(onClick = { creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.add_circle_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.source_group_create))
                }
            }
        }, confirmButton = {
            TextButton(onClick = { onSave(added.toSet(), removed.toSet()) },
                enabled = !busy && sources.isNotEmpty() && (added.isNotEmpty() || removed.isNotEmpty())) {
                Text(stringResource(R.string.source_group_save))
            }
        }, dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
        })
}

@Composable
internal fun SourceGroupNameDialog(groups: List<SourceGroup>, group: SourceGroup?, busy: Boolean, message: Int?,
    onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    val normalized = name.trim()
    val valid = SourceGroup.validName(normalized) && groups.none { it.id != group?.id && it.name.equals(normalized, true) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(if (group == null) R.string.source_group_create else R.string.source_group_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message == R.string.sources_action_failed) Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.source_group_name)) }, isError = name.isNotEmpty() && !valid)
                Text(stringResource(R.string.source_group_name_help), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = valid && !busy, onClick = { onSave(normalized) }) { Text(stringResource(R.string.source_group_save)) }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        })
}

@Composable
internal fun SourceGroupDeleteDialog(group: SourceGroup, busy: Boolean, message: Int?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.source_group_delete_title, group.name)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message == R.string.sources_action_failed) Text(stringResource(message), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.source_group_delete_help))
        } },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDelete) {
                Text(stringResource(R.string.source_group_delete), color = MaterialTheme.colorScheme.error)
            }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        })
}
