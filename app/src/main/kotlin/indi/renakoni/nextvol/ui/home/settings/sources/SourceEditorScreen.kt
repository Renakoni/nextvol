package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

@Composable
internal fun SourceEditorScreen(draft: SourceEditorState, busy: Boolean, model: SourcesViewModel,
    modifier: Modifier = Modifier, finding: Boolean = false, onCloseFind: () -> Unit = {}) {
    var value by remember(draft.installed.definition.sourceId) { mutableStateOf(TextFieldValue(draft.rawJson)) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var replacing by rememberSaveable { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    val findFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val largeText = LocalDensity.current.fontScale > 1.3f
    LaunchedEffect(finding) { if (finding) findFocus.requestFocus() }
    LaunchedEffect(draft.rawJson) {
        if (value.text != draft.rawJson) value = TextFieldValue(draft.rawJson,
            TextRange(value.selection.start.coerceAtMost(draft.rawJson.length)))
    }
    val matches = remember(value.text, query) { editorMatches(value.text, query) }
    fun change(next: TextFieldValue) {
        value = next
        if (next.text != draft.rawJson) model.editDraft(next.text)
    }
    fun find(backwards: Boolean = false, positions: List<Int> = matches) {
        if (positions.isEmpty()) return
        val index = if (backwards) positions.lastOrNull { it < value.selection.min } ?: positions.last()
            else positions.firstOrNull { it >= value.selection.max } ?: positions.first()
        value = value.copy(selection = TextRange(index, index + query.length))
        editorFocus.requestFocus()
    }
    val selectedMatch = if (value.selection.length == query.length) matches.indexOf(value.selection.min) else -1
    Column(modifier.fillMaxSize().imePadding()) {
        if (finding) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EditorSearchField(query, stringResource(R.string.sources_editor_find), !busy, Modifier.weight(1f).focusRequester(findFocus),
                        onValueChange = { query = it }, onSearch = { find() })
                    IconButton(onClick = { focus.clearFocus(); onCloseFind() }) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.sources_editor_close_find))
                    }
                }
                if (replacing) FlowRow(Modifier.padding(top = 8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    EditorSearchField(replacement, stringResource(R.string.sources_editor_replace_with), !busy,
                        if (largeText) Modifier.fillMaxWidth() else Modifier.weight(1f),
                        onValueChange = { replacement = it })
                    TextButton(onClick = {
                        val start = if (selectedMatch >= 0) matches[selectedMatch] else
                            matches.firstOrNull { it >= value.selection.max } ?: matches.first()
                        change(TextFieldValue(value.text.replaceRange(start, start + query.length, replacement),
                            TextRange(start + replacement.length)))
                        find(positions = editorMatches(value.text, query))
                    }, enabled = !busy && matches.isNotEmpty()) { Text(stringResource(R.string.sources_editor_replace)) }
                    TextButton(onClick = {
                        change(TextFieldValue(value.text.replace(query, replacement)))
                    }, enabled = !busy && matches.isNotEmpty()) { Text(stringResource(R.string.sources_editor_replace_all)) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { replacing = !replacing }) {
                        Text(stringResource(if (replacing) R.string.sources_editor_hide_replace else R.string.sources_editor_replace))
                        Icon(painterResource(R.drawable.keyboard_arrow_up_24px),
                            stringResource(if (replacing) R.string.sources_editor_hide_replace else R.string.sources_editor_show_replace),
                            Modifier.size(18.dp).rotate(if (replacing) 0f else 180f))
                    }
                    Spacer(Modifier.weight(1f))
                    if (query.isNotEmpty()) Text(when {
                        matches.isEmpty() -> stringResource(R.string.sources_editor_no_matches)
                        selectedMatch >= 0 -> "${selectedMatch + 1}/${matches.size}"
                        else -> stringResource(R.string.sources_editor_match_count, matches.size)
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { find(true) }, enabled = !busy && matches.isNotEmpty()) {
                        Icon(painterResource(R.drawable.keyboard_arrow_up_24px), stringResource(R.string.sources_editor_previous))
                    }
                    IconButton(onClick = { find() }, enabled = !busy && matches.isNotEmpty()) {
                        Icon(painterResource(R.drawable.keyboard_arrow_up_24px), stringResource(R.string.sources_editor_next), Modifier.rotate(180f))
                    }
                }
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
        draft.error?.let { error ->
            val message = if (error.detail == null) stringResource(error.message) else stringResource(error.message, error.detail)
            val located = error.line != null && error.column != null
            val text = if (located) stringResource(R.string.sources_editor_error_position, error.line!!, error.column!!) + " · " + message else message
            Text(text, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = located) {
                var offset = 0
                repeat((error.line!! - 1).coerceAtLeast(0)) {
                    offset = value.text.indexOf('\n', offset).takeIf { it >= 0 }?.plus(1) ?: value.text.length
                }
                offset = (offset + error.column!! - 1).coerceIn(0, value.text.length)
                value = value.copy(selection = TextRange(offset, (offset + 1).coerceAtMost(value.text.length)))
                editorFocus.requestFocus()
            }.padding(horizontal = 16.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (draft.error == null && (draft.saved || draft.jsonValid)) Text(stringResource(
            if (draft.saved) R.string.sources_saved else R.string.sources_editor_json_valid),
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (draft.pendingOrigins.isNotEmpty()) AlertDialog(onDismissRequest = model::dismissEditorOrigins,
        title = { Text(stringResource(R.string.sources_editor_allow_sites)) },
        text = { Text(draft.pendingOrigins.joinToString("\n")) },
        confirmButton = { TextButton(onClick = model::approveEditorOrigins, enabled = !busy) {
            Text(stringResource(R.string.sources_editor_allow_save))
        } },
        dismissButton = { TextButton(onClick = model::dismissEditorOrigins, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } })
}

private fun editorMatches(text: String, query: String): List<Int> =
    if (query.isEmpty()) emptyList() else buildList {
        var index = text.indexOf(query)
        while (index >= 0) { add(index); index = text.indexOf(query, index + query.length) }
    }

@Composable
private fun EditorSearchField(value: String, label: String, enabled: Boolean, modifier: Modifier,
    onValueChange: (String) -> Unit, onSearch: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value, onValueChange, modifier.heightIn(min = 48.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
        .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
        .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
        enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        decorationBox = { input -> Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            input()
        } })
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
