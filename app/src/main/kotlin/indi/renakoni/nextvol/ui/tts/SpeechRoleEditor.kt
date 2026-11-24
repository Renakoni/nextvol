package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.SpeechRoleRule
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.speechDetectionLanguages
import indi.renakoni.nextvol.tts.speechLanguageLabel
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRoleEditorScreen(
    state: SpeechRolesState,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onEdit: ((SpeechRoleRule) -> SpeechRoleRule) -> Unit,
    onEngine: (String) -> Unit,
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
        val draft = state.draft
        if (state.loading || draft == null) {
            LinearProgressIndicator(Modifier.padding(24.dp).fillMaxWidth().clip(CircleShape))
            return@Surface
        }
        val modes = listOf(
            0 to stringResource(R.string.tts_role_mode_text),
            1 to stringResource(R.string.tts_role_mode_language),
            2 to stringResource(R.string.tts_role_mode_character),
        )
        val languageMode = draft.mode == 1 || draft.mode != 0 && draft.pattern.startsWith("lang:")
        val activeModeIndex = modes.indexOfFirst { it.first == if (languageMode) 1 else draft.mode }.coerceAtLeast(0)
        val language = draft.regex.removePrefix("lang:")
        val engine = state.engines.find { it.key == state.engineKey }
        val groups = state.settings.roleRules.map { it.group }.filter { it.isNotEmpty() }.distinct()
        var groupsOpen by remember { mutableStateOf(false) }
        val defaultGroup = stringResource(R.string.tts_replace_ungrouped)
        val existing = state.settings.roleRules.any { it.id == draft.id }

        Column(Modifier.testTag("speech-role-editor")) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.person_edit_24px),
                    size = 38.dp,
                    iconSize = 19.dp,
                    cornerRadius = 11.dp,
                )
                Text(
                    text = stringResource(if (existing) R.string.tts_role_edit else R.string.tts_role_add),
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

            // Scrollable structured body
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Visual segmented mode selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.tts_role_mode),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W600,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    EditorialPillTabRow(
                        tabs = modes.map { it.second },
                        selectedTabIndex = activeModeIndex,
                        onTabSelected = { idx ->
                            if (!state.saving) {
                                val mode = modes[idx]
                                onEdit {
                                    it.copy(
                                        mode = mode.first,
                                        regex = if (mode.first == 1) {
                                            it.regex.takeIf { value -> value.startsWith("lang:") }.orEmpty()
                                        } else {
                                            it.regex.removePrefix("lang:")
                                        },
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Identity & matching pattern card
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReplacementField(
                            value = draft.name,
                            onChange = { value -> onEdit { it.copy(name = value) } },
                            label = stringResource(R.string.tts_role_name),
                            singleLine = true,
                            readOnly = state.saving,
                        )
                        if (languageMode) {
                            RoleDropdown(
                                value = if (language.isEmpty()) "" else speechLanguageLabel(language),
                                label = stringResource(R.string.tts_role_secondary_language),
                                options = speechDetectionLanguages(state.settings.text.languageDetector),
                                optionLabel = { speechLanguageLabel(it) },
                                onSelect = { code ->
                                    onEdit {
                                        it.copy(
                                            mode = 1,
                                            regex = "lang:$code",
                                            name = it.name.ifEmpty { speechLanguageLabel(code) },
                                        )
                                    }
                                },
                                filterable = true,
                                enabled = !state.saving,
                            )
                        } else {
                            ReplacementField(
                                value = draft.regex,
                                onChange = { value -> onEdit { it.copy(regex = value) } },
                                label = stringResource(R.string.tts_role_regex),
                                modifier = Modifier.testTag("speech-role-regex"),
                                readOnly = state.saving,
                            )
                        }
                        ExposedDropdownMenuBox(
                            expanded = groupsOpen,
                            onExpandedChange = { if (!state.saving && groups.isNotEmpty()) groupsOpen = it },
                        ) {
                            ReplacementField(
                                value = draft.group,
                                onChange = { value -> onEdit { it.copy(group = value) } },
                                label = stringResource(R.string.tts_role_group),
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = !state.saving),
                                singleLine = true,
                                readOnly = state.saving,
                                trailingIcon = if (groups.isEmpty()) {
                                    null
                                } else {
                                    {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = groupsOpen,
                                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled = !state.saving),
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
                                            onEdit { it.copy(group = value) }
                                            groupsOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Voice assignment card
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RoleDropdown(
                            value = engine?.name.orEmpty(),
                            label = stringResource(R.string.tts_role_engine),
                            options = state.engines,
                            optionLabel = { it.name },
                            onSelect = { onEngine(it.key) },
                            filterable = true,
                            enabled = !state.saving && !state.catalogLoading,
                        )
                        key(state.engineKey) {
                            RoleDropdown(
                                value = draft.speaker?.label.orEmpty(),
                                label = stringResource(R.string.tts_role_voice),
                                options = (listOfNotNull(draft.speaker) + engine?.voices.orEmpty()).distinctBy { it.canonicalKey },
                                optionLabel = { it.label },
                                onSelect = { voice -> onEdit { it.copy(speaker = voice) } },
                                filterable = true,
                                enabled = !state.saving && !state.catalogLoading,
                            )
                        }
                        if (state.catalogLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        }
                    }
                }

                // Behavior switches card
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    RoleSwitchRow(
                        label = stringResource(R.string.tts_role_activate),
                        checked = draft.activate,
                        enabled = !state.saving,
                    ) { value -> onEdit { it.copy(activate = value) } }
                    EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                    RoleSwitchRow(
                        label = stringResource(R.string.tts_role_abstract),
                        checked = draft.isAbstract,
                        enabled = !state.saving,
                    ) { value -> onEdit { it.copy(isAbstract = value) } }
                    if (!languageMode) {
                        EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                        RoleSwitchRow(
                            label = stringResource(R.string.tts_role_before),
                            checked = draft.matchBefore,
                            enabled = !state.saving,
                        ) { value -> onEdit { it.copy(matchBefore = value) } }
                        EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                        RoleSwitchRow(
                            label = stringResource(R.string.tts_role_after),
                            checked = draft.matchAfter,
                            enabled = !state.saving,
                        ) { value -> onEdit { it.copy(matchAfter = value) } }
                    }
                }

                state.saveError?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.error?.let {
                    Text(
                        text = stringResource(it.messageResource),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            EditorialHairlineDivider()
            // Footer actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { onEdit { it.copy(isAbstract = false, activate = true, matchBefore = true, matchAfter = true) } },
                    enabled = !state.saving,
                ) {
                    Text(stringResource(R.string.tts_role_defaults))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack, enabled = !state.saving) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onSave(defaultGroup) },
                    enabled = !state.saving,
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> RoleDropdown(
    value: String,
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    filterable: Boolean = false,
    enabled: Boolean = true,
) {
    val tokens = NextVolDesign.tokens
    var expanded by remember { mutableStateOf(false) }
    var query by remember(value) { mutableStateOf(value) }
    var filter by remember(value) { mutableStateOf<String?>(null) }
    val matches = filter?.let { text -> options.filter { optionLabel(it).contains(text, ignoreCase = true) } } ?: options
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            if (enabled) {
                expanded = open
                query = value
                filter = null
            }
        },
    ) {
        ReplacementField(
            value = if (expanded) query else value,
            onChange = { text ->
                query = text
                filter = text
                expanded = true
            },
            label = label,
            modifier = Modifier
                .onFocusChanged { if (filterable && !it.isFocused) expanded = false }
                .menuAnchor(
                    if (filterable) ExposedDropdownMenuAnchorType.PrimaryEditable
                    else ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                    enabled = enabled,
                ),
            singleLine = true,
            readOnly = !filterable || !enabled,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded,
                    modifier = if (filterable) {
                        Modifier.menuAnchor(ExposedDropdownMenuAnchorType.SecondaryEditable, enabled = enabled)
                    } else {
                        Modifier
                    },
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
private fun RoleSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
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
        MiuixSwitch(
            checked = checked,
            onCheckedChange = { if (enabled) onChange(it) },
            enabled = enabled,
        )
    }
}
