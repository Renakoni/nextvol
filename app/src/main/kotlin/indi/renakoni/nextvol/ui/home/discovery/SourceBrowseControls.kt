package indi.renakoni.nextvol.ui.home.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceScopeTitle(state: DiscoveryPageState, onScope: (SourceCategory?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(state.scope?.title ?: R.string.source_range_all)
    val description = stringResource(R.string.source_range_description, title)
    TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.semantics { contentDescription = description }) {
        Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(" ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // List only categories that available sources belong to; an active empty scope stays visible as selected.
        val categories = SourceCategory.entries.filter { category ->
            category == state.scope || state.availableSources.any { it.metadata.category == category }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(stringResource(R.string.source_range_title), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.displayMedium) }
            items(listOf(null) + categories, key = { it?.name ?: "all" }) { category ->
                ListItem(headlineContent = { Text(stringResource(category?.title ?: R.string.source_range_all)) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(if (state.loadingSources) "…" else state.availableSources.count { category == null || it.metadata.category == category }.toString(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.size(24.dp)) { if (state.scope == category) Icon(painterResource(R.drawable.check_24px), null) }
                        }
                    }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.selectable(selected = category == state.scope, role = Role.RadioButton,
                        onClick = { expanded = false; onScope(category) }))
            }
        }
    }
}

@Composable
internal fun sourceTopBarHeight() = with(LocalDensity.current) {
    if (fontScale > 1.3f) (MaterialTheme.typography.bodyLarge.lineHeight * 2).toDp().coerceAtLeast(56.dp) else 56.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceTabs(state: DiscoveryPageState, onSelect: (Identifier) -> Unit, onPage: (Int) -> Unit) {
    var showPages by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        val tabs: @Composable (Modifier) -> Unit = { modifier ->
            key(state.scope, state.pageIndex) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = state.pageSources.indexOfFirst { it.metadata.id == state.selected }.coerceAtLeast(0),
                    modifier = modifier, edgePadding = 0.dp, divider = {},
                ) {
                    state.pageSources.forEach { source ->
                        Tab(selected = source.metadata.id == state.selected, onClick = { onSelect(source.metadata.id) },
                            selectedContentColor = MaterialTheme.colorScheme.onSurface,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(source.metadata.item.name, Modifier.widthIn(max = 208.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
        }
        if (stacked) Column {
            tabs(Modifier.fillMaxWidth())
            if (state.pageCount > 1) SourcePageButtons(state, onPage, { showPages = true }, Modifier.align(Alignment.End))
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            tabs(Modifier.weight(1f))
            if (state.pageCount > 1) {
                VerticalDivider(Modifier.height(20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SourcePageButtons(state, onPage, { showPages = true }, compact = true)
            }
        }
    }
    if (showPages && state.pageCount > 1) ModalBottomSheet(onDismissRequest = { showPages = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(stringResource(R.string.source_pages_title), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.displayMedium) }
            items(state.pageCount) { page ->
                val start = page * SOURCE_PAGE_SIZE
                val end = minOf(start + SOURCE_PAGE_SIZE, state.sources.size)
                val names = state.sources.subList(start, end).take(2).joinToString(" · ") { it.metadata.item.name }
                ListItem(headlineContent = { Text(stringResource(R.string.source_page_number, page + 1)) },
                    supportingContent = { Text(stringResource(R.string.source_page_range, start + 1, end, names), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (page == state.pageIndex) Icon(painterResource(R.drawable.check_24px), null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.selectable(page == state.pageIndex, role = Role.RadioButton,
                        onClick = { showPages = false; onPage(page) }))
            }
        }
    }
}

@Composable
private fun SourcePageButtons(state: DiscoveryPageState, onPage: (Int) -> Unit, onShowPages: () -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    val previous = stringResource(R.string.source_page_previous)
    val next = stringResource(R.string.source_page_next)
    val description = stringResource(R.string.source_page_description, state.pageIndex + 1, state.pageCount)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onPage(state.pageIndex - 1) }, enabled = state.pageIndex > 0,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.size(width = if (compact) 24.dp else 48.dp, height = 48.dp)
                .semantics { contentDescription = previous }) { Text("‹", fontSize = if (compact) 20.sp else 24.sp) }
        Box(Modifier.width(IntrinsicSize.Max).widthIn(min = if (compact) 42.dp else 48.dp).heightIn(min = 48.dp)
            .clip(ButtonDefaults.textShape).clickable(role = Role.Button, onClick = onShowPages)
            .semantics { contentDescription = description }
            .padding(horizontal = if (compact) 2.dp else 4.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.source_page_position, state.pageIndex + 1, state.pageCount),
                modifier = Modifier.fillMaxWidth(), maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onPage(state.pageIndex + 1) }, enabled = state.pageIndex + 1 < state.pageCount,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.size(width = if (compact) 24.dp else 48.dp, height = 48.dp)
                .semantics { contentDescription = next }) { Text("›", fontSize = if (compact) 20.sp else 24.sp) }
    }
}

@Composable
internal fun SourceScopeEmpty(state: DiscoveryPageState, explore: Boolean,
    onScope: (SourceCategory?) -> Unit, onManageSources: () -> Unit) {
    val scope = state.scope
    if (scope == null) DiscoveryEmpty(stringResource(if (explore) R.string.explore_no_sources else R.string.categories_no_sources), onManageSources)
    else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (explore) R.string.source_range_empty_explore else R.string.source_range_empty_categories, stringResource(scope.title)))
        TextButton(onClick = { onScope(null) }) { Text(stringResource(R.string.source_range_all), color = MaterialTheme.colorScheme.onSurface) }
        Button(onClick = onManageSources, colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text(stringResource(R.string.sources_add))
        }
    }
}
