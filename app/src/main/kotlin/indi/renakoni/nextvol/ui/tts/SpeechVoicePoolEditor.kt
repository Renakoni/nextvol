package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechSettings

@Composable
internal fun SpeechVoicePoolEditor(state: SpeechVoicePoolState, onDismiss: () -> Unit,
    onEdit: ((SpeechVoicePoolDraft) -> SpeechVoicePoolDraft) -> Unit, onEngine: (String) -> Unit,
    onGroup: (String) -> Unit, onSave: () -> Unit) {
    val draft = state.draft ?: return
    val groups = (state.settings.poolGroups() + draft.group).filter { it.isNotEmpty() }.distinct()
    val groupLabels = groups.associateWith { speechPoolGroupLabel(it) }
    val engine = state.engines.find { it.key == state.engineKey }
    AlertDialog(onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(stringResource(if (draft.id == null && draft.pair == null) R.string.tts_pool_add else R.string.tts_pool_edit)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.pair != null) ReplacementField(draft.person, { value -> onEdit { it.copy(person = value) } },
                    stringResource(R.string.tts_pool_person), singleLine = true, readOnly = state.saving)
                PoolDropdown(groupLabels[draft.group].orEmpty(), stringResource(R.string.tts_role_abstract),
                    groups, { groupLabels.getValue(it) }, !state.saving, onGroup)
                PoolDropdown(engine?.name.orEmpty(), stringResource(R.string.tts_role_engine),
                    state.engines, { it.name }, !state.saving && !state.catalogLoading) { onEngine(it.key) }
                key(state.engineKey) {
                    PoolDropdown(draft.voice?.label.orEmpty(), stringResource(R.string.tts_role_voice),
                        (engine?.candidates.orEmpty() + listOfNotNull(draft.voice)).distinctBy { it.key },
                        { it.label }, !state.saving && !state.catalogLoading) { voice -> onEdit { it.copy(voice = voice) } }
                }
                if (state.catalogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = onSave, enabled = !state.saving && !state.catalogLoading) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.saving) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PoolDropdown(value: String, label: String, options: List<T>, optionLabel: (T) -> String,
    enabled: Boolean, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(value) { mutableStateOf(value) }
    var filtering by remember(value) { mutableStateOf(false) }
    val matches = if (filtering) options.filter { optionLabel(it).contains(query, ignoreCase = true) } else options
    ExposedDropdownMenuBox(expanded, { if (enabled) { expanded = it; query = value; filtering = false } }) {
        ReplacementField(if (expanded) query else value, { query = it; filtering = true; expanded = true }, label,
            Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled), singleLine = true, readOnly = !enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded,
                Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled)) })
        ExposedDropdownMenu(expanded && matches.isNotEmpty(), { expanded = false }) {
            matches.forEach { option -> DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable
internal fun SpeechAddToPoolDialog(settings: SpeechSettings, busy: Boolean, error: Int?, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var group by remember { mutableStateOf(settings.poolGroups().first()) }
    val groups = settings.poolGroups()
    val labels = groups.associateWith { speechPoolGroupLabel(it) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(R.string.tts_pool_join)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PoolDropdown(labels[group].orEmpty(), stringResource(R.string.tts_role_abstract), groups, { labels.getValue(it) }, !busy) { group = it }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { onAdd(group) }, enabled = !busy) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}
