package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hnovel.speech.SpeechEngineConfig
import hnovel.speech.speechEngineLogin
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.key
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import kotlinx.serialization.json.Json

/** Dialog content; the navigation dialog keeps the engine list underneath it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEngineEditorScreen(
    configuration: SpeechEngineConfig?,
    creating: Boolean,
    state: SpeechEnginesState,
    onBack: () -> Unit,
    onSave: (SpeechEngineConfig) -> Unit,
    onLogin: (SpeechEngineConfig, (SpeechEngineConfig) -> Unit) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val back = { if (!state.saving) onBack() }
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
        if (!creating && state.loading) {
            LinearProgressIndicator(Modifier.padding(24.dp).fillMaxWidth().clip(CircleShape))
            return@Surface
        }
        val original = configuration ?: SpeechEngineConfig("", "", "http")
        var draft by rememberSaveable(
            original.key,
            stateSaver = Saver<SpeechEngineConfig, String>(
                save = { Json.encodeToString(it) },
                restore = { Json.decodeFromString(it) },
            ),
        ) { mutableStateOf(original) }
        var savedParameters by remember { mutableStateOf(configuration?.parameters) }
        LaunchedEffect(configuration?.parameters) {
            if (configuration?.parameters != savedParameters) {
                draft = draft.copy(parameters = configuration?.parameters.orEmpty())
                savedParameters = configuration?.parameters
            }
        }

        Column(Modifier.testTag("speech-engine-editor")) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.settings_applications_24px),
                    size = 38.dp,
                    iconSize = 19.dp,
                    cornerRadius = 11.dp,
                )
                Text(
                    text = stringResource(if (creating) R.string.tts_engine_new else R.string.tts_engine_edit),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W600,
                )
                EditorialIconButton(
                    onClick = back,
                    enabled = !state.saving,
                    painter = painterResource(R.drawable.close_24px),
                    contentDescription = stringResource(R.string.cancel),
                )
            }
            EditorialHairlineDivider()

            BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                val horizontalPairs = maxWidth - 40.dp >= 280.dp * LocalDensity.current.fontScale
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!creating && configuration == null) {
                        Text(
                            text = stringResource(state.error ?: R.string.tts_engine_missing),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        VoicePair(
                            horizontalPairs,
                            { EngineField(draft.name, R.string.tts_engine_name, !state.saving) { draft = draft.copy(name = it) } },
                            {
                                var typesOpen by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(
                                    expanded = typesOpen,
                                    onExpandedChange = { if (creating && !state.saving) typesOpen = it },
                                ) {
                                    OutlinedTextField(
                                        value = if (draft.type == "http") "HttpTTS" else "innerTTS",
                                        onValueChange = {},
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, creating && !state.saving),
                                        readOnly = true,
                                        enabled = !state.saving,
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        label = { Text(stringResource(R.string.tts_engine_type)) },
                                        trailingIcon = if (creating) ({ ExposedDropdownMenuDefaults.TrailingIcon(typesOpen) }) else null,
                                    )
                                    ExposedDropdownMenu(
                                        expanded = typesOpen,
                                        onDismissRequest = { typesOpen = false },
                                        shape = RoundedCornerShape(tokens.popupRadius),
                                        containerColor = tokens.cardElevatedSurface,
                                    ) {
                                        listOf("http" to "HttpTTS", "inner" to "innerTTS").forEach { (type, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    draft = draft.copy(type = type)
                                                    typesOpen = false
                                                },
                                            )
                                        }
                                    }
                                }
                            },
                        )
                        EngineField(draft.code, R.string.tts_engine_code, !state.saving, readOnly = !creating) {
                            draft = draft.copy(code = it)
                        }
                        EngineField(draft.parameters, R.string.tts_engine_parameters, !state.saving, maxLines = 12) {
                            draft = draft.copy(parameters = it)
                        }
                        if (speechEngineLogin(draft.parameters) != null) {
                            FilledTonalButton(
                                onClick = { onLogin(draft) { draft = it } },
                                enabled = !state.saving,
                                shape = CircleShape,
                            ) {
                                Text(stringResource(R.string.tts_engine_login))
                            }
                        }
                        if (draft.type == "http") {
                            EngineField(draft.url, R.string.tts_engine_url, !state.saving, maxLines = 3) {
                                draft = draft.copy(url = it)
                            }
                            EngineField(draft.header, R.string.tts_engine_header, !state.saving, maxLines = 5) {
                                draft = draft.copy(header = it)
                            }
                        }
                        EngineField(draft.note, R.string.tts_engine_note, !state.saving, maxLines = 4) {
                            draft = draft.copy(note = it)
                        }
                        state.error?.let {
                            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            EditorialHairlineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = back, enabled = !state.saving) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onSave(
                            draft.copy(
                                name = draft.name.trim(),
                                url = draft.url.trim(),
                                deleted = false,
                                enabled = if (original.deleted) true else draft.enabled,
                            ),
                        )
                    },
                    enabled = !state.saving && (creating || configuration != null) && draft.name.isNotBlank(),
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.tts_engine_save))
                }
            }
        }
    }
}

@Composable
private fun EngineField(
    value: String,
    title: Int,
    enabled: Boolean,
    maxLines: Int = 1,
    readOnly: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = maxLines == 1,
        maxLines = maxLines,
        shape = RoundedCornerShape(14.dp),
        label = { Text(stringResource(title)) },
    )
}
