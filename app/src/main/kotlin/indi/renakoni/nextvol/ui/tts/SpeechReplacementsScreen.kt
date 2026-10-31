package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechReplacementsScreen(state: SpeechReplacementsState, onBack: () -> Unit,
    onEdit: (String?) -> Unit, onToggle: (String, Boolean) -> Unit, onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit, onPreview: () -> Unit) {
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var groupsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.rules) { if (!state.loading && state.rules.none { it.group == group }) group = null }
    val visible = state.rules.filter { group == null || it.group == group }
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_replacements, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-replacements-list"),
            contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                FlowRow(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEdit(null) }, enabled = !state.loading && !state.saving) { Text(stringResource(R.string.tts_replace_add)) }
                    TextButton(onClick = onPreview, enabled = !state.loading) { Text(stringResource(R.string.tts_replace_preview)) }
                    Box {
                        TextButton(onClick = { groupsOpen = true }) { Text(group?.ifEmpty { stringResource(R.string.tts_replace_ungrouped) }
                            ?: stringResource(R.string.tts_replace_all_groups)) }
                        DropdownMenu(groupsOpen, { groupsOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_all_groups)) }, onClick = { group = null; groupsOpen = false })
                            state.rules.map { it.group }.distinct().forEach { name ->
                                DropdownMenuItem(text = { Text(name.ifEmpty { stringResource(R.string.tts_replace_ungrouped) }) },
                                    onClick = { group = name; groupsOpen = false })
                            }
                        }
                    }
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(22.dp)) }
            if (state.storageFailed) item { Text(stringResource(R.string.tts_error_storage), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error) }
            if (!state.loading && state.rules.isEmpty()) item {
                Text(stringResource(R.string.tts_replace_empty), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(visible, key = { _, rule -> rule.id }) { index, rule ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(rule.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (rule.name.isEmpty()) null else {
                        { Text("${rule.source} → ${rule.target}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    },
                    leadingContent = {
                        Switch(rule.activate, { onToggle(rule.id, it) }, enabled = !state.saving,
                            modifier = Modifier.testTag("replace-toggle-${rule.id}").semantics {
                                contentDescription = rule.label
                            })
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !state.saving) {
                                Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_replace_actions, rule.label))
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_edit)) }, onClick = { menu = false; onEdit(rule.id) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_up)) }, enabled = index > 0,
                                    onClick = { menu = false; onMove(rule.id, state.rules.indexOf(visible[index - 1]) - state.rules.indexOf(rule)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_down)) }, enabled = index < visible.lastIndex,
                                    onClick = { menu = false; onMove(rule.id, state.rules.indexOf(visible[index + 1]) - state.rules.indexOf(rule)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_delete)) }, onClick = { menu = false; onDelete(rule.id) })
                            }
                        }
                    }, modifier = Modifier.clickable(enabled = !state.saving) { onEdit(rule.id) }.testTag("replace-rule-${rule.id}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechReplacementEditorScreen(state: SpeechReplacementsState, ruleId: String?, onBack: () -> Unit,
    onSave: (SpeechReplacementRule) -> Unit, onPreview: (String, SpeechReplacementRule) -> Unit,
    onClearPreview: () -> Unit) {
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_replace_edit, onBack) }) { padding ->
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(padding).padding(22.dp))
            return@Scaffold
        }
        val existing = state.rules.find { it.id == ruleId }
        val id = rememberSaveable { ruleId ?: UUID.randomUUID().toString() }
        var name by rememberSaveable(id) { mutableStateOf(existing?.name.orEmpty()) }
        var group by rememberSaveable(id) { mutableStateOf(existing?.group.orEmpty()) }
        var source by rememberSaveable(id) { mutableStateOf(existing?.source.orEmpty()) }
        var target by rememberSaveable(id) { mutableStateOf(existing?.target.orEmpty()) }
        var regex by rememberSaveable(id) { mutableStateOf(existing?.regex ?: false) }
        var active by rememberSaveable(id) { mutableStateOf(existing?.activate ?: true) }
        val draft = SpeechReplacementRule(id, group, name, source, target, regex, active)
        LaunchedEffect(draft) { onClearPreview() }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-replacement-editor"),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ReplacementField(name, { name = it }, stringResource(R.string.tts_replace_name), singleLine = true) }
            item { ReplacementField(group, { group = it }, stringResource(R.string.tts_replace_group), singleLine = true) }
            item { ReplacementSwitch(stringResource(R.string.tts_replace_enabled), active) { active = it } }
            item { ReplacementSwitch(stringResource(R.string.tts_replace_regex), regex) { regex = it } }
            item { ReplacementField(source, { source = it }, stringResource(R.string.tts_replace_source), Modifier.testTag("replace-source")) }
            item { ReplacementField(target, { target = it }, stringResource(R.string.tts_replace_target), Modifier.testTag("replace-target")) }
            item {
                Text(stringResource(if (draft.wholeText) R.string.tts_replace_whole else R.string.tts_replace_normal), style = MaterialTheme.typography.titleSmall)
            }
            item { Button(onClick = { onSave(draft) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tts_engine_save)) } }
            if (state.storageFailed) item { Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error) }
            item { ReplacementPreview(state, onPreview = { onPreview(it, draft) }, onClearPreview) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechReplacementPreviewScreen(state: SpeechReplacementsState, onBack: () -> Unit,
    onPreview: (String) -> Unit, onClearPreview: () -> Unit) {
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_replace_preview, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(22.dp)) {
            item { ReplacementPreview(state, onPreview, onClearPreview) }
        }
    }
}

@Composable
private fun ReplacementSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(value, null)
    }
}

@Composable
private fun ReplacementPreview(state: SpeechReplacementsState, onPreview: (String) -> Unit, onClearPreview: () -> Unit) {
    val sample = stringResource(R.string.tts_replace_sample)
    var text by rememberSaveable { mutableStateOf(sample) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReplacementField(text, { text = it; onClearPreview() }, stringResource(R.string.tts_replace_input), Modifier.testTag("replace-preview-input"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPreview(text) }, enabled = !state.loading && !state.previewing) { Text(stringResource(R.string.tts_replace_run_preview)) }
            if (state.previewing) TextButton(onClick = onClearPreview) { Text(stringResource(R.string.cancel)) }
        }
        if (state.previewing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { SpeechReplacementError(it) }
        state.previewError?.let { Text(stringResource(it.messageResource), color = MaterialTheme.colorScheme.error) }
        state.preview?.let { plan ->
            Text(stringResource(R.string.tts_replace_after_whole), style = MaterialTheme.typography.titleSmall)
            Text(plan.structuredText, Modifier.testTag("replace-preview-structure"))
            Text(stringResource(R.string.tts_replace_after_normal), style = MaterialTheme.typography.titleSmall)
            if (plan.segments.isEmpty()) Text(stringResource(R.string.tts_replace_no_speech))
            plan.segments.forEach { segment ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    segment.speaker?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    Text(segment.text)
                }
            }
        }
    }
}

@Composable
private fun ReplacementField(value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = false) {
    TextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
}

@Composable
internal fun SpeechReplacementError(failure: SpeechReplacementFailure) {
    val reason = stringResource(when (failure.problem) {
        SpeechReplacementProblem.Pattern -> R.string.tts_replace_error_pattern
        SpeechReplacementProblem.Replacement -> R.string.tts_replace_error_target
        SpeechReplacementProblem.Budget -> R.string.tts_replace_error_budget
    })
    Text(stringResource(R.string.tts_replace_error_rule, failure.ruleName, reason), color = MaterialTheme.colorScheme.error)
}
