package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import hnovel.speech.updateSpeechExtension
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceEditorScreen(
    state: VoiceEditorState,
    copy: Boolean,
    onEdit: (VoiceDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onToggleEnabled: () -> Unit,
    parametersSupported: Boolean = true,
    showParameters: Boolean = false,
    showExtensions: Boolean = false,
    newVoice: Boolean = false,
) {
    val tokens = NextVolDesign.tokens
    val draft = state.draft
    val sovits = draft?.sovits
    val genericParameters = parametersSupported && sovits == null
    val fontScale = LocalDensity.current.fontScale
    val pickAvatar = rememberSpeechAvatarPicker { avatar -> draft?.let { onEdit(it.copy(avatar = avatar)) } }
    val back = { if (!state.busy || draft == null) onBack() }
    BackHandler(onBack = back)

    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 20.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(tokens.sheetRadius),
        color = tokens.cardElevatedSurface,
        border = BorderStroke(0.75.dp, tokens.cardBorder),
        shadowElevation = 12.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (draft != null) {
                    IconButton(
                        onClick = pickAvatar,
                        enabled = !state.busy,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        SpeechAvatar(
                            draft.avatar,
                            draft.gender.toIntOrNull() ?: 0,
                            Modifier.fillMaxSize(),
                            state.packageAvatar,
                            stringResource(R.string.tts_voice_avatar),
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (newVoice) R.string.tts_engine_add_speaker
                        else if (copy) R.string.tts_voice_copy
                        else R.string.tts_edit_source,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W600,
                )
                EditorialIconButton(
                    onClick = back,
                    enabled = !state.busy || draft == null,
                    painter = painterResource(R.drawable.close_24px),
                    contentDescription = stringResource(R.string.cancel),
                )
            }
            EditorialHairlineDivider()

            BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                val horizontalPairs = maxWidth - 40.dp >= 280.dp * fontScale
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offline-voice-editor"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
                    state.error?.let {
                        item { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    if (draft != null) {
                        item { VoiceText(draft.code, R.string.tts_voice_code, !state.busy) { onEdit(draft.copy(code = it)) } }
                        item {
                            VoicePair(
                                horizontalPairs,
                                { VoiceText(draft.locale, R.string.tts_voice_locale, !state.busy) { onEdit(draft.copy(locale = it)) } },
                                { VoiceText(draft.name, R.string.tts_source_name, !state.busy) { onEdit(draft.copy(name = it)) } },
                            )
                        }
                        item {
                            VoicePair(
                                horizontalPairs,
                                {
                                    VoiceChoice(
                                        stringResource(R.string.tts_voice_gender),
                                        draft.gender,
                                        mapOf("0" to stringResource(R.string.tts_voice_female), "1" to stringResource(R.string.tts_voice_male)),
                                        !state.busy,
                                    ) { onEdit(draft.copy(gender = it)) }
                                },
                                {
                                    VoiceChoice(
                                        stringResource(R.string.tts_voice_type),
                                        draft.type,
                                        mapOf("0" to stringResource(R.string.tts_voice_type_offline)) +
                                            (1..5).associate { it.toString() to stringResource(R.string.tts_voice_type_online, it) },
                                        !state.busy && sovits == null,
                                    ) { onEdit(draft.copy(type = it)) }
                                },
                            )
                        }
                        if (sovits != null) {
                            item {
                                VoicePair(
                                    horizontalPairs,
                                    { VoiceNumber(draft.speed, R.string.tts_voice_speed, !state.busy, 0.6f..1.65f) { onEdit(draft.copy(speed = it)) } },
                                    { VoiceChoice(stringResource(R.string.tts_voice_sample_rate), "32000", voiceSampleRates, false) {} },
                                )
                            }
                            item {
                                VoicePair(
                                    horizontalPairs,
                                    {
                                        VoiceNumber(sovits.temperature, R.string.sovits_temperature, !state.busy, 0f..1f) {
                                            onEdit(draft.copy(sovits = sovits.copy(temperature = it)))
                                        }
                                    },
                                    {
                                        VoiceNumber(sovits.topK, R.string.sovits_top_k, !state.busy, 1f..100f, integral = true) {
                                            onEdit(draft.copy(sovits = sovits.copy(topK = it)))
                                        }
                                    },
                                )
                            }
                            item {
                                VoicePair(
                                    horizontalPairs,
                                    {
                                        VoiceNumber(sovits.topP, R.string.sovits_top_p, !state.busy, 0f..1f) {
                                            onEdit(draft.copy(sovits = sovits.copy(topP = it)))
                                        }
                                    },
                                    {
                                        VoiceNumber(sovits.repetitionPenalty, R.string.sovits_repetition_penalty, !state.busy, 1f..2f) {
                                            onEdit(draft.copy(sovits = sovits.copy(repetitionPenalty = it)))
                                        }
                                    },
                                )
                            }
                        } else {
                            item {
                                VoicePair(
                                    horizontalPairs,
                                    { VoiceNumber(draft.speed, R.string.tts_voice_speed, !state.busy) { onEdit(draft.copy(speed = it)) } },
                                    { VoiceNumber(draft.volume, R.string.tts_voice_volume, !state.busy) { onEdit(draft.copy(volume = it)) } },
                                )
                            }
                            item {
                                VoicePair(
                                    horizontalPairs,
                                    { VoiceNumber(draft.pitch, R.string.tts_pitch, !state.busy) { onEdit(draft.copy(pitch = it)) } },
                                    {
                                        VoiceChoice(stringResource(R.string.tts_voice_sample_rate), draft.sampleRate, voiceSampleRates, !state.busy) {
                                            onEdit(draft.copy(sampleRate = it))
                                        }
                                    },
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = draft.description,
                                onValueChange = { onEdit(draft.copy(description = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                                shape = RoundedCornerShape(14.dp),
                                maxLines = 4,
                                label = { Text(stringResource(R.string.tts_voice_description)) },
                            )
                        }
                        val controls = if (genericParameters) state.controls else emptyList()
                        if (controls == null && !state.loadingControls) {
                            item {
                                Text(
                                    stringResource(R.string.tts_voice_extension_invalid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        items(controls.orEmpty().filter { it.name.isNotBlank() }, key = { "control:${it.code}" }) { control ->
                            VoiceChoice(
                                control.name,
                                control.value.content,
                                control.candidates,
                                !state.busy && !state.loadingControls && control.supported,
                            ) { value ->
                                onEdit(draft.copy(extensions = updateSpeechExtension(draft.extensions, control.code, value)))
                            }
                        }
                        val emptyCode = newVoice || state.emptyCode
                        if (genericParameters && (showParameters || emptyCode)) {
                            item {
                                OutlinedTextField(
                                    value = draft.parameters,
                                    onValueChange = { onEdit(draft.copy(parameters = it)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.busy,
                                    shape = RoundedCornerShape(14.dp),
                                    maxLines = 8,
                                    label = { Text(stringResource(R.string.tts_voice_parameters)) },
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = draft.note,
                                onValueChange = { onEdit(draft.copy(note = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                                shape = RoundedCornerShape(14.dp),
                                maxLines = 4,
                                label = { Text(stringResource(R.string.tts_engine_note)) },
                            )
                        }
                        if (genericParameters && (showExtensions || emptyCode)) {
                            item {
                                OutlinedTextField(
                                    value = draft.extensions,
                                    onValueChange = { onEdit(draft.copy(extensions = it)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.busy,
                                    shape = RoundedCornerShape(14.dp),
                                    maxLines = 8,
                                    label = { Text(stringResource(R.string.tts_voice_extensions)) },
                                    isError = controls == null,
                                )
                            }
                        }
                        state.availability?.takeUnless { it.canRun }?.let { status ->
                            item {
                                Text(
                                    text = stringResource(status.message, status.detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            EditorialHairlineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (draft != null && !copy && !newVoice) {
                    TextButton(
                        onClick = onToggleEnabled,
                        enabled = !state.busy,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (draft.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(stringResource(if (draft.enabled) R.string.tts_voice_disable else R.string.tts_voice_enable))
                    }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = back, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = !state.busy && draft != null,
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.tts_engine_save))
                }
            }
        }
    }
}

@Composable
private fun VoiceText(value: String, label: Int, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        label = { Text(stringResource(label)) },
        singleLine = true,
    )
}

@Composable
private fun VoiceNumber(
    value: String,
    label: Int,
    enabled: Boolean,
    range: ClosedFloatingPointRange<Float>? = null,
    integral: Boolean = false,
    onChange: (String) -> Unit,
) {
    val number = value.toFloatOrNull()
    val valid = number != null && (range?.contains(number) ?: number.isFinite()) && (!integral || value.toIntOrNull() != null)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        label = { Text(stringResource(label)) },
        supportingText = range?.let {
            { Text(if (integral) "${it.start.toInt()}–${it.endInclusive.toInt()}" else "${it.start}–${it.endInclusive}") }
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (integral) KeyboardType.Number else KeyboardType.Decimal),
        isError = enabled && !valid,
    )
}

@Composable
internal fun VoicePair(horizontal: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (horizontal) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            first()
            second()
        }
    }
}

private val voiceSampleRates = linkedMapOf(
    "8000" to "8kHz", "16000" to "16kHz", "24000" to "24kHz", "32000" to "32kHz",
    "48000" to "48kHz", "64000" to "64kHz", "96000" to "96kHz", "22050" to "22.1kHz", "44100" to "44.1kHz",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceChoice(title: String, value: String, choices: Map<String, String>, enabled: Boolean, onChange: (String) -> Unit) {
    val tokens = NextVolDesign.tokens
    var menu by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(menu, { if (enabled) menu = it }) {
        OutlinedTextField(
            value = choices[value].orEmpty(),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menu) },
        )
        ExposedDropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = RoundedCornerShape(tokens.popupRadius),
            containerColor = tokens.cardElevatedSurface,
        ) {
            choices.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        menu = false
                        onChange(key)
                    },
                )
            }
        }
    }
}
