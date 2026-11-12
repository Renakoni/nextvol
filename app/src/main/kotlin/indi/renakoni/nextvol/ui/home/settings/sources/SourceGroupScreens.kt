package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.ManagedSource

@Composable
internal fun SourceGroupsScreen(state: SourceManagementState, onOpen: (String) -> Unit, onCreate: () -> Unit,
    modifier: Modifier = Modifier) {
    val members = state.addedSources.flatMap { source -> source.preferences.groupIds.map { it to source } }
        .groupBy({ it.first }, { it.second })
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.source_groups_help), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            FilledTonalButton(onClick = onCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.add_circle_24px), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.source_group_create))
            }
        }
        if (state.message == R.string.sources_action_failed) item {
            Text(stringResource(state.message), color = MaterialTheme.colorScheme.error)
        }
        items(state.groups, key = { it.id }) { group ->
            SourceGroupRow(group.name, members[group.id].orEmpty(), state,
                stringResource(R.string.source_group_empty_help)) { onOpen(group.id) }
        }
        if (state.groups.isEmpty()) item {
            Text(stringResource(R.string.source_groups_empty), Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourceGroupRow(name: String, members: List<ManagedSource>, state: SourceManagementState,
    emptySummary: String, onClick: () -> Unit) {
    val preview = members.take(3).joinToString(" · ") { it.name }
    ListItem(headlineContent = { Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(preview.ifEmpty { emptySummary }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(painterResource(R.drawable.view_list_24px), null, Modifier.padding(10.dp).size(22.dp))
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pluralStringResource(R.plurals.source_group_member_count, members.size, members.size),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(enabled = !state.busy, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
}

@Composable
internal fun SourceGroupMembersScreen(state: SourceManagementState, groupId: String, adding: Boolean,
    selecting: Boolean, selected: List<String>, onSelection: (List<String>) -> Unit, onSelect: () -> Unit,
    onAdd: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val sources = state.addedSources.filter { (groupId in it.preferences.groupIds) != adding }
    val selectionMode = adding || selecting
    val visible = sources.filter { source ->
        source.name.contains(query.trim(), true) || source.host.contains(query.trim(), true)
    }
    val allVisibleSelected = visible.isNotEmpty() && visible.all { it.key in selected }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (adding) Text(stringResource(R.string.source_group_add_help), Modifier.padding(top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sources.isNotEmpty()) TextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
            singleLine = true, enabled = !state.busy,
            placeholder = { Text(stringResource(R.string.source_group_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.search_24px), null) },
            trailingIcon = if (query.isEmpty()) null else ({
                IconButton(onClick = { query = "" }, enabled = !state.busy) {
                    Icon(painterResource(R.drawable.close_24px), stringResource(R.string.source_group_clear_search))
                }
            }), shape = MaterialTheme.shapes.large,
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                val selection = when {
                    allVisibleSelected -> ToggleableState.On
                    visible.any { it.key in selected } -> ToggleableState.Indeterminate
                    else -> ToggleableState.Off
                }
                Row(Modifier.heightIn(min = 48.dp).triStateToggleable(selection, enabled = !state.busy && visible.isNotEmpty(),
                    role = Role.Checkbox) {
                    val ids = visible.map { it.key }
                    onSelection(if (allVisibleSelected) selected - ids.toSet() else (selected + ids).distinct())
                }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(selection, onClick = null, enabled = !state.busy && visible.isNotEmpty(),
                        modifier = Modifier.padding(12.dp))
                    Text(stringResource(R.string.source_group_select_all), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(pluralStringResource(R.plurals.source_group_member_count, visible.size, visible.size),
                Modifier.weight(1f).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!selectionMode) {
                TextButton(onClick = onSelect, enabled = !state.busy && visible.isNotEmpty()) {
                    Text(stringResource(R.string.source_group_select_members))
                }
                FilledTonalButton(onClick = onAdd, enabled = !state.busy) {
                    Icon(painterResource(R.drawable.library_add_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.source_group_add_sources))
                }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.message == R.string.sources_action_failed) Text(stringResource(state.message),
            Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(visible, key = { _, source -> source.key }) { index, source ->
                val checked = source.key in selected
                val memberships = state.groups.filter { it.id in source.preferences.groupIds }.joinToString(" · ") { it.name }
                val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 0.dp, topEnd = if (index == 0) 20.dp else 0.dp,
                    bottomStart = if (index == visible.lastIndex) 20.dp else 0.dp, bottomEnd = if (index == visible.lastIndex) 20.dp else 0.dp)
                val selectSource = { onSelection(if (checked) selected - source.key else selected + source.key) }
                Column(Modifier.clip(shape)) {
                    ListItem(headlineContent = { Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(source.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (adding && memberships.isNotEmpty()) Text(memberships, style = MaterialTheme.typography.labelMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } },
                        leadingContent = {
                            if (selectionMode) Checkbox(checked, onCheckedChange = null, enabled = !state.busy)
                            else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Icon(painterResource(R.drawable.language_24px), null, Modifier.padding(10.dp).size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        modifier = if (selectionMode) Modifier.toggleable(checked, enabled = !state.busy, role = Role.Checkbox) { selectSource() }
                            else Modifier.combinedClickable(enabled = !state.busy, onClick = { onSelect(); selectSource() },
                                onLongClick = { onSelect(); selectSource() }),
                        colors = ListItemDefaults.colors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer))
                    if (index != visible.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
            if (visible.isEmpty()) item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.view_list_24px), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(when {
                            query.isNotBlank() && sources.isNotEmpty() -> R.string.source_group_no_matches
                            state.addedSources.isEmpty() -> R.string.source_group_no_sources
                            adding -> R.string.source_group_candidates_empty
                            else -> R.string.source_group_members_empty
                        }), style = MaterialTheme.typography.titleMedium)
                        if (!adding && sources.isEmpty() && state.addedSources.isNotEmpty()) {
                            Text(stringResource(R.string.source_group_empty_help), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
