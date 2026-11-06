package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechReplacementRule
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechReplacementEditorScreen(state: SpeechReplacementsState, ruleId: String?, onBack: () -> Unit,
    onSave: (SpeechReplacementRule) -> Unit, onPreview: (String, SpeechReplacementRule) -> Unit,
    onClearPreview: () -> Unit, onClearSaveError: () -> Unit) {
    Surface(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        if (state.loading) {
            LinearProgressIndicator(Modifier.padding(24.dp))
            return@Surface
        }
        val existing = state.rules.find { it.id == ruleId }
        val id = rememberSaveable { ruleId ?: UUID.randomUUID().toString() }
        var name by rememberSaveable(id) { mutableStateOf(existing?.name.orEmpty()) }
        var group by rememberSaveable(id) { mutableStateOf(existing?.group.orEmpty()) }
        var source by rememberSaveable(id) { mutableStateOf(existing?.source.orEmpty()) }
        var target by rememberSaveable(id) { mutableStateOf(existing?.target.orEmpty()) }
        var regex by rememberSaveable(id) { mutableStateOf(existing?.regex ?: false) }
        var active by rememberSaveable(id) { mutableStateOf(existing?.activate ?: true) }
        var text by rememberSaveable(id) { mutableStateOf("") }
        var groupsOpen by remember { mutableStateOf(false) }
        val groups = state.rules.map { it.group }.filter { it.isNotEmpty() }.distinct()
        val draft = SpeechReplacementRule(id, group, name, source, target, regex, active)
        val defaultGroup = stringResource(R.string.tts_replace_ungrouped)
        LaunchedEffect(source, target, regex, text) { onClearPreview() }
        LaunchedEffect(name, source, regex) { onClearSaveError() }
        DisposableEffect(Unit) { onDispose(onClearPreview) }
        Column(Modifier.testTag("speech-replacement-editor")) {
            Text(stringResource(if (ruleId == null) R.string.tts_replace_add else R.string.tts_replace_edit),
                Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall)
            SpeechSchemeCaption(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReplacementField(name, { name = it }, stringResource(R.string.tts_replace_name), singleLine = true,
                    error = if (state.saveError == SpeechReplacementSaveError.Name) stringResource(R.string.tts_replace_name_required) else null)
                ReplacementField(source, { source = it }, stringResource(R.string.tts_replace_source), Modifier.testTag("replace-source"),
                    error = when (state.saveError) {
                        SpeechReplacementSaveError.Source -> stringResource(R.string.tts_replace_source_required)
                        SpeechReplacementSaveError.Pattern -> stringResource(R.string.tts_replace_error_pattern)
                        else -> null
                    })
                ReplacementField(target, { target = it }, stringResource(R.string.tts_replace_target), Modifier.testTag("replace-target"))
                ExposedDropdownMenuBox(groupsOpen, { if (groups.isNotEmpty()) groupsOpen = it }) {
                    ReplacementField(group, { group = it }, stringResource(R.string.tts_replace_group),
                        Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable), singleLine = true,
                        trailingIcon = if (groups.isEmpty()) null else {
                            { ExposedDropdownMenuDefaults.TrailingIcon(groupsOpen,
                                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable)) }
                        })
                    ExposedDropdownMenu(groupsOpen, { groupsOpen = false }) {
                        groups.forEach { value -> DropdownMenuItem(text = { Text(value) },
                            onClick = { group = value; groupsOpen = false }) }
                    }
                }
                ReplacementField(text, { text = it }, stringResource(R.string.tts_replace_test_input),
                    Modifier.testTag("replace-preview-input"), trailingIcon = {
                        TextButton(onClick = { if (state.previewing) onClearPreview() else onPreview(text, draft) },
                            contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(if (state.previewing) R.string.cancel else R.string.tts_replace_test))
                        }
                    })
                ReplacementField(state.rulePreview.orEmpty(), {}, stringResource(R.string.tts_replace_test_output),
                    Modifier.testTag("replace-test-output"), readOnly = true)
                if (state.previewing) LinearProgressIndicator(Modifier.fillMaxWidth())
                ReplacementPreviewError(state)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ReplacementCheckbox(stringResource(R.string.tts_replace_regex), regex) { regex = it }
                    ReplacementCheckbox(stringResource(R.string.tts_replace_enabled), active) { active = it }
                }
                if (state.storageFailed) Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBack, enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onSave(draft.copy(group = group.ifEmpty { defaultGroup })) }, enabled = !state.saving) {
                    Text(stringResource(R.string.tts_engine_save))
                }
            }
        }
    }
}

@Composable
private fun ReplacementCheckbox(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.toggleable(value, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(value, null)
        Text(label, Modifier.padding(start = 8.dp, end = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplacementField(value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = false, minLines: Int = 1, readOnly: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null, error: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        unfocusedIndicatorColor = if (readOnly) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
        focusedIndicatorColor = if (readOnly) Color.Transparent else MaterialTheme.colorScheme.primary)
    BasicTextField(value, onChange, modifier.fillMaxWidth().defaultMinSize(minHeight = TextFieldDefaults.MinHeight),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), interactionSource = interaction,
        singleLine = singleLine, minLines = minLines, readOnly = readOnly) { field ->
        TextFieldDefaults.DecorationBox(value, field, enabled = true, singleLine = singleLine,
            visualTransformation = VisualTransformation.None, interactionSource = interaction,
            label = { Text(label) }, trailingIcon = trailingIcon, colors = colors, isError = error != null,
            supportingText = error?.let { { Text(it) } },
            contentPadding = TextFieldDefaults.contentPaddingWithLabel(start = 0.dp, end = 0.dp))
    }
}
