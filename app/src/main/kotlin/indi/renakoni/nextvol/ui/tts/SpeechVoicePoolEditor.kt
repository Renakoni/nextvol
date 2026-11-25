package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge

@Composable
internal fun SpeechVoicePoolEditor(
    state: SpeechVoicePoolState,
    onDismiss: () -> Unit,
    onEdit: ((SpeechVoicePoolDraft) -> SpeechVoicePoolDraft) -> Unit,
    onEngine: (String) -> Unit,
    onGroup: (String) -> Unit,
    onSave: () -> Unit,
) {
    val draft = state.draft ?: return
    val tokens = NextVolDesign.tokens
    val groups = (state.settings.poolGroups() + draft.group).filter { it.isNotEmpty() }.distinct()
    val groupLabels = groups.associateWith { speechPoolGroupLabel(it) }
    val engine = state.engines.find { it.key == state.engineKey }

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        icon = {
            EditorialIconBadge(
                painter = painterResource(R.drawable.tts_text_multiple_voices),
                size = 42.dp,
                iconSize = 21.dp,
            )
        },
        title = {
            Text(
                stringResource(if (draft.id == null && draft.pair == null) R.string.tts_pool_add else R.string.tts_pool_edit),
                fontWeight = FontWeight.W600,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (draft.pair != null) {
                    ReplacementField(
                        value = draft.person,
                        onChange = { value -> onEdit { it.copy(person = value) } },
                        label = stringResource(R.string.tts_pool_person),
                        singleLine = true,
                        readOnly = state.saving,
                    )
                }
                PoolDropdown(
                    value = groupLabels[draft.group].orEmpty(),
                    label = stringResource(R.string.tts_role_abstract),
                    options = groups,
                    optionLabel = { groupLabels.getValue(it) },
                    enabled = !state.saving,
                    onSelect = onGroup,
                )
                PoolDropdown(
                    value = engine?.name.orEmpty(),
                    label = stringResource(R.string.tts_role_engine),
                    options = state.engines,
                    optionLabel = { it.name },
                    enabled = !state.saving && !state.catalogLoading,
                    onSelect = { onEngine(it.key) },
                )
                key(state.engineKey) {
                    PoolDropdown(
                        value = draft.voice?.label.orEmpty(),
                        label = stringResource(R.string.tts_role_voice),
                        options = (listOfNotNull(draft.voice) + engine?.voices.orEmpty()).distinctBy { it.canonicalKey },
                        optionLabel = { it.label },
                        enabled = !state.saving && !state.catalogLoading,
                        onSelect = { voice -> onEdit { it.copy(voice = voice) } },
                    )
                }
                if (state.catalogLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                }
                state.error?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !state.saving && !state.catalogLoading,
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.saving) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PoolDropdown(
    value: String,
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var expanded by remember { mutableStateOf(false) }
    var query by remember(value) { mutableStateOf(value) }
    var filtering by remember(value) { mutableStateOf(false) }
    val matches = if (filtering) options.filter { optionLabel(it).contains(query, ignoreCase = true) } else options
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                expanded = it
                query = value
                filtering = false
            }
        },
    ) {
        ReplacementField(
            value = if (expanded) query else value,
            onChange = {
                query = it
                filtering = true
                expanded = true
            },
            label = label,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled),
            singleLine = true,
            readOnly = !enabled,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled),
                )
            },
        )
        ExposedDropdownMenu(
            expanded = expanded && matches.isNotEmpty(),
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(tokens.popupRadius),
            containerColor = tokens.cardElevatedSurface,
        ) {
            matches.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun SpeechAddToPoolDialog(
    settings: SpeechSettings,
    busy: Boolean,
    error: Int?,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val groups = settings.poolGroups()
    var group by remember { mutableStateOf(groups.first()) }
    val labels = groups.associateWith { speechPoolGroupLabel(it) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        icon = {
            EditorialIconBadge(
                painter = painterResource(R.drawable.person_edit_24px),
                size = 42.dp,
                iconSize = 21.dp,
            )
        },
        title = { Text(stringResource(R.string.tts_pool_join), fontWeight = FontWeight.W600) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.tts_role_abstract),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    groups.forEach { candidate ->
                        FilterChip(
                            selected = candidate == group,
                            onClick = { if (!busy) group = candidate },
                            enabled = !busy,
                            shape = CircleShape,
                            label = { Text(labels.getValue(candidate)) },
                        )
                    }
                }
                error?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(group) },
                enabled = !busy,
                shape = CircleShape,
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
