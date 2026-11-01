package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechRoleRule
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRoleEditorScreen(state: SpeechRolesState, onBack: () -> Unit, onSave: () -> Unit,
    onEdit: ((SpeechRoleRule) -> SpeechRoleRule) -> Unit, onChooseVoice: () -> Unit,
    onPreview: (String) -> Unit, onClearPreview: () -> Unit, onSources: () -> Unit) {
    val draft = state.draft
    var sample by rememberSaveable(draft?.id) { mutableStateOf("") }
    val back = { if (!state.saving) onBack() }
    BackHandler(enabled = state.saving) { }
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_role_edit, back) },
        bottomBar = {
            if (draft != null) RoleSaveBar(state.saving, back, onSave)
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("speech-role-editor"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading || draft == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (draft != null) {
                item { ReplacementField(draft.name, { value -> onEdit { it.copy(name = value) } },
                    stringResource(R.string.tts_replace_name), singleLine = true) }
                item { ReplacementField(draft.regex, { value -> onEdit { it.copy(regex = value) } },
                    stringResource(R.string.tts_role_regex), Modifier.testTag("speech-role-regex")) }
                item {
                    Text(stringResource(R.string.tts_role_regex_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(draft.mode == 2, { onEdit { it.copy(mode = 2) } }, label = { Text(stringResource(R.string.tts_role_mode_character)) })
                        FilterChip(draft.mode == 0, { onEdit { it.copy(mode = 0) } }, label = { Text(stringResource(R.string.tts_role_mode_text)) })
                    }
                }
                if (draft.mode == 2) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!draft.isAbstract, { onEdit { it.copy(isAbstract = false) } }, label = { Text(stringResource(R.string.tts_role_concrete)) })
                        FilterChip(draft.isAbstract, { onEdit { it.copy(isAbstract = true) } }, label = { Text(stringResource(R.string.tts_role_abstract)) })
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.saving, onClick = onChooseVoice).heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.tts_assign_voice), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(draft.speaker?.label ?: stringResource(R.string.tts_role_voice_missing),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp))
                    }
                }
                item { ReplacementField(draft.group, { value -> onEdit { it.copy(group = value) } },
                    stringResource(R.string.tts_replace_group), singleLine = true) }
                item {
                    Text(stringResource(R.string.tts_role_scope), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(draft.bookId == null, { onEdit { it.copy(bookId = null) } }, label = { Text(stringResource(R.string.tts_role_global)) })
                        FilterChip(draft.bookId != null, { onEdit { it.copy(bookId = state.bookId) } }, enabled = state.bookId != null,
                            label = { Text(stringResource(R.string.tts_role_this_book)) })
                    }
                }
                if (draft.mode == 2) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        RoleCheckbox(stringResource(R.string.tts_role_before), draft.matchBefore) { value -> onEdit { it.copy(matchBefore = value) } }
                        RoleCheckbox(stringResource(R.string.tts_role_after), draft.matchAfter) { value -> onEdit { it.copy(matchAfter = value) } }
                    }
                }
                item { RoleCheckbox(stringResource(R.string.tts_replace_enabled), draft.activate) { value -> onEdit { it.copy(activate = value) } } }
                item { ReplacementField(draft.note, { value -> onEdit { it.copy(note = value) } }, stringResource(R.string.tts_role_note)) }
                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                item { ReplacementField(sample, { sample = it; onClearPreview() }, stringResource(R.string.tts_replace_test_input), minLines = 2) }
                item {
                    Button(onClick = { if (state.previewing) onClearPreview() else onPreview(sample) }, enabled = !state.saving) {
                        Text(stringResource(if (state.previewing) R.string.cancel else R.string.tts_role_test_draft))
                    }
                }
                rolePreview(state, { if (!state.saving) onSources() })
            }
        }
    }
}

@Composable
private fun RoleCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked, null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRoleContextScreen(state: SpeechRolesState, onBack: () -> Unit, onSave: (String) -> Unit) {
    var pattern by rememberSaveable(state.settings.roleContextPattern) { mutableStateOf(state.settings.roleContextPattern) }
    val back = { if (!state.saving) onBack() }
    BackHandler(enabled = state.saving) { }
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_role_context_template, back) },
        bottomBar = { RoleSaveBar(state.saving, back) { onSave(pattern) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ReplacementField(pattern, { pattern = it }, stringResource(R.string.tts_role_context_template), minLines = 3) }
            item { Text(stringResource(R.string.tts_role_context_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.error != null) item { Text(stringResource(R.string.save_failed), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RoleSaveBar(saving: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    Surface {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onBack, enabled = !saving) { Text(stringResource(R.string.cancel)) }
            Button(onSave, enabled = !saving) { Text(stringResource(R.string.tts_engine_save)) }
        }
    }
}
