package indi.renakoni.nextvol.ui.tts

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import hnovel.speech.speechExtensionControls
import hnovel.speech.updateSpeechExtension
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfflineVoiceEditorScreen(
    state: OfflineVoiceEditorState, copy: Boolean,
    onEdit: (OfflineVoiceDraft) -> Unit, onSave: () -> Unit, onBack: () -> Unit,
    parametersSupported: Boolean = true,
    showParameters: Boolean = false, showExtensions: Boolean = false,
) {
    val draft = state.draft
    val voice = state.voice
    val context = LocalContext.current
    var avatarError by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && draft != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                onEdit(draft.copy(avatar = uri.toString()))
                avatarError = false
            } catch (_: SecurityException) { avatarError = true }
        }
    }
    val back = { if (!state.busy) onBack() }
    BackHandler(onBack = back)
    val canSave = !state.busy && state.valid
    Scaffold(topBar = { SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(),
        if (copy) R.string.tts_voice_copy else R.string.tts_edit_source, back) }, bottomBar = {
        if (draft != null) Surface {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = back, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
                Button(onClick = onSave, enabled = canSave) {
                    Text(stringResource(R.string.tts_engine_save))
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("offline-voice-editor"),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { Text(stringResource(it), color = MaterialTheme.colorScheme.error) } }
            if (draft != null && voice != null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }, enabled = !state.busy,
                            modifier = Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            SpeechAvatar(draft.avatar, draft.gender.toIntOrNull() ?: 0, Modifier.fillMaxSize(),
                                state.packageAvatar, stringResource(R.string.tts_voice_avatar))
                        }
                    }
                    if (avatarError) Text(stringResource(R.string.tts_voice_avatar_failed), color = MaterialTheme.colorScheme.error)
                }
                item { VoiceText(draft.code, R.string.tts_voice_code, !state.busy) { onEdit(draft.copy(code = it)) } }
                item { VoicePair(
                    { VoiceText(draft.locale, R.string.tts_voice_locale, !state.busy) { onEdit(draft.copy(locale = it)) } },
                    { VoiceText(draft.name, R.string.tts_source_name, !state.busy) { onEdit(draft.copy(name = it)) } },
                ) }
                item { VoicePair(
                    { VoiceChoice(stringResource(R.string.tts_voice_gender), draft.gender,
                        mapOf("0" to stringResource(R.string.tts_voice_female), "1" to stringResource(R.string.tts_voice_male)),
                        !state.busy) { onEdit(draft.copy(gender = it)) } },
                    { VoiceChoice(stringResource(R.string.tts_voice_type), draft.type,
                        mapOf("0" to stringResource(R.string.tts_voice_type_offline)) +
                            (1..5).associate { it.toString() to stringResource(R.string.tts_voice_type_online, it) },
                        !state.busy) { onEdit(draft.copy(type = it)) } },
                ) }
                item { VoicePair(
                    { VoiceNumber(draft.speed, R.string.tts_voice_speed, !state.busy) { onEdit(draft.copy(speed = it)) } },
                    { VoiceNumber(draft.volume, R.string.tts_voice_volume, !state.busy) { onEdit(draft.copy(volume = it)) } },
                ) }
                item { VoicePair(
                    { VoiceNumber(draft.pitch, R.string.tts_pitch, !state.busy) { onEdit(draft.copy(pitch = it)) } },
                    { VoiceChoice(stringResource(R.string.tts_voice_sample_rate), draft.sampleRate, voiceSampleRates,
                        !state.busy) { onEdit(draft.copy(sampleRate = it)) } },
                ) }
                item { TextField(draft.description, { onEdit(draft.copy(description = it)) }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, colors = voiceFieldColors(), maxLines = 4,
                    label = { Text(stringResource(R.string.tts_voice_description)) }) }
                val controls = if (parametersSupported) runCatching { speechExtensionControls(draft.extensions) }.getOrNull() else emptyList()
                if (controls == null) item { Text(stringResource(R.string.tts_voice_extension_invalid), color = MaterialTheme.colorScheme.error) }
                else items(controls.filter { it.name.isNotBlank() }, key = { "control:${it.code}" }) { control ->
                    VoiceChoice(control.name, control.value.content, control.candidates, !state.busy && control.supported) { value ->
                        onEdit(draft.copy(extensions = updateSpeechExtension(draft.extensions, control.code, value)))
                    }
                }
                if (parametersSupported && showParameters) {
                    item { TextField(draft.parameters, { onEdit(draft.copy(parameters = it)) }, Modifier.fillMaxWidth(),
                        enabled = !state.busy, colors = voiceFieldColors(), label = { Text(stringResource(R.string.tts_voice_parameters)) }) }
                }
                item { TextField(draft.note, { onEdit(draft.copy(note = it)) }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, minLines = 3, maxLines = 8, label = { Text(stringResource(R.string.tts_engine_note)) }) }
                if (parametersSupported && showExtensions) item {
                    TextField(draft.extensions, { onEdit(draft.copy(extensions = it)) }, Modifier.fillMaxWidth(),
                        enabled = !state.busy, colors = voiceFieldColors(), label = { Text(stringResource(R.string.tts_voice_extensions)) }, isError = controls == null)
                }
                if (!state.valid) item { Text(stringResource(R.string.tts_voice_invalid), color = MaterialTheme.colorScheme.error) }
                state.availability?.takeUnless { it.canRun }?.let { status -> item {
                    Text(stringResource(status.message, status.detail), style = MaterialTheme.typography.bodySmall,
                        color = if (status.canRun) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                } }
            }
        }
    }
}

@Composable
private fun VoiceText(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    TextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, colors = voiceFieldColors(),
        label = { Text(stringResource(label)) }, singleLine = true)
}

@Composable
private fun VoiceNumber(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    TextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, singleLine = true, colors = voiceFieldColors(),
        label = { Text(stringResource(label)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = enabled && value.toFloatOrNull()?.isFinite() != true)
}

@Composable
private fun voiceFieldColors() = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent)

@Composable
private fun VoicePair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 300.dp * fontScale) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second() }
    }
}

private val voiceSampleRates = linkedMapOf(
    "8000" to "8kHz", "16000" to "16kHz", "24000" to "24kHz", "32000" to "32kHz",
    "48000" to "48kHz", "64000" to "64kHz", "96000" to "96kHz", "22050" to "22.1kHz", "44100" to "44.1kHz",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceChoice(title: String, value: String, choices: Map<String, String>, enabled: Boolean, onChange: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(menu, { if (enabled) menu = it }) {
        TextField(choices[value].orEmpty(), {}, Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            readOnly = true, enabled = enabled, singleLine = true, colors = voiceFieldColors(),
            label = { Text(title) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) })
        ExposedDropdownMenu(menu, { menu = false }) {
            choices.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onChange(key) })
            }
        }
    }
}
