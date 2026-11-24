package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.ManagedSource
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge

@Composable
internal fun SourceGroupsScreen(
    state: SourceManagementState,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val members = state.addedSources.flatMap { source -> source.preferences.groupIds.map { it to source } }
        .groupBy({ it.first }, { it.second })
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "groups_help") {
            Text(
                text = stringResource(R.string.source_groups_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item(key = "groups_create") {
            FilledTonalButton(
                onClick = onCreate,
                enabled = !state.busy,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.add_circle_24px), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.source_group_create), fontWeight = FontWeight.W600)
            }
        }
        if (state.message == R.string.sources_action_failed) {
            item(key = "groups_error") {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Text(stringResource(state.message), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        items(state.groups, key = { it.id }) { group ->
            SourceGroupRow(
                name = group.name,
                members = members[group.id].orEmpty(),
                state = state,
                emptySummary = stringResource(R.string.source_group_empty_help),
            ) { onOpen(group.id) }
        }
        if (state.groups.isEmpty()) {
            item(key = "groups_empty") {
                EmptyPage(
                    icon = painterResource(R.drawable.view_list_24px),
                    title = stringResource(R.string.source_groups_empty),
                    description = "",
                )
            }
        }
    }
}

@Composable
private fun SourceGroupRow(
    name: String,
    members: List<ManagedSource>,
    state: SourceManagementState,
    emptySummary: String,
    onClick: () -> Unit,
) {
    val preview = members.take(3).joinToString(" · ") { it.name }
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = !state.busy,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EditorialIconBadge(
                painter = painterResource(R.drawable.view_list_24px),
                size = 40.dp,
                iconSize = 20.dp,
                cornerRadius = 12.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W600,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview.ifEmpty { emptySummary },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = pluralStringResource(R.plurals.source_group_member_count, members.size, members.size),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.W600,
                )
                Icon(
                    painter = painterResource(R.drawable.arrow_forward_ios_24px),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun SourceGroupMembersScreen(
    state: SourceManagementState,
    groupId: String,
    adding: Boolean,
    selecting: Boolean,
    selected: List<String>,
    onSelection: (List<String>) -> Unit,
    onSelect: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    var query by rememberSaveable { mutableStateOf("") }
    val sources = state.addedSources.filter { (groupId in it.preferences.groupIds) != adding }
    val selectionMode = adding || selecting
    val visible = sources.filter { source ->
        source.name.contains(query.trim(), true) || source.host.contains(query.trim(), true)
    }
    val allVisibleSelected = visible.isNotEmpty() && visible.all { it.key in selected }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        if (adding) {
            Text(
                text = stringResource(R.string.source_group_add_help),
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (sources.isNotEmpty()) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .border(0.75.dp, tokens.cardBorder, CircleShape),
                singleLine = true,
                enabled = !state.busy,
                placeholder = { Text(stringResource(R.string.source_group_search)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = if (query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { query = "" }, enabled = !state.busy) {
                            Icon(
                                painter = painterResource(R.drawable.close_24px),
                                contentDescription = stringResource(R.string.source_group_clear_search),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = tokens.cardSurface,
                    unfocusedContainerColor = tokens.cardSurface,
                ),
            )
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                val selection = when {
                    allVisibleSelected -> ToggleableState.On
                    visible.any { it.key in selected } -> ToggleableState.Indeterminate
                    else -> ToggleableState.Off
                }
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .triStateToggleable(
                            state = selection,
                            enabled = !state.busy && visible.isNotEmpty(),
                            role = Role.Checkbox,
                        ) {
                            val ids = visible.map { it.key }
                            onSelection(if (allVisibleSelected) selected - ids.toSet() else (selected + ids).distinct())
                        }
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        state = selection,
                        onClick = null,
                        enabled = !state.busy && visible.isNotEmpty(),
                        modifier = Modifier.padding(10.dp),
                    )
                    Text(stringResource(R.string.source_group_select_all), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                text = pluralStringResource(R.plurals.source_group_member_count, visible.size, visible.size),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!selectionMode) {
                TextButton(onClick = onSelect, enabled = !state.busy && visible.isNotEmpty()) {
                    Text(stringResource(R.string.source_group_select_members))
                }
                FilledTonalButton(
                    onClick = onAdd,
                    enabled = !state.busy,
                    shape = CircleShape,
                ) {
                    Icon(painterResource(R.drawable.library_add_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.source_group_add_sources))
                }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
        if (state.message == R.string.sources_action_failed) {
            Text(
                text = stringResource(state.message),
                modifier = Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            itemsIndexed(visible, key = { _, source -> source.key }) { index, source ->
                val checked = source.key in selected
                val memberships = state.groups.filter { it.id in source.preferences.groupIds }.joinToString(" · ") { it.name }
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) tokens.cardRadius else 0.dp,
                    topEnd = if (index == 0) tokens.cardRadius else 0.dp,
                    bottomStart = if (index == visible.lastIndex) tokens.cardRadius else 0.dp,
                    bottomEnd = if (index == visible.lastIndex) tokens.cardRadius else 0.dp,
                )
                val selectSource = { onSelection(if (checked) selected - source.key else selected + source.key) }
                Column(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (checked) tokens.activePillFill else tokens.cardSurface),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (selectionMode) {
                                    Modifier.toggleable(checked, enabled = !state.busy, role = Role.Checkbox) { selectSource() }
                                } else {
                                    Modifier.combinedClickable(
                                        enabled = !state.busy,
                                        onClick = {
                                            onSelect()
                                            selectSource()
                                        },
                                        onLongClick = {
                                            onSelect()
                                            selectSource()
                                        },
                                    )
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (selectionMode) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !state.busy)
                        } else {
                            EditorialIconBadge(
                                painter = painterResource(R.drawable.language_24px),
                                size = 36.dp,
                                iconSize = 18.dp,
                                cornerRadius = 11.dp,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.W600,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = source.host,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (adding && memberships.isNotEmpty()) {
                                Text(
                                    text = memberships,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (index != visible.lastIndex) {
                        EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            if (visible.isEmpty()) {
                item(key = "group_members_empty") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            EditorialIconBadge(
                                painter = painterResource(R.drawable.view_list_24px),
                                size = 40.dp,
                                iconSize = 20.dp,
                            )
                            Text(
                                text = stringResource(
                                    when {
                                        query.isNotBlank() && sources.isNotEmpty() -> R.string.source_group_no_matches
                                        state.addedSources.isEmpty() -> R.string.source_group_no_sources
                                        adding -> R.string.source_group_candidates_empty
                                        else -> R.string.source_group_members_empty
                                    },
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.W600,
                            )
                            if (!adding && sources.isEmpty() && state.addedSources.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.source_group_empty_help),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
