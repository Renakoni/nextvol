package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.ManagedSource
import indi.renakoni.nextvol.data.web.rules.SourceGroup
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider

@Composable
internal fun SourceGroupFilters(
    groups: List<SourceGroup>,
    installed: List<ManagedSource>,
    selected: String?,
    showBottomDivider: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    val ungroupedCount = installed.count { it.preferences.groupIds.isEmpty() }
    Column(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            item(key = "all") {
                SourceGroupFilter(
                    name = stringResource(R.string.sources_filter_all),
                    count = installed.size,
                    selected = selected == null,
                ) { onSelect(null) }
            }
            if (ungroupedCount > 0) {
                item(key = "ungrouped") {
                    SourceGroupFilter(
                        name = stringResource(R.string.source_group_ungrouped),
                        count = ungroupedCount,
                        selected = selected == "",
                    ) { onSelect("") }
                }
            }
            items(groups, key = { it.id }) { group ->
                SourceGroupFilter(
                    name = group.name,
                    count = installed.count { group.id in it.preferences.groupIds },
                    selected = selected == group.id,
                ) { onSelect(group.id) }
            }
        }
        if (showBottomDivider) {
            EditorialHairlineDivider()
        }
    }
}

@Composable
private fun SourceGroupFilter(name: String, count: Int, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val memberCount = pluralStringResource(R.plurals.source_group_member_count, count, count)
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.onSurface else colors.onSurfaceVariant,
        animationSpec = tween(180),
        label = "sourceGroupTabText",
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = tween(180),
        label = "sourceGroupTabIndicator",
    )

    Column(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onSelect,
            )
            .semantics { contentDescription = "$name, $memberCount" }
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = name,
                modifier = Modifier.widthIn(max = 160.dp),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count > 0) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                    ),
                    color = if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(CircleShape)
                .background(indicatorColor),
        )
    }
}

@Composable
internal fun SourceGroupsDialog(
    groups: List<SourceGroup>,
    installed: List<ManagedSource>,
    busy: Boolean,
    message: Int?,
    revision: Long,
    members: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>, Set<String>) -> Unit,
    onCreate: (String, Set<String>, Set<String>) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var creating by rememberSaveable(revision) { mutableStateOf(false) }
    var added by rememberSaveable(revision) { mutableStateOf(emptyList<String>()) }
    var removed by rememberSaveable(revision) { mutableStateOf(emptyList<String>()) }
    val sources = installed.filter { it.key in members }
    if (creating) {
        SourceGroupNameDialog(
            groups = groups,
            group = null,
            busy = busy,
            message = message,
            onDismiss = { creating = false },
            onSave = { onCreate(it, added.toSet(), removed.toSet()) },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.source_group_move)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.source_group_members_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message == R.string.sources_action_failed) {
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                }
                if (groups.isNotEmpty()) {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                        ) {
                            items(groups, key = { it.id }) { group ->
                                val count = sources.count { group.id in it.preferences.groupIds }
                                val selected = when {
                                    group.id in added -> ToggleableState.On
                                    group.id in removed || count == 0 -> ToggleableState.Off
                                    count == sources.size -> ToggleableState.On
                                    else -> ToggleableState.Indeterminate
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .triStateToggleable(
                                            state = selected,
                                            enabled = !busy,
                                            role = Role.Checkbox,
                                        ) {
                                            if (selected == ToggleableState.On) {
                                                added = added - group.id
                                                removed = (removed + group.id).distinct()
                                            } else {
                                                added = (added + group.id).distinct()
                                                removed = removed - group.id
                                            }
                                        }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    TriStateCheckbox(state = selected, onClick = null, enabled = !busy)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.W500,
                                        )
                                        if (selected == ToggleableState.Indeterminate) {
                                            Text(
                                                text = stringResource(R.string.source_group_partial_members),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (group.id != groups.last().id) {
                                    EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                                }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { creating = true },
                    enabled = !busy,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(R.drawable.add_circle_24px), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.source_group_create))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(added.toSet(), removed.toSet()) },
                shape = CircleShape,
                enabled = !busy && sources.isNotEmpty() && (added.isNotEmpty() || removed.isNotEmpty()),
            ) {
                Text(stringResource(R.string.source_group_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
internal fun SourceGroupNameDialog(
    groups: List<SourceGroup>,
    group: SourceGroup?,
    busy: Boolean,
    message: Int?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var name by rememberSaveable(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    val normalized = name.trim()
    val valid = SourceGroup.validName(normalized) && groups.none { it.id != group?.id && it.name.equals(normalized, true) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(if (group == null) R.string.source_group_create else R.string.source_group_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message == R.string.sources_action_failed) {
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.source_group_name)) },
                    isError = name.isNotEmpty() && !valid,
                )
                Text(
                    text = stringResource(R.string.source_group_name_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !busy,
                shape = CircleShape,
                onClick = { onSave(normalized) },
            ) {
                Text(stringResource(R.string.source_group_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
internal fun SourceGroupDeleteDialog(
    group: SourceGroup,
    busy: Boolean,
    message: Int?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        title = { Text(stringResource(R.string.source_group_delete_title, group.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message == R.string.sources_action_failed) {
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = stringResource(R.string.source_group_delete_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = onDelete,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.source_group_delete))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
