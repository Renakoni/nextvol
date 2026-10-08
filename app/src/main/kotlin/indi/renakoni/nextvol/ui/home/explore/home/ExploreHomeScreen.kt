package indi.renakoni.nextvol.ui.home.explore.home

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.rememberListCover
import indi.renakoni.nextvol.ui.home.HomeSettingsAction
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.utils.fadingEdge
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreHomeScreen(
    state: DiscoveryPageState,
    onSelect: (Identifier) -> Unit,
    onScroll: (Identifier, DiscoveryScroll) -> Unit,
    onRefresh: () -> Unit,
    onMore: (SourceDiscoverySection) -> Unit,
    onBook: (SourceBookId) -> Unit,
    onSearch: () -> Unit,
    onManageSources: () -> Unit,
    onInput: (String, String) -> Unit,
    onAction: (String, Boolean) -> Unit,
    onSettings: () -> Unit,
    onScope: (SourceCategory?) -> Unit = {},
    onPage: (Int) -> Unit = {},
    onRetryPreview: ((SourceDiscoverySection) -> Unit)? = null,
    coverFor: suspend (String) -> Uri? = { null },
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { SourceScopeTitle(state, onScope) },
            expandedHeight = sourceTopBarHeight(),
            navigationIcon = { Icon(painterResource(R.drawable.outline_explore_24px), stringResource(R.string.nav_explore), Modifier.padding(12.dp)) },
            actions = {
                IconButton(onClick = onSearch) {
                    Icon(painterResource(R.drawable.search_24px), stringResource(R.string.search_hub_title))
                }
                HomeSettingsAction(onSettings)
            }, windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loadingSources) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.sources.isEmpty()) {
                SourceScopeEmpty(state, explore = true, onScope, onManageSources)
            } else {
                SourceTabs(state, onSelect, onPage)
                val id = state.selected
                val content = state.content[id] ?: DiscoveryPageContent()
                if (id != null) key(id, content.resetId) {
                    val list = rememberLazyListState(content.scroll.index, content.scroll.offset)
                    val currentContent by rememberUpdatedState(content)
                    LaunchedEffect(list) {
                        if (!content.loaded && content.scroll != DiscoveryScroll()) {
                            // A decayed page may first emit a short batch that clamps the saved position.
                            // Wait for the complete feed unless the reader starts scrolling meanwhile.
                            val ready = snapshotFlow { currentContent.loaded to list.isScrollInProgress }
                                .first { (loaded, scrolling) -> loaded || scrolling }
                            if (!ready.second) list.scrollToItem(content.scroll.index, content.scroll.offset)
                        }
                        snapshotFlow { DiscoveryScroll(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
                            .collect { onScroll(id, it) }
                    }
                    val titleHeight = with(LocalDensity.current) { (16.sp * 2.2f).toDp() }
                    PullToRefreshBox(isRefreshing = content.acting || content.loading && content.sections.isEmpty(),
                        onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                        // A keyed trailing spacer would anchor the empty list when the first feed arrives.
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
                            content.error?.let { error -> item(key = "error") {
                                DiscoveryFailure(error, onRefresh, onManageSources, back = null, field = content.errorField, permission = content.errorPermission, diagnostic = content.errorDiagnostic, httpStatus = content.errorHttpStatus)
                            } }
                            items(content.filters, key = { "input:" + it.id }) { filter ->
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    DiscoveryFilterControl(filter, content.values[filter.id].orEmpty()) {
                                        if (!content.acting && !content.loading) onInput(filter.id, it)
                                    }
                                }
                            }
                            items(content.buttons, key = { "action:" + it.id }) { button ->
                                ListItem(headlineContent = { Text(button.title) },
                                    modifier = Modifier.combinedClickable(enabled = !content.acting && !content.loading,
                                        onClick = { onAction(button.id, false) }, onLongClick = { onAction(button.id, true) }))
                            }
                            if (content.loaded && content.sections.isEmpty() && content.buttons.isEmpty() && content.filters.isEmpty())
                                item { DiscoveryEmpty(stringResource(R.string.explore_empty), onManageSources) }
                            items(content.sections, key = { "section:" + it.id }) { section ->
                                ExploreRowSection(Modifier, section, titleHeight, onMore, onBook, onManageSources, onRetryPreview, coverFor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreRowSection(
    modifier: Modifier,
    row: SourceDiscoverySection,
    titleHeight: androidx.compose.ui.unit.Dp,
    onClickExpand: (SourceDiscoverySection) -> Unit,
    onClickBook: (SourceBookId) -> Unit,
    onManageSources: () -> Unit,
    onRetryPreview: ((SourceDiscoverySection) -> Unit)?,
    coverFor: suspend (String) -> Uri?,
) {
    Column(
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                modifier = Modifier.weight(2f).semantics { heading() },
                text = row.title.ifBlank { stringResource(R.string.discovery_unnamed_entry) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (row.more != null) {
                IconButton(
                    modifier = Modifier.size(48.dp),
                    onClick = {
                        onClickExpand(row)
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_forward_24px),
                        contentDescription = stringResource(R.string.explore_more)
                    )
                }
            }
        }

        val lazyRowState = rememberLazyListState()
        val validBooks = remember(row.books) {
            row.books.filter { it.id.remoteId.isNotBlank() }.distinctBy { it.id }
        }

        if (row.previewLoading && validBooks.isNotEmpty()) {
            LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth())
        }
        if (row.previewFailure != null || validBooks.isEmpty()) {
            Surface(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                when {
                    row.previewLoading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.discovery_preview_loading), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    row.previewFailure != null -> {
                        val failure = row.previewFailure
                        DiscoveryFailure(failure.error,
                            if (row.previewRetryAvailable && onRetryPreview != null) ({ onRetryPreview(row) }) else null,
                            onManageSources, back = null, field = failure.field, permission = failure.permission,
                            diagnostic = row.diagnosticFailure, httpStatus = row.httpStatus)
                    }
                    else -> Text(stringResource(R.string.discovery_preview_empty), Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (validBooks.isNotEmpty()) CompositionLocalProvider(LocalOverscrollFactory provides null) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .fadingEdge(
                        Brush.horizontalGradient(
                            0.01f to Color.Transparent,
                            0.03f to Color.White,
                            0.97f to Color.White,
                            0.99f to Color.Transparent
                        )
                )
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                state = lazyRowState,
                flingBehavior = rememberSnapFlingBehavior(lazyRowState)
            ) {
                item {
                    Box(modifier = Modifier.width(10.dp))
                }

                items(
                    items = validBooks,
                    key = { it.id.storageKey }
                ) { exploreDisplayBook ->
                    ExploreBookCard(
                        book = exploreDisplayBook,
                        titleHeight = titleHeight,
                        coverFor = coverFor,
                        onClickBook = onClickBook
                    )
                }

                item {
                    Box(modifier = Modifier.width(12.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            HorizontalDivider()
        }
    }
}

@Composable
private fun ExploreBookCard(
    book: SourceDiscoveryBook,
    titleHeight: androidx.compose.ui.unit.Dp,
    coverFor: suspend (String) -> Uri?,
    onClickBook: (SourceBookId) -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClickBook(book.id) }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            Cover(
                bookId = book.id.storageKey,
                width = 98.dp,
                height = 138.dp,
                uri = rememberListCover(book.id.storageKey, book.coverUrl, coverFor),
                title = book.title,
                author = book.author,
                rounded = 6.dp
            )
        }
        Column(
            modifier = Modifier
                .width(100.dp)
                .padding(horizontal = 2.dp)
                .padding(top = 8.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                modifier = Modifier
                    .height(titleHeight)
                    .wrapContentHeight(Alignment.Top),
                text = book.title,
                style = MaterialTheme.typography.headlineMedium.copy(
                    letterSpacing = 0.5.sp
                ),
                fontWeight = FontWeight.W500,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (book.author.isNotEmpty()) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
