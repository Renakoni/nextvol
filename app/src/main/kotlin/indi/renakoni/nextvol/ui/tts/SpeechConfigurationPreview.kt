package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.design.EditorialCard

internal fun LazyListScope.speechConfigurationPreview(state: SpeechPackagePreviewState, onImport: (Boolean) -> Unit) {
    val config = state.configuration ?: return
    if (!config.hasChanges && config.error == null && config.unsupported.isEmpty()) return
    item(key = "configuration") {
        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.tts_config_import),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W600,
                )
                config.error?.let { Text(stringResource(R.string.tts_config_invalid, it), color = MaterialTheme.colorScheme.error) }
                if (config.hasChanges) {
                    Text(
                        text = stringResource(
                            R.string.tts_config_counts,
                            config.replacements?.size ?: 0,
                            config.roles?.size ?: 0,
                            config.voiceItems?.size ?: 0,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    )
                    Text(
                        text = stringResource(R.string.tts_config_scope),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.conflicts.isNotEmpty()) {
                    Text(stringResource(R.string.tts_config_conflicts), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
                    Text(state.conflicts.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
                if (config.unresolvedVoices.isNotEmpty()) {
                    Text(stringResource(R.string.tts_config_missing), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
                    Text(config.unresolvedVoices.joinToString("\n") { it.label }, style = MaterialTheme.typography.bodySmall)
                }
                if (config.unsupported.isNotEmpty()) {
                    Text(stringResource(R.string.tts_config_unsupported), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.W600)
                    Text(config.unsupported.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    if (config.unsupported.any { it.endsWith("hanlp.properties") }) {
                        Text(stringResource(R.string.tts_config_hanlp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.configurationSaved) {
                    Text(stringResource(R.string.tts_config_saved), color = MaterialTheme.colorScheme.primary)
                } else if (config.hasChanges && config.error == null) {
                    var replace by rememberSaveable { mutableStateOf(false) }
                    Column(Modifier.selectableGroup()) {
                        listOf(false, true).forEach { choice ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = replace == choice,
                                        enabled = !state.installing,
                                        role = Role.RadioButton,
                                        onClick = { replace = choice },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = replace == choice,
                                    onClick = null,
                                    enabled = !state.installing,
                                    modifier = Modifier.padding(10.dp),
                                )
                                Text(stringResource(if (choice) R.string.tts_config_replace else R.string.tts_config_merge))
                            }
                        }
                    }
                    Text(
                        text = stringResource(if (replace) R.string.tts_config_replace_description else R.string.tts_config_merge_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { onImport(replace) },
                        enabled = !state.installing,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.tts_config_apply))
                    }
                }
                state.installError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    items(config.replacements.orEmpty()) { rule ->
        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 14.dp,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOf(rule.group, rule.name).filter { it.isNotEmpty() }.joinToString(" / "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W600,
                )
                Text("${rule.source} → ${rule.target}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(if (rule.activate) R.string.tts_config_enabled else R.string.tts_config_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(config.roles.orEmpty()) { rule ->
        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 14.dp,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOf(rule.group, rule.label).filter { it.isNotEmpty() }.joinToString(" / "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W600,
                )
                Text(rule.pattern, style = MaterialTheme.typography.bodyMedium)
                Text(rule.speaker?.label.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(if (rule.activate) R.string.tts_config_enabled else R.string.tts_config_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    items(config.voiceItems.orEmpty()) { item ->
        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 14.dp,
            contentPadding = PaddingValues(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${speechPoolGroupLabel(item.group)} / ${item.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W600,
                )
                Text(item.value.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(if (item.activate) R.string.tts_config_enabled else R.string.tts_config_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
