package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechReplacementRule
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechReplacementEditorScreen(
    state: SpeechReplacementsState,
    ruleId: String?,
    onBack: () -> Unit,
    onSave: (SpeechReplacementRule) -> Unit,
    onPreview: (String, SpeechReplacementRule) -> Unit,
    onClearPreview: () -> Unit,
    onClearSaveError: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
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
        if (state.loading) {
            LinearProgressIndicator(Modifier.padding(24.dp).fillMaxWidth().clip(CircleShape))
            return@Surface
        }
        val existing = state.rules.find { it.id == ruleId }
        val id = rememberSaveable { ruleId ?: UUID.randomUUID().toString() }
        var name by rememberSaveable(id) { mutableStateOf(existing?.name.orEmpty()) }
        var group by rememberSaveable(id) { mutableStateOf(existing?.group.orEmpty()) }
        var source by rememberSaveable(id) { mutableStateOf(existing?.source.orEmpty()) }
        var target by rememberSaveable(id) { mutableStateOf(existing?.target.orEmpty()) }
        var regex by rememberSaveable(id) { mutableStateOf(existing?.regex ?: false) }
        var active by rememberSaveable(id) { mutableStateOf(existing?.activate ?: true) }
        var text by rememberSaveable(id) { mutableStateOf("") }
        var groupsOpen by remember { mutableStateOf(false) }
        val groups = state.rules.map { it.group }.filter { it.isNotEmpty() }.distinct()
        val draft = SpeechReplacementRule(id, group, name, source, target, regex, active)
        val defaultGroup = stringResource(R.string.tts_replace_ungrouped)
        LaunchedEffect(source, target, regex, text) { onClearPreview() }
        LaunchedEffect(name, source, regex) { onClearSaveError() }
        DisposableEffect(Unit) { onDispose(onClearPreview) }

        Column(Modifier.testTag("speech-replacement-editor")) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.find_replace_24px),
                    size = 38.dp,
                    iconSize = 19.dp,
                    cornerRadius = 11.dp,
                )
                Text(
                    text = stringResource(if (ruleId == null) R.string.tts_replace_add else R.string.tts_replace_edit),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W600,
                )
                EditorialIconButton(
                    onClick = onBack,
                    enabled = !state.saving,
                    painter = painterResource(R.drawable.close_24px),
                    contentDescription = stringResource(R.string.cancel),
                )
            }
            EditorialHairlineDivider()

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Rule definition card
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReplacementField(
                            value = name,
                            onChange = { name = it },
                            label = stringResource(R.string.tts_replace_name),
                            singleLine = true,
                            error = if (state.saveError == SpeechReplacementSaveError.Name) {
                                stringResource(R.string.tts_replace_name_required)
                            } else {
                                null
                            },
                        )
                        ReplacementField(
                            value = source,
                            onChange = { source = it },
                            label = stringResource(R.string.tts_replace_source),
                            modifier = Modifier.testTag("replace-source"),
                            error = when (state.saveError) {
                                SpeechReplacementSaveError.Source -> stringResource(R.string.tts_replace_source_required)
                                SpeechReplacementSaveError.Pattern -> stringResource(R.string.tts_replace_error_pattern)
                                else -> null
                            },
                        )
                        ReplacementField(
                            value = target,
                            onChange = { target = it },
                            label = stringResource(R.string.tts_replace_target),
                            modifier = Modifier.testTag("replace-target"),
                        )
                        ExposedDropdownMenuBox(
                            expanded = groupsOpen,
                            onExpandedChange = { if (groups.isNotEmpty()) groupsOpen = it },
                        ) {
                            ReplacementField(
                                value = group,
                                onChange = { group = it },
                                label = stringResource(R.string.tts_replace_group),
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
                                singleLine = true,
                                trailingIcon = if (groups.isEmpty()) {
                                    null
                                } else {
                                    {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = groupsOpen,
                                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable),
                                        )
                                    }
                                },
                            )
                            ExposedDropdownMenu(
                                expanded = groupsOpen,
                                onDismissRequest = { groupsOpen = false },
                                shape = RoundedCornerShape(tokens.popupRadius),
                                containerColor = tokens.cardElevatedSurface,
                            ) {
                                groups.forEach { value ->
                                    DropdownMenuItem(
                                        text = { Text(value) },
                                        onClick = {
                                            group = value
                                            groupsOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Switches card
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    ReplacementSwitchRow(stringResource(R.string.tts_replace_regex), regex) { regex = it }
                    EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                    ReplacementSwitchRow(stringResource(R.string.tts_replace_enabled), active) { active = it }
                }

                // Live test sandbox card
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReplacementField(
                            value = text,
                            onChange = { text = it },
                            label = stringResource(R.string.tts_replace_test_input),
                            modifier = Modifier.testTag("replace-preview-input"),
                            trailingIcon = {
                                TextButton(
                                    onClick = { if (state.previewing) onClearPreview() else onPreview(text, draft) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(stringResource(if (state.previewing) R.string.cancel else R.string.tts_replace_test))
                                }
                            },
                        )
                        ReplacementField(
                            value = state.rulePreview.orEmpty(),
                            onChange = {},
                            label = stringResource(R.string.tts_replace_test_output),
                            modifier = Modifier.testTag("replace-test-output"),
                            readOnly = true,
                        )
                        if (state.previewing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        }
                        ReplacementPreviewError(state)
                    }
                }

                if (state.storageFailed) {
                    Text(
                        text = stringResource(R.string.save_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
                TextButton(onClick = onBack, enabled = !state.saving) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onSave(draft.copy(group = group.ifEmpty { defaultGroup })) },
                    enabled = !state.saving,
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.tts_engine_save))
                }
            }
        }
    }
}

@Composable
private fun ReplacementSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 50.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W500,
        )
        MiuixSwitch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
internal fun ReplacementField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    readOnly: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null,
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        singleLine = singleLine,
        minLines = minLines,
        readOnly = readOnly,
        label = { Text(label) },
        trailingIcon = trailingIcon,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
    )
}
