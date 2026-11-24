package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hnovel.imports.ImportCode
import hnovel.imports.SourceCandidate
import hnovel.imports.SourceOriginCandidates
import hnovel.imports.deduplicated
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Selection and permissions are drafts; only the fixed confirmation action commits them. */
@Composable
internal fun SourceImportPreview(
    state: SourceManagementState,
    model: SourcesViewModel,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    val preview = checkNotNull(state.preview)
    var advanced by rememberSaveable(preview) { mutableStateOf(false) }
    var selected by rememberSaveable(preview) {
        mutableStateOf(
            if (state.updateTarget == null) {
                preview.candidates.deduplicated().filter { state.catalogPreview || it.enabled }.map { it.index }
            } else {
                emptyList()
            },
        )
    }
    var filter by rememberSaveable(preview) { mutableIntStateOf(0) }
    var approveIdentity by rememberSaveable(preview) { mutableStateOf(false) }
    var permissions by rememberSaveable(
        preview,
        stateSaver = mapSaver<Map<Int, String>>(
            save = { values -> values.mapKeys { it.key.toString() } },
            restore = { values -> values.map { it.key.toInt() to it.value as String }.toMap() },
        ),
    ) {
        mutableStateOf(emptyMap())
    }
    val candidates = if (advanced) preview.candidates else preview.candidates.deduplicated()
    val invalidLines = selected.associateWith {
        SourcesViewModel.invalidPermissionLines(permissions[it] ?: state.previewOrigins[it].orEmpty())
    }
    val visible = candidates.filter {
        when (filter) {
            1 -> it.existing == null
            2 -> it.existing != null
            else -> true
        }
    }
    fun select(candidate: SourceCandidate, checked: Boolean) {
        selected = if (!checked) {
            selected - candidate.index
        } else if (state.updateTarget != null) {
            listOf(candidate.index)
        } else {
            (selected - candidate.duplicateIndexes.toSet() - candidate.index) + candidate.index
        }
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "preview_controls") {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        }
                        val ignored = preview.issues.count { it.code == ImportCode.UnsupportedType }
                        if (ignored > 0) {
                            Text(
                                text = stringResource(R.string.sources_non_text_skipped, ignored),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.message?.let {
                            Text(
                                text = stringResource(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (state.updateTarget == null) {
                                listOf(
                                    R.string.sources_filter_all,
                                    R.string.sources_filter_new,
                                    R.string.sources_filter_updates,
                                ).forEachIndexed { index, title ->
                                    FilterChip(
                                        selected = filter == index,
                                        onClick = { filter = index },
                                        shape = CircleShape,
                                        label = { Text(stringResource(title)) },
                                    )
                                }
                            }
                            FilterChip(
                                selected = advanced,
                                onClick = { advanced = !advanced },
                                shape = CircleShape,
                                label = { Text(stringResource(R.string.sources_advanced)) },
                            )
                        }
                        if (state.updateTarget == null) {
                            EditorialHairlineDivider()
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        selected = (preview.candidates.filter { it.index in selected } + visible)
                                            .deduplicated()
                                            .map { it.index }
                                    },
                                    enabled = !state.busy && visible.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.sources_select_visible))
                                }
                                TextButton(
                                    onClick = { selected = emptyList() },
                                    enabled = !state.busy && selected.isNotEmpty(),
                                ) {
                                    Text(stringResource(R.string.sources_clear_selection))
                                }
                            }
                        }
                    }
                }
            }

            items(preview.issues.filter { it.code != ImportCode.UnsupportedType }) { issue ->
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(importProblem(issue.code)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        issue.index?.let {
                            Text(
                                text = stringResource(R.string.sources_import_entry, it + 1),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        ImportField(issue.field)
                    }
                }
            }

            if (visible.isEmpty()) {
                item(key = "preview_empty") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sources_filter_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(visible, key = { it.index }) { candidate ->
                var showNotes by rememberSaveable(preview, candidate.index) { mutableStateOf(false) }
                val checked = candidate.index in selected
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = if (checked) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                    borderWidth = if (checked) 1.25.dp else 0.75.dp,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    enabled = !state.busy,
                                    role = Role.Checkbox,
                                    onValueChange = { select(candidate, it) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !state.busy)
                            Spacer(Modifier.width(10.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = candidate.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.W600,
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(
                                            if (candidate.existing == null) tokens.activePillFill
                                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        )
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (candidate.existing == null) R.string.sources_new else R.string.sources_replace,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (candidate.existing == null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                        if (advanced && !candidate.enabled) {
                            Text(
                                text = stringResource(R.string.sources_disabled_definition),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (advanced && candidate.duplicateIndexes.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.sources_duplicate_choice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (advanced && candidate.notices.isNotEmpty()) {
                            TextButton(onClick = { showNotes = !showNotes }) {
                                Text(stringResource(R.string.sources_import_notes, candidate.notices.size))
                            }
                            if (showNotes) {
                                EditorialCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 12.dp,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    contentPadding = PaddingValues(12.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        candidate.notices.forEach { notice ->
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = stringResource(importNotice(notice.code)),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (notice.code == "UnclassifiedField") {
                                                        MaterialTheme.colorScheme.error
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                )
                                                ImportField(notice.field)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val errors = invalidLines[candidate.index].orEmpty()
                        if (errors.isNotEmpty() && !advanced) {
                            Text(
                                text = stringResource(R.string.sources_invalid_permission_lines, errors.joinToString(", ")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (checked && advanced) {
                            val draft = permissions[candidate.index] ?: state.previewOrigins[candidate.index].orEmpty()
                            val origins = remember(candidate) {
                                SourceOriginCandidates.discover(Json.parseToJsonElement(candidate.rawJson).jsonObject)
                            }
                            EditorialHairlineDivider()
                            Text(
                                text = stringResource(R.string.sources_origin_candidates_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            origins.distinctBy { it.origin }.forEach { origin ->
                                SourcePermissionCandidate(origin.origin, origin.kind.name, draft, state.busy) {
                                    permissions = permissions + (candidate.index to it)
                                }
                            }
                            OutlinedTextField(
                                value = draft,
                                onValueChange = { permissions = permissions + (candidate.index to it) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                label = { Text(stringResource(R.string.sources_permissions)) },
                                enabled = !state.busy,
                                isError = errors.isNotEmpty(),
                                supportingText = if (errors.isEmpty()) {
                                    null
                                } else {
                                    { Text(stringResource(R.string.sources_invalid_permission_lines, errors.joinToString(", "))) }
                                },
                            )
                        }
                    }
                }
            }

            if (state.updateTarget != null) {
                item(key = "approve_identity") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = if (approveIdentity) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                        containerColor = if (approveIdentity) tokens.activePillFill else tokens.cardSurface,
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = approveIdentity,
                                    enabled = !state.busy,
                                    role = Role.Checkbox,
                                    onValueChange = { approveIdentity = it },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked = approveIdentity, onCheckedChange = null, enabled = !state.busy)
                            Text(
                                text = stringResource(R.string.sources_identity_approval),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        SourceSelectionBar(
            summary = stringResource(R.string.sources_selected_count, selected.size, candidates.size),
            secondary = stringResource(android.R.string.cancel),
            onSecondary = { if (state.busy) model.cancel() else model.dismissPreview() },
            action = stringResource(R.string.sources_apply),
            actionEnabled = !state.busy && selected.isNotEmpty() && invalidLines.values.all { it.isEmpty() },
            onAction = {
                model.commit(
                    selected.toSet(),
                    selected.associateWith { permissions[it] ?: state.previewOrigins[it].orEmpty() },
                    approveIdentity,
                )
            },
        )
    }
}

@Composable
private fun ImportField(field: String?) {
    // A parser field can originate in untrusted object keys. Never echo arbitrary values or messages.
    field?.takeIf { it.matches(Regex("[A-Za-z0-9_.\\[\\]-]{1,120}")) }?.let {
        Text(
            text = stringResource(R.string.sources_notice_field, it),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun importNotice(code: String): Int = when (code) {
    "ExecutionCompatibilityPending" -> R.string.sources_notice_execution
    "UnclassifiedField" -> R.string.sources_notice_unknown
    "ExternalOrderRetainedNotApplied" -> R.string.sources_notice_order
    "DiscoveryExtension" -> R.string.sources_notice_extension
    else -> R.string.sources_notice_other
}

private fun importProblem(code: ImportCode): Int = when (code) {
    ImportCode.TooDeep, ImportCode.TooLarge, ImportCode.TooMany -> R.string.sources_import_limit
    ImportCode.UnsupportedFormat, ImportCode.UnsupportedProfile, ImportCode.UnsupportedType -> R.string.sources_import_unsupported
    ImportCode.ReadFailed -> R.string.sources_import_read_failed
    ImportCode.DownloadFailed -> R.string.sources_import_download_failed
    ImportCode.StorageUnavailable, ImportCode.StorageQuota -> R.string.sources_import_storage_failed
    ImportCode.Conflict, ImportCode.StalePreview -> R.string.sources_import_stale
    ImportCode.DuplicateSelection, ImportCode.InvalidSelection -> R.string.sources_import_selection_failed
    else -> R.string.sources_import_invalid_format
}
