package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

@Composable
internal fun SourceEditorScreen(draft: SourceEditorState, busy: Boolean, model: SourcesViewModel,
    modifier: Modifier = Modifier, finding: Boolean = false) {
    var value by remember(draft.installed.definition.sourceId) { mutableStateOf(TextFieldValue(draft.rawJson)) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    val editorFocus = remember { FocusRequester() }
    LaunchedEffect(draft.rawJson) {
        if (value.text != draft.rawJson) value = TextFieldValue(draft.rawJson,
            TextRange(value.selection.start.coerceAtMost(draft.rawJson.length)))
    }
    val matches = remember(value.text, query) {
        if (query.isEmpty()) emptyList() else buildList {
            var index = value.text.indexOf(query)
            while (index >= 0) { add(index); index = value.text.indexOf(query, index + query.length) }
        }
    }
    fun change(next: TextFieldValue) {
        value = next
        if (next.text != draft.rawJson) model.editDraft(next.text)
    }
    fun findNext() {
        if (query.isEmpty()) return
        val index = value.text.indexOf(query, value.selection.max).takeIf { it >= 0 } ?: value.text.indexOf(query)
        if (index < 0) return
        value = value.copy(selection = TextRange(index, index + query.length))
        editorFocus.requestFocus()
    }
    val selectedMatch = query.isNotEmpty() && !value.selection.collapsed &&
        value.text.substring(value.selection.min, value.selection.max) == query
    Column(modifier.fillMaxSize().imePadding()) {
        if (finding) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.sources_editor_find)) },
                trailingIcon = { Text("${if (selectedMatch) matches.indexOf(value.selection.min) + 1 else 0}/${matches.size}",
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            OutlinedTextField(replacement, { replacement = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.sources_editor_replace_with)) },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = ::findNext, enabled = !busy && matches.isNotEmpty()) { Text(stringResource(R.string.sources_editor_next)) }
                TextButton(onClick = {
                    val start = if (selectedMatch) value.selection.min else
                        value.text.indexOf(query, value.selection.max).takeIf { it >= 0 } ?: value.text.indexOf(query)
                    if (start >= 0) {
                        change(TextFieldValue(value.text.replaceRange(start, start + query.length, replacement), TextRange(start + replacement.length)))
                    }
                    findNext()
                }, enabled = !busy && matches.isNotEmpty()) { Text(stringResource(R.string.sources_editor_replace)) }
                TextButton(onClick = {
                    change(TextFieldValue(value.text.replace(query, replacement)))
                }, enabled = !busy && matches.isNotEmpty()) { Text(stringResource(R.string.sources_editor_replace_all)) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val editorLabel = stringResource(R.string.sources_editor_json)
        TextField(value, { change(it) }, Modifier.weight(1f).fillMaxWidth().focusRequester(editorFocus)
            .semantics { contentDescription = editorLabel }, readOnly = busy,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        draft.error?.let {
            Text(if (it.detail == null) stringResource(it.message) else stringResource(it.message, it.detail),
                Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
        if (draft.saved) Text(stringResource(R.string.sources_saved),
            Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    }
    if (draft.pendingOrigins.isNotEmpty()) AlertDialog(onDismissRequest = model::dismissEditorOrigins,
        title = { Text(stringResource(R.string.sources_editor_allow_sites)) },
        text = { Text(draft.pendingOrigins.joinToString("\n")) },
        confirmButton = { TextButton(onClick = model::approveEditorOrigins, enabled = !busy) {
            Text(stringResource(R.string.sources_editor_allow_save))
        } },
        dismissButton = { TextButton(onClick = model::dismissEditorOrigins, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun SourcePermissionsField(permissions: String, busy: Boolean, onChange: (String) -> Unit) {
    val invalid = remember(permissions) { SourcesViewModel.invalidPermissionLines(permissions) }
    OutlinedTextField(permissions, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_permissions)) },
        minLines = 2, maxLines = 6, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
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
        Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = sourceButtonColors()) { Text(stringResource(label)) }
    }
}
