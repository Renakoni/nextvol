package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.speechEngineLogin
import indi.renakoni.nextvol.R
import kotlinx.serialization.json.Json

/** Dialog content; the navigation dialog keeps the engine list underneath it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEngineEditorScreen(configuration: SpeechEngineConfig?, creating: Boolean, state: SpeechEnginesState,
    onBack: () -> Unit, onSave: (SpeechEngineConfig) -> Unit,
    onLogin: (SpeechEngineConfig, (SpeechEngineConfig) -> Unit) -> Unit) {
    val back = { if (!state.saving) onBack() }
    BackHandler(onBack = back)
    Surface(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        if (!creating && state.loading) {
            LinearProgressIndicator(Modifier.padding(24.dp))
            return@Surface
        }
        val original = configuration ?: SpeechEngineConfig("", "", "http")
        var draft by rememberSaveable(original.key, stateSaver = Saver<SpeechEngineConfig, String>(
            save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })) { mutableStateOf(original) }
        var savedParameters by remember { mutableStateOf(configuration?.parameters) }
        LaunchedEffect(configuration?.parameters) {
            if (configuration?.parameters != savedParameters) {
                draft = draft.copy(parameters = configuration?.parameters.orEmpty())
                savedParameters = configuration?.parameters
            }
        }
        Column(Modifier.testTag("speech-engine-editor")) {
            Text(stringResource(if (creating) R.string.tts_engine_new else R.string.tts_engine_edit),
                Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!creating && configuration == null) Text(stringResource(state.error ?: R.string.tts_engine_missing),
                    color = MaterialTheme.colorScheme.error)
                else {
                    EngineField(draft.name, R.string.tts_engine_name, !state.saving) { draft = draft.copy(name = it) }
                    EngineField(draft.code, R.string.tts_engine_code, creating && !state.saving) { draft = draft.copy(code = it) }
                    var typesOpen by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(typesOpen, { if (creating && !state.saving) typesOpen = it }) {
                        TextField(if (draft.type == "http") "HttpTTS" else "innerTTS", {}, Modifier.fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, creating && !state.saving),
                            readOnly = true, enabled = creating && !state.saving, singleLine = true, colors = voiceFieldColors(),
                            label = { Text(stringResource(R.string.tts_engine_type)) },
                            trailingIcon = if (creating) ({ ExposedDropdownMenuDefaults.TrailingIcon(typesOpen) }) else null)
                        ExposedDropdownMenu(typesOpen, { typesOpen = false }) {
                            listOf("http" to "HttpTTS", "inner" to "innerTTS").forEach { (type, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { draft = draft.copy(type = type); typesOpen = false })
                            }
                        }
                    }
                    EngineField(draft.parameters, R.string.tts_engine_parameters, !state.saving, true) { draft = draft.copy(parameters = it) }
                    if (speechEngineLogin(draft.parameters) != null) TextButton(onClick = { onLogin(draft) { draft = it } }, enabled = !state.saving) {
                        Text(stringResource(R.string.tts_engine_login))
                    }
                    if (draft.type == "http") {
                        EngineField(draft.url, R.string.tts_engine_url, !state.saving, true) { draft = draft.copy(url = it) }
                        EngineField(draft.header, R.string.tts_engine_header, !state.saving, true) { draft = draft.copy(header = it) }
                    }
                    EngineField(draft.note, R.string.tts_engine_note, !state.saving, true) { draft = draft.copy(note = it) }
                    state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = back, enabled = !state.saving) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { onSave(draft.copy(name = draft.name.trim(), url = draft.url.trim(), deleted = false,
                    enabled = if (original.deleted) true else draft.enabled)) },
                    enabled = !state.saving && (creating || configuration != null) && draft.name.isNotBlank()) {
                    Text(stringResource(R.string.tts_engine_save))
                }
            }
        }
    }
}

@Composable
private fun EngineField(value: String, title: Int, enabled: Boolean, multiline: Boolean = false, onChange: (String) -> Unit) {
    TextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = !multiline,
        minLines = if (multiline) 3 else 1, maxLines = if (multiline) 8 else 1,
        colors = voiceFieldColors(), label = { Text(stringResource(title)) })
}
