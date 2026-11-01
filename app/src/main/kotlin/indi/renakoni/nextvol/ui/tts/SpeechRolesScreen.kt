package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRolesScreen(state: SpeechRolesState, onBack: () -> Unit, onEdit: (String?, Boolean) -> Unit,
    onToggle: (String, Boolean) -> Unit, onMove: (String, Int) -> Unit, onDelete: (String) -> Unit,
    onAudition: (SpeechRoleRule) -> Unit, onPreview: () -> Unit, onContext: () -> Unit, onSources: () -> Unit,
    playback: ReadAloudState, onCommand: (SpeechAction) -> Unit) {
    var bookOnly by rememberSaveable { mutableStateOf(state.bookId != null) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showPlayback by remember { mutableStateOf(false) }
    val rules = state.settings.roleRules.filter { rule ->
        (if (bookOnly) state.bookId != null && (rule.bookId == null || rule.bookId == state.bookId) else rule.bookId == null) &&
            (query.isBlank() || listOf(rule.name, rule.regex, rule.group, rule.note).any { it.contains(query, true) })
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.tts_roles), style = MaterialTheme.typography.displayLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
            IconButton(onBack) { Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back)) }
        }, actions = {
            IconButton(onClick = { onEdit(null, bookOnly) }, enabled = !state.loading && !state.saving) {
                Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_role_add))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_role_menu)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_role_preview)) }, onClick = { menu = false; onPreview() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_role_context_template)) }, onClick = { menu = false; onContext() })
                }
            }
        })
    }, bottomBar = {
        if (playback.request != null && playback.phase != SpeechPhase.Stopped)
            SpeechPlaybackBar(playback, onCommand, onOpen = { showPlayback = true })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-roles-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!bookOnly, { bookOnly = false }, label = { Text(stringResource(R.string.tts_role_global)) })
                        FilterChip(bookOnly, { bookOnly = true }, enabled = state.bookId != null,
                            label = { Text(stringResource(R.string.tts_role_this_book)) })
                    }
                    if (bookOnly) Text(state.bookTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReplacementField(query, { query = it }, stringResource(R.string.tts_role_search), singleLine = true)
                }
            }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { RoleError(error, onSources, Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) } }
            if (!state.loading && rules.isEmpty()) item {
                Text(stringResource(if (query.isEmpty()) R.string.tts_role_empty else R.string.search_no_results),
                    Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                var actions by remember { mutableStateOf(false) }
                val order = state.settings.roleRules.indexOf(rule)
                ListItem(headlineContent = {
                    Text(stringResource(R.string.tts_voice_segment, order + 1, rule.label), maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                    supportingContent = {
                        Text(listOf(rule.group, stringResource(if (rule.bookId == null) R.string.tts_role_global else R.string.tts_role_this_book),
                            stringResource(if (rule.mode == 0) R.string.tts_role_mode_text else if (rule.isAbstract)
                            R.string.tts_role_abstract else R.string.tts_role_concrete),
                            rule.speaker?.label ?: stringResource(R.string.tts_role_voice_missing)).filter { it.isNotEmpty() }.joinToString(" · "))
                    }, trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(rule.activate, { onToggle(rule.id, it) }, enabled = !state.saving,
                                modifier = Modifier.semantics { contentDescription = rule.label })
                            Box {
                                IconButton(onClick = { actions = true }, enabled = !state.saving) {
                                    Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_replace_actions, rule.label))
                                }
                                DropdownMenu(actions, { actions = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_edit)) },
                                        onClick = { actions = false; onEdit(rule.id, bookOnly) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_preview)) },
                                        onClick = { actions = false; onAudition(rule) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_up)) }, enabled = index > 0,
                                        onClick = { actions = false; onMove(rule.id, state.settings.roleRules.indexOf(rules[index - 1]) - order) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_down)) }, enabled = index < rules.lastIndex,
                                        onClick = { actions = false; onMove(rule.id, state.settings.roleRules.indexOf(rules[index + 1]) - order) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.tts_replace_delete)) },
                                        onClick = { actions = false; onDelete(rule.id) })
                                }
                            }
                        }
                    }, modifier = Modifier.clickable(enabled = !state.saving) { onEdit(rule.id, bookOnly) }
                        .testTag("speech-role-${rule.id}"))
            }
        }
    }
    if (showPlayback) ReadAloudSheet(playback, onCommand, onSettings = null, onDismiss = { showPlayback = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRolePreviewScreen(state: SpeechRolesState, onBack: () -> Unit,
    onPreview: (String, Boolean) -> Unit, onClear: () -> Unit, onRule: (String) -> Unit, onSources: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose(onClear) }
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_role_preview, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ReplacementField(text, { text = it; onClear() }, stringResource(R.string.tts_replace_input), minLines = 3) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPreview(text, false) }, enabled = !state.previewing && !state.loading) {
                        Text(stringResource(R.string.tts_replace_run_preview))
                    }
                    TextButton(onClick = { onPreview("", true) }, enabled = state.bookId != null && !state.previewing) {
                        Text(stringResource(R.string.tts_role_current_chapter))
                    }
                    if (state.previewing) TextButton(onClear) { Text(stringResource(R.string.cancel)) }
                }
            }
            rolePreview(state, onSources, onRule)
        }
    }
}

internal fun LazyListScope.rolePreview(state: SpeechRolesState, onSources: () -> Unit, onRule: ((String) -> Unit)? = null) {
    if (state.previewing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    state.failure?.let { failure -> item {
        SpeechRoleError(failure)
        if (onRule != null) TextButton(onClick = { onRule(failure.ruleId) }) { Text(stringResource(R.string.tts_replace_edit)) }
    } }
    state.error?.let { error -> item { RoleError(error, onSources) } }
    state.preview?.let { plan ->
        if (plan.segments.isEmpty()) item { Text(stringResource(R.string.tts_replace_no_speech)) }
        itemsIndexed(plan.segments) { _, segment ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val match = segment.roleMatch
                Text(match?.let { stringResource(R.string.tts_role_match, it.ruleName, it.person) }
                    ?: stringResource(R.string.tts_role_no_match), style = MaterialTheme.typography.titleSmall,
                    color = if (match == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                Text(segment.selectedVoice?.label.orEmpty(), style = MaterialTheme.typography.labelLarge)
                Text(segment.text)
                if (segment.beforeText.isNotEmpty() || segment.rememberedText.isNotEmpty()) Text(
                    stringResource(R.string.tts_role_context_before, (segment.beforeText.ifEmpty { segment.rememberedText }).asReversed().joinToString("")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (segment.afterText.isNotEmpty()) Text(stringResource(R.string.tts_role_context_after, segment.afterText.joinToString("")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                segment.selectedVoice?.error?.let { error -> RoleError(error, onSources) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
internal fun SpeechRoleError(failure: SpeechRoleFailure) {
    val reason = stringResource(when (failure.problem) {
        SpeechReplacementProblem.Pattern -> R.string.tts_replace_error_pattern
        SpeechReplacementProblem.Replacement -> R.string.tts_replace_error_target
        SpeechReplacementProblem.Budget -> R.string.tts_replace_error_budget
    })
    Text(stringResource(R.string.tts_replace_error_rule, failure.ruleName, reason), color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun RoleError(error: SpeechError, onSources: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onSources) { Text(stringResource(R.string.tts_sources)) }
    }
}
