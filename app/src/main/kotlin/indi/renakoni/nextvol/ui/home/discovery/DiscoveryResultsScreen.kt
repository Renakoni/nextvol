package indi.renakoni.nextvol.ui.home.discovery

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.SourceDiscoveryBook
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.rememberListCover
import indi.renakoni.nextvol.utils.navigationBarSpacer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryResultsScreen(
    state: DiscoveryResultsState,
    onFilter: (Map<String, String>) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onScroll: (DiscoveryScroll) -> Unit,
    onBook: (SourceBookId) -> Unit,
    onManageSources: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    coverFor: suspend (String) -> Uri? = { null },
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showFilters by rememberSaveable(state.sourceName, state.definitions) { mutableStateOf(false) }
    val values = filterValues(state.definitions, state.filters)
    val defaults = filterValues(state.definitions, emptyMap())
    val activeFilters = state.definitions.filter { values[it.id] != defaults[it.id] }
    val list = key(state.resetId) { rememberLazyListState(state.scroll.index, state.scroll.offset) }

    LaunchedEffect(list) {
        snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }.collect(onScroll)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DiscoveryTopBar(
                title = state.title,
                sourceName = state.sourceName,
                onBack = onBack,
                onRefresh = onRefresh,
                onSettings = onSettings,
                onFilters = if (state.definitions.isEmpty()) null else ({ showFilters = true }),
                filterCount = activeFilters.size,
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
            if (activeFilters.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        activeFilters.forEach { filter ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(tokens.activePillFill)
                                    .border(0.75.dp, tokens.activePillBorder, CircleShape)
                                    .clickable(role = Role.Button) { showFilters = true }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = discoveryFilterSummary(filter, values.getValue(filter.id)),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600),
                                    color = colors.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    EditorialHairlineDivider()
                }
            }

            PullToRefreshBox(
                isRefreshing = state.loading && !state.loaded,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.books, key = { it.id.storageKey }, contentType = { "book" }) { book ->
                        DiscoveryBookRow(book, coverFor) { onBook(book.id) }
                    }
                    if (state.loading) {
                        item {
                            EditorialCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 16.dp,
                                contentPadding = PaddingValues(16.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text(
                                        text = stringResource(R.string.discovery_preview_loading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    state.error?.let { error ->
                        item {
                            DiscoveryFailure(
                                error = error,
                                retry = onLoadMore,
                                manage = onManageSources,
                                back = onBack,
                                field = state.errorField,
                                permission = state.errorPermission,
                                diagnostic = state.errorDiagnostic,
                                httpStatus = state.errorHttpStatus,
                            )
                        }
                    }
                    if (state.loaded && !state.loading && state.books.isEmpty() && state.error == null) {
                        item {
                            EmptyPage(
                                icon = painterResource(R.drawable.not_found_90dp),
                                title = stringResource(R.string.nothing_here),
                                description = stringResource(
                                    if (state.hasMore) R.string.discovery_no_page_matches else R.string.discovery_no_books,
                                ),
                            )
                        }
                    }
                    if (state.hasMore && state.error == null) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                FilledTonalButton(
                                    onClick = onLoadMore,
                                    enabled = !state.loading,
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Text(stringResource(R.string.discovery_load_more))
                                }
                            }
                        }
                    }
                    navigationBarSpacer()
                }
            }
        }
    }

    if (showFilters && state.definitions.isNotEmpty()) {
        DiscoveryResultsFilters(
            definitions = state.definitions,
            values = values,
            onDismiss = { showFilters = false },
            onApply = {
                showFilters = false
                onFilter(it)
            },
        )
    }
}

/** Discovery summaries share the source-aware cover and result renderer. Details are read only for a book listed without a cover. */
@Composable
internal fun DiscoveryBookRow(
    book: SourceDiscoveryBook,
    coverFor: suspend (String) -> Uri? = { null },
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(
                bookId = book.id.storageKey,
                width = 84.dp,
                height = 122.dp,
                uri = rememberListCover(book.id.storageKey, book.coverUrl, coverFor),
                title = book.title,
                author = book.author,
                rounded = 10.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                        color = colors.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
