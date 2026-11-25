package indi.renakoni.nextvol.ui.home.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SwitchChip
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryResultsFilters(
    definitions: List<DiscoveryFilter>,
    values: Map<String, String>,
    onDismiss: () -> Unit,
    onApply: (Map<String, String>) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var draft by rememberSaveable(definitions, values) { mutableStateOf(values) }
    val valid = definitions.all { validDiscoveryFilter(it, draft[it.id].orEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.discovery_filters),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                )
                TextButton(onClick = { draft = filterValues(definitions, emptyMap()) }) {
                    Text(stringResource(R.string.discovery_reset_filters))
                }
            }
            EditorialHairlineDivider(Modifier.padding(top = 6.dp))
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(definitions, key = { it.id }) { filter ->
                    DiscoveryResultsFilter(filter, draft[filter.id].orEmpty()) {
                        draft = draft + (filter.id to it)
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.discovery_filter_paging_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            EditorialHairlineDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onApply(draft) },
                    enabled = valid,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp),
                ) {
                    Text(
                        text = stringResource(R.string.discovery_apply_filters),
                        fontWeight = FontWeight.W600,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveryResultsFilter(filter: DiscoveryFilter, value: String, onChange: (String) -> Unit) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    when (filter) {
        is DiscoveryFilter.Toggle -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { onChange((value != "true").toString()) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = filter.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            MiuixSwitch(
                checked = value == "true",
                onCheckedChange = { onChange(it.toString()) },
            )
        }
        is DiscoveryFilter.Choice -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = filter.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                color = colors.onSurface,
            )
            if (filter.options.size <= 4) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    filter.options.forEach { (key, label) ->
                        SwitchChip(
                            label = label,
                            selected = value == key,
                            onClick = { onChange(key) },
                        )
                    }
                }
            } else {
                var expanded by remember { mutableStateOf(false) }
                val fieldShape = RoundedCornerShape(tokens.controlRadius)
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(fieldShape)
                            .background(tokens.cardSurface)
                            .border(0.75.dp, tokens.cardBorder, fieldShape)
                            .clickable { expanded = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = filter.options[value].orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            painter = painterResource(R.drawable.search_expand_24px),
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    EditorialPopupMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        filter.options.forEach { (key, label) ->
                            EditorialPopupMenuItem(
                                text = label,
                                selected = key == value,
                                onClick = {
                                    expanded = false
                                    onChange(key)
                                },
                            )
                        }
                    }
                }
            }
        }
        is DiscoveryFilter.Number -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(filter.title) },
            singleLine = true,
            shape = RoundedCornerShape(tokens.controlRadius),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !validDiscoveryFilter(filter, value),
            supportingText = { Text(stringResource(R.string.discovery_number_range, filter.min, filter.max)) },
        )
        is DiscoveryFilter.Text -> OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= 4096) onChange(it) },
            label = { Text(filter.title) },
            singleLine = true,
            shape = RoundedCornerShape(tokens.controlRadius),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun validDiscoveryFilter(filter: DiscoveryFilter, value: String): Boolean = when (filter) {
    is DiscoveryFilter.Number -> value.toIntOrNull()?.let { it in filter.min..filter.max } == true
    is DiscoveryFilter.Text -> value.length <= 4096
    is DiscoveryFilter.Choice -> value in filter.options
    is DiscoveryFilter.Toggle -> value == "true" || value == "false"
}

@Composable
internal fun discoveryFilterSummary(filter: DiscoveryFilter, value: String): String = when (filter) {
    is DiscoveryFilter.Toggle -> if (value == "true") filter.title else "${filter.title}: ${stringResource(R.string.discovery_filter_off)}"
    is DiscoveryFilter.Choice -> "${filter.title}: ${filter.options[value].orEmpty()}"
    else -> "${filter.title}: ${value.ifBlank { stringResource(R.string.discovery_filter_blank) }}"
}
