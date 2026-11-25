package indi.renakoni.nextvol.ui.home.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.SourceDiscoveryCategory
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.home.HomeSettingsAction
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryEmpty
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryFailure
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryFilterControl
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageContent
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryPageState
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryScroll
import indi.renakoni.nextvol.ui.home.discovery.SourceScopeEmpty
import indi.renakoni.nextvol.ui.home.discovery.SourceScopeTitle
import indi.renakoni.nextvol.ui.home.discovery.SourceTabs
import indi.renakoni.nextvol.ui.home.discovery.sourceTopBarHeight
import io.nightfish.lightnovelreader.api.identifier.Identifier

private data class CategorySection(
    val id: String,
    val title: String?,
    val items: List<SourceDiscoveryCategory>,
)

private fun groupCategories(categories: List<SourceDiscoveryCategory>): List<CategorySection> {
    val result = mutableListOf<CategorySection>()
    var currentId = "root"
    var currentTitle: String? = null
    var currentItems = mutableListOf<SourceDiscoveryCategory>()

    fun flush() {
        if (currentItems.isNotEmpty()) {
            result.add(CategorySection(currentId, currentTitle, currentItems))
            currentItems = mutableListOf()
        }
    }

    categories.forEach { entry ->
        if (entry.target.target.isBlank()) {
            flush()
            currentId = entry.id
            currentTitle = entry.title.trim().ifBlank { null }
        } else {
            currentItems.add(entry)
        }
    }
    flush()
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    state: DiscoveryPageState,
    onSelect: (Identifier) -> Unit,
    onCategory: (SourceDiscoveryCategory) -> Unit,
    onScroll: (Identifier, DiscoveryScroll) -> Unit,
    onRefresh: () -> Unit,
    onManageSources: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onInput: (String, String) -> Unit = { _, _ -> },
    onAction: (String, Boolean) -> Unit = { _, _ -> },
    onScope: (SourceCategory?) -> Unit = {},
    onPage: (Int) -> Unit = {},
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val content = state.content[state.selected] ?: DiscoveryPageContent()
    val isRefreshing = !state.loadingSources && (content.refreshing || content.acting || (content.loading && content.categories.isEmpty()))

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CategoriesTopBar(
                onRefresh = onRefresh,
                onSettings = onSettings,
                loading = !state.loadingSources && (content.loading || content.acting),
                title = { SourceScopeTitle(state, onScope) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.loadingSources) {
                // No inventory snapshot yet is not an authoritative empty-source result.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.sources.isEmpty()) {
                SourceScopeEmpty(state, explore = false, onScope, onManageSources)
            } else {
                SourceTabs(state, onSelect, onPage)
                val id = state.selected
                if (id != null) {
                    key(id, content.resetId) {
                        val list = rememberLazyListState(content.scroll.index, content.scroll.offset)
                        val sections = remember(content.categories) { groupCategories(content.categories) }

                        LaunchedEffect(list) {
                            snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                                .collect { onScroll(id, it) }
                        }

                        PullToRefreshBox(
                            isRefreshing = isRefreshing,
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Keep bottom clearance out of the item keys so delayed content starts at the top.
                            LazyColumn(
                                state = list,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                content.error?.let { error ->
                                    item(key = "error") {
                                        DiscoveryFailure(
                                            error = error,
                                            retry = onRefresh,
                                            manage = onManageSources,
                                            back = onBack,
                                            field = content.errorField,
                                            permission = content.errorPermission,
                                            diagnostic = content.errorDiagnostic,
                                            httpStatus = content.errorHttpStatus,
                                        )
                                    }
                                }

                                if (content.buttons.isNotEmpty()) {
                                    item(key = "action-buttons") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            items(content.buttons, key = { it.id }) { button ->
                                                val enabled = !content.acting && !content.loading
                                                Box(
                                                    modifier = Modifier
                                                        .heightIn(min = 38.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(tokens.activePillFill)
                                                        .combinedClickable(
                                                            enabled = enabled,
                                                            role = Role.Button,
                                                            onClick = { onAction(button.id, false) },
                                                            onLongClick = { onAction(button.id, true) },
                                                        )
                                                        .alpha(if (enabled) 1f else 0.4f)
                                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Text(
                                                        text = button.title,
                                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                                                        color = colors.primary,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                items(content.filters, key = { "input:${it.id}" }) { filter ->
                                    Column(Modifier.padding(horizontal = 16.dp)) {
                                        DiscoveryFilterControl(filter, content.values[filter.id].orEmpty()) {
                                            if (!content.acting) onInput(filter.id, it)
                                        }
                                    }
                                }

                                if (content.loaded && content.categories.isEmpty() && content.buttons.isEmpty() && content.filters.isEmpty()) {
                                    item {
                                        DiscoveryEmpty(stringResource(R.string.categories_empty), onManageSources)
                                    }
                                }

                                if (sections.isNotEmpty()) {
                                    sections.forEachIndexed { sectionIndex, section ->
                                        section.title?.let { title ->
                                            item(key = "category-header:${section.id}") {
                                                SectionHeader(
                                                    text = title,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(
                                                            start = 16.dp,
                                                            end = 16.dp,
                                                            top = if (sectionIndex == 0) 4.dp else 12.dp,
                                                        )
                                                        .semantics { heading() },
                                                )
                                            }
                                        }
                                        item(key = "category-block:${section.id}") {
                                            CategoryDirectoryBlock(
                                                items = section.items,
                                                onCategory = onCategory,
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
    }
}

private fun computeCategoryDirectoryColumns(
    availableWidthDp: Int,
    maxLabelLen: Int,
    itemCount: Int,
): Int {
    if (itemCount <= 1) return 1
    if (maxLabelLen > 10) {
        return if (availableWidthDp >= 600 && itemCount >= 4) 2 else 1
    }
    val (minCellWidthDp, maxCols) = if (maxLabelLen <= 5) {
        104 to 6
    } else {
        148 to 4
    }
    val rawCols = (availableWidthDp / minCellWidthDp).coerceIn(1, maxCols).coerceAtMost(itemCount)
    // Avoid leaving a single orphan item on the last row when one fewer column balances better.
    if (rawCols > 2 && itemCount % rawCols == 1) {
        val balanced = rawCols - 1
        if (itemCount % balanced != 1) {
            return balanced
        }
    }
    return rawCols
}

@Composable
private fun CategoryDirectoryBlock(
    items: List<SourceDiscoveryCategory>,
    onCategory: (SourceDiscoveryCategory) -> Unit,
) {
    val maxLen = remember(items) { items.maxOfOrNull { it.title.trim().length } ?: 0 }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val columns = computeCategoryDirectoryColumns(
            availableWidthDp = (maxWidth.value / LocalDensity.current.fontScale.coerceAtLeast(1f)).toInt(),
            maxLabelLen = maxLen,
            itemCount = items.size,
        )

        if (columns == 1) {
            Column(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, category ->
                    CategoryDirectoryRow(
                        category = category,
                        onClick = { onCategory(category) },
                    )
                    if (index < items.lastIndex) {
                        EditorialHairlineDivider()
                    }
                }
            }
        } else {
            val tokens = NextVolDesign.tokens
            val rows = remember(items, columns) { items.chunked(columns) }
            Column(modifier = Modifier.fillMaxWidth()) {
                EditorialHairlineDivider()
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (col in 0 until columns) {
                            val category = rowItems.getOrNull(col)
                            if (category != null) {
                                CategoryGridCell(
                                    category = category,
                                    onClick = { onCategory(category) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            if (col < rowItems.lastIndex) {
                                VerticalDivider(
                                    modifier = Modifier.height(22.dp),
                                    thickness = 0.75.dp,
                                    color = tokens.divider,
                                )
                            }
                        }
                    }
                    EditorialHairlineDivider()
                }
            }
        }
    }
}

@Composable
private fun CategoryGridCell(
    category: SourceDiscoveryCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = category.title.ifBlank { stringResource(R.string.discovery_unnamed_entry) },
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W500),
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryDirectoryRow(
    category: SourceDiscoveryCategory,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = category.title.ifBlank { stringResource(R.string.discovery_unnamed_entry) },
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.W500),
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.arrow_forward_ios_24px),
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.56f),
            modifier = Modifier.size(13.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoriesTopBar(
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    loading: Boolean = false,
    title: @Composable () -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val refreshLabel = stringResource(R.string.discovery_refresh)

    TopAppBar(
        title = title,
        expandedHeight = sourceTopBarHeight(),
        actions = {
            if (loading) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onRefresh)
                        .semantics {
                            role = Role.Button
                            contentDescription = refreshLabel
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                EditorialIconButton(
                    onClick = onRefresh,
                    painter = painterResource(R.drawable.refresh_24px),
                    contentDescription = refreshLabel,
                )
            }
            HomeSettingsAction(onSettings)
            Spacer(Modifier.width(4.dp))
        },
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
