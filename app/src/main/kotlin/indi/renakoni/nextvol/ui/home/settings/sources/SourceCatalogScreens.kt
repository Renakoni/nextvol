package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceCatalogAddScreen(state: SourceManagementState, tab: Int, onTab: (Int) -> Unit,
    url: String, onUrl: (String) -> Unit, onPreviewUrl: () -> Unit, onFile: () -> Unit,
    onCategory: (SourceCategory) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.source_import_tab, R.string.source_catalog_tab).forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { onTab(index) },
                    selectedContentColor = MaterialTheme.colorScheme.onSurface,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(stringResource(title)) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { SourceImportProgress(onCancel) }
            state.message?.takeUnless { it == R.string.source_groups_saved }?.let { message ->
                item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) }
            }
            item { Text(stringResource(R.string.source_groups_import_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (tab == 1) {
                val grouped = state.catalog.groupBy { it.category }
                items(SourceCategory.entries.chunked(2)) { categories ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        categories.forEach { category ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
                                modifier = Modifier.weight(1f).heightIn(min = 96.dp).clip(MaterialTheme.shapes.large)
                                    .clickable(enabled = !state.busy) { onCategory(category) }) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(category.title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                                        Text(grouped[category].orEmpty().size.toString(),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val examples = grouped[category].orEmpty().take(3).map { it.name }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        examples.forEachIndexed { index, name ->
                                            Text(name + if (index < examples.lastIndex) " ·" else "", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    OutlinedTextField(url, onUrl, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sources_url)) },
                        enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                item { Button(onClick = onPreviewUrl, enabled = !state.busy && url.isNotBlank(), colors = sourceButtonColors()) {
                    Text(stringResource(R.string.sources_preview_url))
                } }
                item { OutlinedButton(onClick = onFile, enabled = !state.busy) { Text(stringResource(R.string.sources_file), color = MaterialTheme.colorScheme.onSurface) } }
            }
        }
    }
}

@Composable
internal fun SourceCatalogSelectionScreen(state: SourceManagementState, category: SourceCategory,
    selected: List<String>, onSelection: (List<String>) -> Unit, onContinue: () -> Unit,
    onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val entries = state.catalog.filter { it.category == category }
    val installed = state.installed.map { it.definition.importKey }.toSet()
    val addedCount = entries.count { it.key in installed }
    val available = entries.map { it.key }.filter { it !in installed }
    val all = available.isNotEmpty() && available.all { it in selected }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { SourceImportProgress(onCancel) }
            state.message?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (addedCount == 0) stringResource(R.string.source_catalog_count, entries.size)
                        else stringResource(R.string.source_catalog_count_added, entries.size, addedCount), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onSelection(if (all) selected - available.toSet() else (selected + available).distinct()) },
                        enabled = !state.busy && available.isNotEmpty()) {
                        Text(stringResource(if (all) R.string.source_catalog_deselect_all else R.string.source_catalog_select_all),
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            items(entries, key = { it.key }) { entry ->
                val added = entry.key in installed
                val checked = entry.key in selected
                ListItem(headlineContent = { Text(entry.name) }, supportingContent = { Text(entry.subtitle) },
                    trailingContent = {
                        if (added) Text(stringResource(R.string.source_catalog_added), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Checkbox(checked, onCheckedChange = null, enabled = !state.busy)
                    }, modifier = Modifier.clip(MaterialTheme.shapes.large).toggleable(checked,
                        enabled = !state.busy && !added, role = Role.Checkbox,
                        onValueChange = { onSelection(if (it) selected + entry.key else selected - entry.key) }),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (added) 0.6f else 1f),
                        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (added) 0.6f else 1f)))
            }
        }
        SourceSelectionBar(stringResource(R.string.source_catalog_selected, selected.size),
            secondary = stringResource(R.string.source_catalog_clear), onSecondary = { onSelection(emptyList()) },
            secondaryEnabled = !state.busy && selected.isNotEmpty(),
            action = stringResource(R.string.source_catalog_continue), onAction = onContinue,
            actionEnabled = !state.busy && selected.isNotEmpty())
    }
}

@Composable
private fun SourceImportProgress(onCancel: () -> Unit) {
    Column {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
    }
}

@Composable
internal fun sourceButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
internal fun SourceSelectionBar(summary: String, secondary: String, onSecondary: () -> Unit,
    action: String, onAction: () -> Unit, actionEnabled: Boolean, secondaryEnabled: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
            val actionButton: @Composable () -> Unit = {
                Button(onClick = onAction, enabled = actionEnabled, colors = sourceButtonColors(),
                    modifier = Modifier.widthIn(min = 88.dp).heightIn(min = 48.dp)) { Text(action) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSecondary, enabled = secondaryEnabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(secondary, color = if (secondaryEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                    }
                    if (!stacked) actionButton()
                }
                if (stacked) Box(Modifier.align(Alignment.End)) { actionButton() }
            }
        }
    }
}
