package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

@Composable
internal fun SpeechReplacementsScreen(state: SpeechReplacementsState, onBack: () -> Unit,
    onEdit: (String?) -> Unit, onToggle: (String, Boolean) -> Unit, onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit, onPreview: () -> Unit, onSort: (Boolean) -> Unit,
    onToggleGroup: (Set<String>, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val closeSearch = { searching = false; query = "" }
    BackHandler(searching, closeSearch)
    val visible = remember(state.rules, query) { filterSpeechReplacements(state.rules, query) }
    val grouped = state.rules.any { it.group.isNotEmpty() }
    Scaffold(topBar = {
        SpeechReplacementsTopBar(query, { query = it }, searching, { searching = true }, closeSearch,
            onBack, state.loading || state.saving, { onEdit(null) }, onPreview, onSort)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-replacements-list"),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.storageFailed) item { Text(stringResource(R.string.tts_error_storage), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
            if (!state.loading && visible.isEmpty()) item {
                Text(stringResource(if (query.isEmpty()) R.string.tts_replace_empty else R.string.tts_replace_no_results),
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            visible.groupBy { it.group }.forEach { (group, rules) ->
                val expanded = query.isNotEmpty() || group !in collapsed
                if (grouped) item(key = "group:$group") {
                    val enabled = rules.count { it.activate }
                    val label = group.ifEmpty { stringResource(R.string.tts_replace_ungrouped) }
                    Row(Modifier.fillMaxWidth().clickable {
                        collapsed = if (group in collapsed) collapsed - group else collapsed + group
                    }.padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(if (expanded) R.drawable.keyboard_arrow_up_24px else R.drawable.arrow_right_24px),
                            stringResource(if (expanded) R.string.tts_replace_collapse else R.string.tts_replace_expand))
                        Text(label, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TriStateCheckbox(when (enabled) { 0 -> ToggleableState.Off; rules.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                            onClick = { onToggleGroup(rules.map { it.id }.toSet(), enabled != rules.size) }, enabled = !state.saving,
                            modifier = Modifier.semantics { contentDescription = label })
                    }
                }
                if (expanded) itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(rule.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(rule.activate, { onToggle(rule.id, it) }, enabled = !state.saving,
                                    modifier = Modifier.testTag("replace-toggle-${rule.id}").semantics { contentDescription = rule.label })
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = !state.saving) {
                                        Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_replace_actions, rule.label))
                                    }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_edit)) }, onClick = { menu = false; onEdit(rule.id) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_up)) }, enabled = index > 0,
                                            onClick = { menu = false; onMove(rule.id, state.rules.indexOf(rules[index - 1]) - state.rules.indexOf(rule)) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_down)) }, enabled = index < rules.lastIndex,
                                            onClick = { menu = false; onMove(rule.id, state.rules.indexOf(rules[index + 1]) - state.rules.indexOf(rule)) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_delete)) }, onClick = { menu = false; onDelete(rule.id) })
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = !state.saving) { onEdit(rule.id) }.testTag("replace-rule-${rule.id}"),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechReplacementPreviewScreen(state: SpeechReplacementsState, onBack: () -> Unit,
    onPreview: (String) -> Unit, onClearPreview: () -> Unit) {
    val sample = stringResource(R.string.tts_replace_sample)
    var text by rememberSaveable { mutableStateOf(sample) }
    DisposableEffect(Unit) { onDispose(onClearPreview) }
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_replace_preview, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ReplacementField(text, { text = it; onClearPreview() }, stringResource(R.string.tts_replace_input),
                Modifier.testTag("replace-preview-input"), minLines = 3) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPreview(text) }, enabled = !state.loading && !state.previewing) { Text(stringResource(R.string.tts_replace_run_preview)) }
                    if (state.previewing) TextButton(onClick = onClearPreview) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (state.previewing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { ReplacementPreviewError(state) }
            state.preview?.let { plan ->
                item { Text(stringResource(R.string.tts_replace_after_whole), style = MaterialTheme.typography.titleSmall) }
                item { SelectionContainer { Text(plan.structuredText, Modifier.testTag("replace-preview-structure")) } }
                item { Text(stringResource(R.string.tts_replace_after_normal), style = MaterialTheme.typography.titleSmall) }
                if (plan.segments.isEmpty()) item { Text(stringResource(R.string.tts_replace_no_speech)) }
                itemsIndexed(plan.segments) { _, segment ->
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            segment.speaker?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                            Text(segment.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReplacementPreviewError(state: SpeechReplacementsState) {
    state.failure?.let { SpeechReplacementError(it) }
    state.previewError?.let { Text(stringResource(it.messageResource), color = MaterialTheme.colorScheme.error) }
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
