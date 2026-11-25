package indi.renakoni.nextvol.ui.home.settings.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialPillTabRow
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun SourceCatalogAddScreen(
    state: SourceManagementState,
    tab: Int,
    onTab: (Int) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    onPreviewUrl: () -> Unit,
    onFile: () -> Unit,
    onCategory: (SourceCategory) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    Column(modifier.fillMaxSize()) {
        EditorialPillTabRow(
            tabs = listOf(
                stringResource(R.string.source_import_tab),
                stringResource(R.string.source_catalog_tab),
            ),
            selectedTabIndex = tab,
            onTabSelected = onTab,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) {
                item(key = "catalog_progress") { SourceImportProgress(onCancel) }
            }
            state.message?.takeUnless { it == R.string.source_groups_saved }?.let { message ->
                item(key = "catalog_error") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(
                            text = stringResource(message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item(key = "catalog_help") {
                Text(
                    text = stringResource(R.string.source_groups_import_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (tab == 1) {
                val grouped = state.catalog.groupBy { it.category }
                items(SourceCategory.entries.chunked(2)) { categories ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        categories.forEach { category ->
                            val count = grouped[category].orEmpty().size
                            EditorialCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 104.dp),
                                cornerRadius = tokens.cardRadius,
                                onClick = { onCategory(category) },
                                enabled = !state.busy,
                                contentPadding = PaddingValues(16.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = stringResource(category.title),
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.W600,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(tokens.activePillFill)
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = count.toString(),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFeatureSettings = "tnum",
                                                ),
                                                fontWeight = FontWeight.W600,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    val examples = grouped[category].orEmpty().take(3).map { it.name }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        examples.forEachIndexed { index, name ->
                                            Text(
                                                text = name + if (index < examples.lastIndex) " ·" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (categories.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                item(key = "url_import_card") {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = url,
                                onValueChange = onUrl,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                label = { Text(stringResource(R.string.sources_url)) },
                                enabled = !state.busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = onPreviewUrl,
                                    enabled = !state.busy && url.isNotBlank(),
                                    shape = CircleShape,
                                    colors = sourceButtonColors(),
                                ) {
                                    Text(stringResource(R.string.sources_preview_url))
                                }
                                OutlinedButton(
                                    onClick = onFile,
                                    enabled = !state.busy,
                                    shape = CircleShape,
                                ) {
                                    Text(
                                        text = stringResource(R.string.sources_file),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SourceCatalogSelectionScreen(
    state: SourceManagementState,
    category: SourceCategory,
    selected: List<String>,
    onSelection: (List<String>) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    val entries = state.catalog.filter { it.category == category }
    val installed = state.installed.map { it.definition.importKey }.toSet()
    val addedCount = entries.count { it.key in installed }
    val available = entries.map { it.key }.filter { it !in installed }
    val all = available.isNotEmpty() && available.all { it in selected }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.busy) item { SourceImportProgress(onCancel) }
            state.message?.let { message ->
                item {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (addedCount == 0) {
                            stringResource(R.string.source_catalog_count, entries.size)
                        } else {
                            stringResource(R.string.source_catalog_count_added, entries.size, addedCount)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            onSelection(
                                if (all) selected - available.toSet()
                                else (selected + available).distinct(),
                            )
                        },
                        enabled = !state.busy && available.isNotEmpty(),
                    ) {
                        Text(
                            text = stringResource(
                                if (all) R.string.source_catalog_deselect_all else R.string.source_catalog_select_all,
                            ),
                        )
                    }
                }
            }
            items(entries, key = { it.key }) { entry ->
                val added = entry.key in installed
                val checked = entry.key in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleBackground(
                            color = if (checked && !added) tokens.activePillFill else tokens.cardSurface,
                            cornerRadius = tokens.cardRadius,
                        )
                        .squircleBorder(
                            width = if (checked && !added) 1.25.dp else 0.75.dp,
                            color = if (checked && !added) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                            cornerRadius = tokens.cardRadius,
                        )
                        .squircleClip(tokens.cardRadius)
                        .toggleable(
                            value = checked,
                            enabled = !state.busy && !added,
                            role = Role.Checkbox,
                            onValueChange = { onSelection(if (it) selected + entry.key else selected - entry.key) },
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (added) 0.55f else 1f),
                        )
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (added) 0.55f else 1f),
                        )
                    }
                    if (added) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.source_catalog_added),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = !state.busy)
                    }
                }
            }
        }
        SourceSelectionBar(
            summary = stringResource(R.string.source_catalog_selected, selected.size),
            secondary = stringResource(R.string.source_catalog_clear),
            onSecondary = { onSelection(emptyList()) },
            secondaryEnabled = !state.busy && selected.isNotEmpty(),
            action = stringResource(R.string.source_catalog_continue),
            onAction = onContinue,
            actionEnabled = !state.busy && selected.isNotEmpty(),
        )
    }
}

@Composable
private fun SourceImportProgress(onCancel: () -> Unit) {
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape),
        )
        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(stringResource(android.R.string.cancel))
        }
    }
}

@Composable
internal fun sourceButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
internal fun SourceSelectionBar(
    summary: String,
    secondary: String,
    onSecondary: () -> Unit,
    action: String,
    onAction: () -> Unit,
    actionEnabled: Boolean,
    secondaryEnabled: Boolean = true,
    applyNavigationBarPadding: Boolean = false,
) {
    val tokens = NextVolDesign.tokens
    Surface(
        color = tokens.cardElevatedSurface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            EditorialHairlineDivider()
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (applyNavigationBarPadding) Modifier.navigationBarsPadding() else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f
                val actionButton: @Composable () -> Unit = {
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        shape = CircleShape,
                        colors = sourceButtonColors(),
                        modifier = Modifier
                            .widthIn(min = 88.dp)
                            .heightIn(min = 44.dp),
                    ) {
                        Text(action, fontWeight = FontWeight.W600)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = summary,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.W500,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = onSecondary,
                            enabled = secondaryEnabled,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Text(
                                text = secondary,
                                color = if (secondaryEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                        }
                        if (!stacked) actionButton()
                    }
                    if (stacked) {
                        Box(Modifier.align(Alignment.End)) { actionButton() }
                    }
                }
            }
        }
    }
}
