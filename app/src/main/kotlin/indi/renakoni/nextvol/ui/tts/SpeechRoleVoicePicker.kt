package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechSingleVoicePickerScreen(choices: List<SpeechVoiceRef>, selected: SpeechVoiceRef?, label: String,
    @androidx.annotation.StringRes unassignedLabel: Int, loading: Boolean, error: SpeechError?,
    onBack: () -> Unit, onSelect: (SpeechVoiceRef?) -> Unit, onSources: () -> Unit,
    roleFailure: SpeechRoleFailure? = null, onRoles: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val available = choices.map { it.key }.toSet()
    val voices = (listOfNotNull(selected) + choices).distinctBy { it.key }
        .filter { query.isBlank() || it.label.contains(query, true) }
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_assign_voice, onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("speech-voice-picker"), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    ReplacementField(query, { query = it }, stringResource(R.string.tts_voice_search), singleLine = true)
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) }
            item {
                Row(Modifier.fillMaxWidth().selectable(selected == null, role = Role.RadioButton, onClick = { onSelect(null) })
                    .padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == null, onClick = null)
                    Text(stringResource(unassignedLabel), Modifier.padding(start = 16.dp))
                }
            }
            items(voices, key = { it.key }) { voice ->
                val checked = selected?.key == voice.key
                val missing = voice.key !in available
                Row(Modifier.fillMaxWidth().selectable(checked, enabled = !missing, role = Role.RadioButton) { onSelect(voice) }
                    .padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RadioButton(checked, onClick = null, enabled = !missing)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(voice.label, style = MaterialTheme.typography.bodyLarge)
                        if (missing) Text(stringResource(R.string.tts_voice_unavailable), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (voices.isEmpty() && !loading) item {
                Text(stringResource(R.string.search_no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { TextButton(onSources, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.tts_sources)) } }
            error?.let { item { RoutingError(it, onSources, roleFailure, onRoles) } }
        }
    }
}

@Composable
private fun RoutingError(error: SpeechError, onSources: () -> Unit, roleFailure: SpeechRoleFailure? = null,
    onRoles: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (roleFailure != null) SpeechRoleError(roleFailure)
        else Text(stringResource(error.messageResource), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (error == SpeechError.RoleRule) {
            if (onRoles != null) TextButton(onRoles) { Text(stringResource(R.string.tts_roles)) }
        } else TextButton(onSources) { Text(stringResource(R.string.tts_sources)) }
    }
}
