package indi.renakoni.nextvol.ui.home.explore.home

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.SourceCategory
import indi.renakoni.nextvol.data.web.SourceDiscoveryBook
import indi.renakoni.nextvol.data.web.SourceDiscoverySection
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.rememberListCover
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
import indi.renakoni.nextvol.utils.fadingEdge
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { SourceScopeTitle(state, onScope) },
                expandedHeight = sourceTopBarHeight(),
                actions = {
                    EditorialIconButton(
                        onClick = onSearch,
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = stringResource(R.string.search_hub_title),
                    )
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
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
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
                if (id != null) {
                    key(id, content.resetId) {
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
                        PullToRefreshBox(
                            isRefreshing = content.refreshing || content.acting || (content.loading && content.sections.isEmpty()),
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // A keyed trailing spacer would anchor the empty list when the first feed arrives.
                            LazyColumn(
                                state = list,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 92.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                content.error?.let { error ->
                                    item(key = "error") {
                                        DiscoveryFailure(
                                            error = error,
                                            retry = onRefresh,
                                            manage = onManageSources,
                                            back = null,
                                            field = content.errorField,
                                            permission = content.errorPermission,
                                            diagnostic = content.errorDiagnostic,
                                            httpStatus = content.errorHttpStatus,
                                        )
                                    }
                                }
                                items(content.filters, key = { "input:" + it.id }) { filter ->
                                    Column(Modifier.padding(horizontal = 16.dp)) {
                                        DiscoveryFilterControl(filter, content.values[filter.id].orEmpty()) {
                                            if (!content.acting && !content.loading) onInput(filter.id, it)
                                        }
                                    }
                                }
                                items(content.buttons, key = { "action:" + it.id }) { button ->
                                    ExploreActionRow(
                                        title = button.title,
                                        enabled = !content.acting && !content.loading,
                                        onClick = { onAction(button.id, false) },
                                        onLongClick = { onAction(button.id, true) },
                                    )
                                }
                                if (content.loaded && content.sections.isEmpty() && content.buttons.isEmpty() && content.filters.isEmpty()) {
                                    item { DiscoveryEmpty(stringResource(R.string.explore_empty), onManageSources) }
                                }
                                items(content.sections, key = { "section:" + it.id }) { section ->
                                    ExploreRowSection(
                                        row = section,
                                        onClickExpand = onMore,
                                        onClickBook = onBook,
                                        onManageSources = onManageSources,
                                        onRetryPreview = onRetryPreview,
                                        coverFor = coverFor,
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
private fun ExploreActionRow(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val radius = 16.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .squircleBackground(color = tokens.cardSurface, cornerRadius = radius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = radius)
            .squircleClip(radius)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painter = painterResource(R.drawable.arrow_forward_ios_24px),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreRowSection(
    row: SourceDiscoverySection,
    onClickExpand: (SourceDiscoverySection) -> Unit,
    onClickBook: (SourceBookId) -> Unit,
    onManageSources: () -> Unit,
    onRetryPreview: ((SourceDiscoverySection) -> Unit)?,
    coverFor: suspend (String) -> Uri?,
) {
    val tokens = NextVolDesign.tokens
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                text = row.title.ifBlank { stringResource(R.string.discovery_unnamed_entry) },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.more != null) {
                val moreDesc = stringResource(R.string.explore_more)
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.activePillFill)
                        .border(0.75.dp, tokens.activePillBorder, CircleShape)
                        .clickable { onClickExpand(row) }
                        .semantics {
                            role = Role.Button
                            contentDescription = moreDesc
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = moreDesc,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_forward_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        val lazyRowState = rememberLazyListState()
        val validBooks = remember(row.books) {
            row.books.filter { it.id.remoteId.isNotBlank() }.distinctBy { it.id }
        }

        if (row.previewLoading && validBooks.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            )
        }
        if (row.previewFailure != null || validBooks.isEmpty()) {
            EditorialCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                cornerRadius = 18.dp,
            ) {
                when {
                    row.previewLoading -> Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.discovery_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    row.previewFailure != null -> {
                        val failure = row.previewFailure
                        DiscoveryFailure(
                            error = failure.error,
                            retry = if (row.previewRetryAvailable && onRetryPreview != null) ({ onRetryPreview(row) }) else null,
                            manage = onManageSources,
                            back = null,
                            field = failure.field,
                            permission = failure.permission,
                            diagnostic = row.diagnosticFailure,
                            httpStatus = row.httpStatus,
                        )
                    }
                    else -> Text(
                        text = stringResource(R.string.discovery_preview_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (validBooks.isNotEmpty()) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fadingEdge(
                            Brush.horizontalGradient(
                                0.01f to Color.Transparent,
                                0.03f to Color.White,
                                0.97f to Color.White,
                                0.99f to Color.Transparent,
                            ),
                        ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    state = lazyRowState,
                    flingBehavior = rememberSnapFlingBehavior(lazyRowState),
                ) {
                    items(
                        items = validBooks,
                        key = { it.id.storageKey },
                    ) { exploreDisplayBook ->
                        ExploreBookCard(
                            book = exploreDisplayBook,
                            coverFor = coverFor,
                            onClickBook = onClickBook,
                        )
                    }
                }
            }
        }
        EditorialHairlineDivider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ExploreBookCard(
    book: SourceDiscoveryBook,
    coverFor: suspend (String) -> Uri?,
    onClickBook: (SourceBookId) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val cardRadius = 16.dp
    Column(
        modifier = Modifier
            .width(114.dp)
            .squircleBackground(color = tokens.cardSurface, cornerRadius = cardRadius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = cardRadius)
            .squircleClip(cardRadius)
            .clickable { onClickBook(book.id) }
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(10.dp),
                ambientColor = tokens.glassShadow,
                spotColor = tokens.glassShadow,
                clip = false,
            ),
        ) {
            Cover(
                bookId = book.id.storageKey,
                width = 100.dp,
                height = 142.dp,
                uri = rememberListCover(book.id.storageKey, book.coverUrl, coverFor),
                title = book.title,
                author = book.author,
                rounded = 10.dp,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotEmpty()) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
