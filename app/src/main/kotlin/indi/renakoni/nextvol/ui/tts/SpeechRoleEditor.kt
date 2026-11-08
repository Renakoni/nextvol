package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechRoleRule
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.speechDetectionLanguages
import indi.renakoni.nextvol.tts.speechLanguageLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRoleEditorScreen(state: SpeechRolesState, onBack: () -> Unit, onSave: (String) -> Unit,
    onEdit: ((SpeechRoleRule) -> SpeechRoleRule) -> Unit, onEngine: (String) -> Unit) {
    Surface(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        val draft = state.draft
        if (state.loading || draft == null) {
            LinearProgressIndicator(Modifier.padding(24.dp))
            return@Surface
        }
        val modes = listOf(0 to stringResource(R.string.tts_role_mode_text),
            1 to stringResource(R.string.tts_role_mode_language),
            2 to stringResource(R.string.tts_role_mode_character))
        val languageMode = draft.mode == 1 || draft.mode != 0 && draft.pattern.startsWith("lang:")
        val language = draft.regex.removePrefix("lang:")
        val engine = state.engines.find { it.key == state.engineKey }
        val groups = state.settings.roleRules.map { it.group }.filter { it.isNotEmpty() }.distinct()
        var groupsOpen by remember { mutableStateOf(false) }
        val defaultGroup = stringResource(R.string.tts_replace_ungrouped)
        val existing = state.settings.roleRules.any { it.id == draft.id }
        Column(Modifier.testTag("speech-role-editor")) {
            Text(stringResource(if (existing) R.string.tts_role_edit else R.string.tts_role_add),
                Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                style = MaterialTheme.typography.headlineSmall)
            SpeechSchemeCaption(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RoleDropdown(modes.find { it.first == if (languageMode) 1 else draft.mode }?.second.orEmpty(), stringResource(R.string.tts_role_mode),
                    modes, { it.second }, { mode -> onEdit { it.copy(mode = mode.first,
                        regex = if (mode.first == 1) it.regex.takeIf { value -> value.startsWith("lang:") }.orEmpty()
                            else it.regex.removePrefix("lang:")) } }, enabled = !state.saving)
                ReplacementField(draft.name, { value -> onEdit { it.copy(name = value) } },
                    stringResource(R.string.tts_role_name), singleLine = true, readOnly = state.saving)
                if (languageMode) {
                    RoleDropdown(if (language.isEmpty()) "" else speechLanguageLabel(language), stringResource(R.string.tts_role_secondary_language),
                        speechDetectionLanguages(state.settings.text.languageDetector), { speechLanguageLabel(it) },
                        { code -> onEdit { it.copy(mode = 1, regex = "lang:$code", name = it.name.ifEmpty { speechLanguageLabel(code) }) } },
                        filterable = true, enabled = !state.saving)
                } else ReplacementField(draft.regex, { value -> onEdit { it.copy(regex = value) } },
                    stringResource(R.string.tts_role_regex), Modifier.testTag("speech-role-regex"), readOnly = state.saving)
                RoleDropdown(engine?.name.orEmpty(), stringResource(R.string.tts_role_engine),
                    state.engines, { it.name }, { onEngine(it.key) }, filterable = true,
                    enabled = !state.saving && !state.catalogLoading)
                key(state.engineKey) {
                    RoleDropdown(draft.speaker?.label.orEmpty(), stringResource(R.string.tts_role_voice),
                        (listOfNotNull(draft.speaker) + engine?.voices.orEmpty()).distinctBy { it.canonicalKey },
                        { it.label }, { voice -> onEdit { it.copy(speaker = voice) } },
                        filterable = true, enabled = !state.saving && !state.catalogLoading)
                }
                if (state.catalogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                ExposedDropdownMenuBox(groupsOpen, { if (!state.saving && groups.isNotEmpty()) groupsOpen = it }) {
                    ReplacementField(draft.group, { value -> onEdit { it.copy(group = value) } },
                        stringResource(R.string.tts_role_group),
                        Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = !state.saving),
                        singleLine = true, readOnly = state.saving,
                        trailingIcon = if (groups.isEmpty()) null else {
                            { ExposedDropdownMenuDefaults.TrailingIcon(groupsOpen,
                                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled = !state.saving)) }
                        })
                    ExposedDropdownMenu(groupsOpen, { groupsOpen = false }) {
                        groups.forEach { value -> DropdownMenuItem(text = { Text(value) },
                            onClick = { onEdit { it.copy(group = value) }; groupsOpen = false }) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoleCheckbox(stringResource(R.string.tts_role_abstract), draft.isAbstract, !state.saving,
                        Modifier.weight(1f)) { value -> onEdit { it.copy(isAbstract = value) } }
                    RoleCheckbox(stringResource(R.string.tts_role_activate), draft.activate, !state.saving,
                        Modifier.weight(1f)) { value -> onEdit { it.copy(activate = value) } }
                }
                if (!languageMode) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoleCheckbox(stringResource(R.string.tts_role_before), draft.matchBefore, !state.saving,
                        Modifier.weight(1f)) { value -> onEdit { it.copy(matchBefore = value) } }
                    RoleCheckbox(stringResource(R.string.tts_role_after), draft.matchAfter, !state.saving,
                        Modifier.weight(1f)) { value -> onEdit { it.copy(matchAfter = value) } }
                }
                state.saveError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(stringResource(it.messageResource), color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onEdit { it.copy(isAbstract = false, activate = true, matchBefore = true, matchAfter = true) } },
                    enabled = !state.saving) { Text(stringResource(R.string.tts_role_defaults)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack, enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onSave(defaultGroup) }, enabled = !state.saving) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RoleDropdown(value: String, label: String, options: List<T>, optionLabel: (T) -> String,
    onSelect: (T) -> Unit, filterable: Boolean = false, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(value) { mutableStateOf(value) }
    var filter by remember(value) { mutableStateOf<String?>(null) }
    val matches = filter?.let { text -> options.filter { optionLabel(it).contains(text, ignoreCase = true) } } ?: options
    ExposedDropdownMenuBox(expanded, { open ->
        if (enabled) {
            expanded = open
            query = value
            filter = null
        }
    }) {
        ReplacementField(if (expanded) query else value, { text ->
            query = text
            filter = text
            expanded = true
        }, label,
            Modifier.onFocusChanged { if (filterable && !it.isFocused) expanded = false }
                .menuAnchor(if (filterable) ExposedDropdownMenuAnchorType.PrimaryEditable
                else ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
            singleLine = true, readOnly = !filterable || !enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded,
                if (filterable) Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled = enabled)
                else Modifier) })
        ExposedDropdownMenu(expanded && matches.isNotEmpty(), { expanded = false }) {
            matches.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) },
                    onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun RoleCheckbox(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier,
    onChange: (Boolean) -> Unit) {
    Row(modifier.heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked, null, enabled = enabled)
    }
}
