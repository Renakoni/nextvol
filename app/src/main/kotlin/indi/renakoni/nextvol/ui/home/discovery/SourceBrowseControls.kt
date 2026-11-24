package indi.renakoni.nextvol.ui.home.discovery

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceScopeTitle(state: DiscoveryPageState, onScope: (SourceCategory?) -> Unit) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(state.scope?.title ?: R.string.source_range_all)
    val description = stringResource(R.string.source_range_description, title)
    val hasScope = state.scope != null

    Row(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (hasScope) tokens.activePillFill else tokens.cardSurface)
            .border(
                width = if (hasScope) 1.dp else 0.75.dp,
                color = if (hasScope) tokens.activePillBorder else tokens.cardBorder,
                shape = CircleShape,
            )
            .clickable { expanded = true }
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
            color = if (hasScope) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(R.drawable.search_expand_24px),
            contentDescription = null,
            tint = if (hasScope) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }

    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
        ) {
            // List only categories that available sources belong to; an active empty scope stays visible as selected.
            val categories = SourceCategory.entries.filter { category ->
                category == state.scope || state.availableSources.any { it.metadata.category == category }
            }
            LazyColumn(
                modifier = Modifier.navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.source_range_title),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                        color = colors.onSurface,
                    )
                }
                items(listOf(null) + categories, key = { it?.name ?: "all" }) { category ->
                    val selected = category == state.scope
                    val countText = if (state.loadingSources) {
                        "…"
                    } else {
                        state.availableSources.count { category == null || it.metadata.category == category }.toString()
                    }
                    val rowShape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(rowShape)
                            .background(if (selected) tokens.activePillFill else tokens.cardSurface)
                            .border(
                                width = if (selected) 1.dp else 0.75.dp,
                                color = if (selected) tokens.activePillBorder else tokens.cardBorder,
                                shape = rowShape,
                            )
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    expanded = false
                                    onScope(category)
                                },
                            )
                            .heightIn(min = 54.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(category?.title ?: R.string.source_range_all),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                            ),
                            color = if (selected) colors.primary else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = countText,
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = colors.onSurfaceVariant,
                        )
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.check_24px),
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun sourceTopBarHeight() = with(LocalDensity.current) {
    if (fontScale > 1.3f) (MaterialTheme.typography.bodyLarge.lineHeight * 2).toDp().coerceAtLeast(60.dp) else 60.dp
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceTabs(state: DiscoveryPageState, onSelect: (Identifier) -> Unit, onPage: (Int) -> Unit) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    var showPages by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale > 1.3f
            val tabs: @Composable (Modifier) -> Unit = { modifier ->
                key(state.scope, state.pageIndex) {
                    val rowState = rememberLazyListState()
                    val selectedIndex = state.pageSources.indexOfFirst { it.metadata.id == state.selected }
                    LaunchedEffect(selectedIndex) {
                        if (selectedIndex in state.pageSources.indices) {
                            rowState.animateScrollToItem(selectedIndex)
                        }
                    }
                    LazyRow(
                        state = rowState,
                        modifier = modifier.selectableGroup(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(
                            items = state.pageSources,
                            key = { _, source -> source.metadata.id.toString() },
                        ) { _, source ->
                            val selected = source.metadata.id == state.selected
                            val bg by animateColorAsState(
                                targetValue = if (selected) tokens.activePillFill else tokens.cardSurface,
                                animationSpec = tween(200),
                                label = "sourceTabBg",
                            )
                            val border by animateColorAsState(
                                targetValue = if (selected) tokens.activePillBorder else tokens.cardBorder,
                                animationSpec = tween(200),
                                label = "sourceTabBorder",
                            )
                            val textColor by animateColorAsState(
                                targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                                animationSpec = tween(200),
                                label = "sourceTabText",
                            )
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(bg)
                                    .border(if (selected) 1.dp else 0.75.dp, border, CircleShape)
                                    .selectable(
                                        selected = selected,
                                        onClick = { onSelect(source.metadata.id) },
                                        role = Role.Tab,
                                    )
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = source.metadata.item.name,
                                    modifier = Modifier.widthIn(max = 196.dp),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
                                    ),
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            if (stacked) {
                Column(Modifier.fillMaxWidth()) {
                    tabs(Modifier.fillMaxWidth())
                    if (state.pageCount > 1) {
                        SourcePageButtons(
                            state = state,
                            onPage = onPage,
                            onShowPages = { showPages = true },
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(end = 8.dp, bottom = 4.dp),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs(Modifier.weight(1f))
                    if (state.pageCount > 1) {
                        VerticalDivider(Modifier.height(22.dp), color = tokens.divider)
                        SourcePageButtons(
                            state = state,
                            onPage = onPage,
                            onShowPages = { showPages = true },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
        }
        EditorialHairlineDivider()
    }

    if (showPages && state.pageCount > 1) {
        ModalBottomSheet(
            onDismissRequest = { showPages = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
        ) {
            LazyColumn(
                modifier = Modifier.navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.source_pages_title),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                        color = colors.onSurface,
                    )
                }
                items(state.pageCount) { page ->
                    val start = page * SOURCE_PAGE_SIZE
                    val end = minOf(start + SOURCE_PAGE_SIZE, state.sources.size)
                    val names = state.sources.subList(start, end).take(2).joinToString(" · ") { it.metadata.item.name }
                    val selected = page == state.pageIndex
                    val rowShape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(rowShape)
                            .background(if (selected) tokens.activePillFill else tokens.cardSurface)
                            .border(
                                width = if (selected) 1.dp else 0.75.dp,
                                color = if (selected) tokens.activePillBorder else tokens.cardBorder,
                                shape = rowShape,
                            )
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    showPages = false
                                    onPage(page)
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.source_page_number, page + 1),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.W600,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = if (selected) colors.primary else colors.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.source_page_range, start + 1, end, names),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (selected) {
                            Icon(
                                painter = painterResource(R.drawable.check_24px),
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourcePageButtons(
    state: DiscoveryPageState,
    onPage: (Int) -> Unit,
    onShowPages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val previous = stringResource(R.string.source_page_previous)
    val next = stringResource(R.string.source_page_next)
    val description = stringResource(R.string.source_page_description, state.pageIndex + 1, state.pageCount)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onPage(state.pageIndex - 1) },
            enabled = state.pageIndex > 0,
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurfaceVariant),
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = previous },
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_back_24px),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        Box(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .widthIn(min = 48.dp)
                .clip(CircleShape)
                .background(tokens.cardSurface)
                .border(0.75.dp, tokens.cardBorder, CircleShape)
                .clickable(role = Role.Button, onClick = onShowPages)
                .semantics { contentDescription = description }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.source_page_position, state.pageIndex + 1, state.pageCount),
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.W600,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.onSurface,
            )
        }
        IconButton(
            onClick = { onPage(state.pageIndex + 1) },
            enabled = state.pageIndex + 1 < state.pageCount,
            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurfaceVariant),
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = next },
        ) {
            Icon(
                painter = painterResource(R.drawable.arrow_forward_24px),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun SourceScopeEmpty(
    state: DiscoveryPageState,
    explore: Boolean,
    onScope: (SourceCategory?) -> Unit,
    onManageSources: () -> Unit,
) {
    val scope = state.scope
    if (state.restorationFailed) {
        DiscoveryEmpty(stringResource(R.string.sources_restoration_failed), onManageSources)
    } else if (scope == null) {
        DiscoveryEmpty(
            stringResource(if (explore) R.string.explore_no_sources else R.string.categories_no_sources),
            onManageSources,
        )
    } else {
        EmptyPage(
            icon = painterResource(R.drawable.public_24px),
            title = stringResource(scope.title),
            description = stringResource(
                if (explore) R.string.source_range_empty_explore else R.string.source_range_empty_categories,
                stringResource(scope.title),
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = { onScope(null) },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.source_range_all))
                }
                Button(
                    onClick = onManageSources,
                    shape = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                ) {
                    Text(stringResource(R.string.sources_add))
                }
            }
        }
    }
}
