package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SectionHeader

@Composable
internal fun SourceEditorScreen(draft: SourceEditorState, busy: Boolean, model: SourcesViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(R.string.sources_editor_help))
                Text(stringResource(R.string.sources_editor_old_books), style = MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(draft.rawJson, { model.editDraft(it, draft.permissions) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sources_editor_json)) }, enabled = !busy, minLines = 8, maxLines = 16,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(text = stringResource(R.string.sources_domain_replace))
                    Text(stringResource(R.string.sources_domain_help), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(draft.oldDomain, { model.editDomains(it, draft.newDomain) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sources_domain_old)) }, enabled = !busy, singleLine = true)
                    OutlinedTextField(draft.newDomain, { model.editDomains(draft.oldDomain, it) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sources_domain_new)) }, enabled = !busy, singleLine = true)
                    OutlinedButton(onClick = model::previewDomain, enabled = !busy && draft.oldDomain.isNotBlank() && draft.newDomain.isNotBlank()) {
                        Text(stringResource(R.string.sources_domain_preview))
                    }
                }
            }
            draft.preview?.let { preview ->
                item { Text(stringResource(R.string.sources_domain_changes, preview.changes.size)) }
                items(preview.changes) { change ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(change.field, style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.sources_domain_before, change.before), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.sources_domain_after, change.after), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sources_domain_remaining,
                            preview.remaining.joinToString(", ").ifEmpty { stringResource(R.string.sources_domain_none) }))
                        Text(stringResource(R.string.sources_domain_review_help), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = model::applyDomainPreview, enabled = !busy && preview.changes.isNotEmpty()) {
                            Text(stringResource(R.string.sources_domain_apply))
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(text = stringResource(R.string.sources_permissions_title))
                    Text(stringResource(R.string.sources_editor_permissions_help), style = MaterialTheme.typography.bodySmall)
                    SourcePermissionsField(draft.permissions, busy) { model.editDraft(draft.rawJson, it) }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (draft.saved) Text(stringResource(R.string.sources_saved), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            SourceSaveActions(draft.error, busy || draft.preview != null, R.string.sources_editor_save, model::saveEditor)
        }
    }
}

@Composable
internal fun SourcePermissionsField(permissions: String, busy: Boolean, onChange: (String) -> Unit) {
    val invalid = remember(permissions) { SourcesViewModel.invalidPermissionLines(permissions) }
    OutlinedTextField(permissions, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_permissions)) },
        enabled = !busy, isError = invalid.isNotEmpty(), supportingText = if (invalid.isEmpty()) null else ({
            Text(stringResource(R.string.sources_invalid_permission_lines, invalid.joinToString(", ")))
        }))
}

@Composable
internal fun SourceSaveActions(error: SourceSaveError?, busy: Boolean, label: Int, onSave: () -> Unit) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(error) { if (error != null) bringIntoView.bringIntoView() }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let {
            Text(if (it.detail == null) stringResource(it.message) else stringResource(it.message, it.detail),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onSave, enabled = !busy) { Text(stringResource(label)) }
    }
}
