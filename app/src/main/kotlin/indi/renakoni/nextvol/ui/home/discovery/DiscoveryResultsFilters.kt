package indi.renakoni.nextvol.ui.home.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryResultsFilters(definitions: List<DiscoveryFilter>, values: Map<String, String>,
    onDismiss: () -> Unit, onApply: (Map<String, String>) -> Unit) {
    var draft by rememberSaveable(definitions, values) { mutableStateOf(values) }
    val valid = definitions.all { validDiscoveryFilter(it, draft[it.id].orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.discovery_filters), Modifier.weight(1f), style = MaterialTheme.typography.displayMedium)
            TextButton(onClick = { draft = filterValues(definitions, emptyMap()) }) {
                Text(stringResource(R.string.discovery_reset_filters))
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            items(definitions, key = { it.id }) { filter ->
                DiscoveryResultsFilter(filter, draft[filter.id].orEmpty()) { draft = draft + (filter.id to it) }
            }
            item { Text(stringResource(R.string.discovery_filter_paging_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            Button(onClick = { onApply(draft) }, enabled = valid, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.discovery_apply_filters))
            }
        }
    }
}

@Composable
private fun DiscoveryResultsFilter(filter: DiscoveryFilter, value: String, onChange: (String) -> Unit) {
    when (filter) {
        is DiscoveryFilter.Toggle -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = value == "true", role = Role.Switch, onValueChange = { onChange(it.toString()) }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(filter.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = value == "true", onCheckedChange = null)
        }
        is DiscoveryFilter.Choice -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(filter.title, style = MaterialTheme.typography.bodyLarge)
            if (filter.options.size <= 4) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filter.options.forEach { (key, label) ->
                    FilterChip(selected = value == key, onClick = { onChange(key) }, label = { Text(label) })
                }
            } else {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(filter.options[value].orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        filter.options.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onChange(key) })
                        }
                    }
                }
            }
        }
        is DiscoveryFilter.Number -> OutlinedTextField(value = value, onValueChange = onChange,
            label = { Text(filter.title) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !validDiscoveryFilter(filter, value),
            supportingText = { Text(stringResource(R.string.discovery_number_range, filter.min, filter.max)) })
        is DiscoveryFilter.Text -> OutlinedTextField(value = value, onValueChange = { if (it.length <= 4096) onChange(it) },
            label = { Text(filter.title) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
