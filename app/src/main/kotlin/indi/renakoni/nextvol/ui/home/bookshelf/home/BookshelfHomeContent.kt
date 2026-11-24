package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onOk
import com.valentinilk.shimmer.Shimmer
import com.valentinilk.shimmer.ShimmerBounds
import com.valentinilk.shimmer.rememberShimmer
import com.valentinilk.shimmer.shimmer
import com.valentinilk.shimmer.unclippedBoundsInWindow
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfBookItem
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfUiState
import indi.renakoni.nextvol.utils.bottomBarPadding
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.majiajie.tinypinyin.Pinyin
import java.text.Collator
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfHomeContent(
    uiState: BookshelfHomeUiState,
    listState: LazyListState,
    gridState: LazyGridState,
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !uiState.selectMode,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            if (uiState.bookshelfList.isNotEmpty()) {
                val selectedIndex = uiState.selectedTabIndex
                    .takeIf { it in uiState.bookshelfList.indices } ?: 0
                BookshelfPillTabStrip(
                    bookshelves = uiState.bookshelfList,
                    selectedBookshelfId = uiState.selectedBookshelfId,
                    selectedIndex = selectedIndex,
                    onSelectBookshelf = { id ->
                        if (!uiState.selectMode) uiState.changePage(id)
                    },
                )
            }
        }

        val selectedBookshelfUiState = uiState.selectedBookshelf
        if (selectedBookshelfUiState != null) {
            val allBookIds = remember(selectedBookshelfUiState.allBookFlows) {
                selectedBookshelfUiState.allBookFlows.map { it.first }
            }
            val allBooksFlow = remember(selectedBookshelfUiState.allBookFlows) {
                selectedBookshelfUiState.allBookFlows
                    .map { pair ->
                        pair.second.map { result ->
                            val item: Result<BookshelfBookItem, WebRequestError>? = result
                            pair.first to item
                        }.onStart { emit(pair.first to null) }
                    }
                    .let { flows ->
                        if (flows.isEmpty()) flowOf(emptyList())
                        else combine(flows) { it.toList() }
                    }
            }
            val allBooks by allBooksFlow.collectAsStateWithLifecycle(emptyList())
            val sortedAllBooks = remember(
                selectedBookshelfUiState.allBookFlows,
                allBooks,
                selectedBookshelfUiState.sortType,
                selectedBookshelfUiState.sortReversed,
            ) {
                sortBooks(
                    source = allBooks,
                    allBookIds = allBookIds,
                    sortType = selectedBookshelfUiState.sortType,
                    sortReversed = selectedBookshelfUiState.sortReversed,
                )
            }

            val updatedBooksFlow = remember(selectedBookshelfUiState.updatedBookFlows) {
                selectedBookshelfUiState.updatedBookFlows
                    .map { pair ->
                        pair.second.map { result ->
                            val item: Result<BookshelfBookItem, WebRequestError>? = result
                            pair.first to item
                        }.onStart { emit(pair.first to null) }
                    }
                    .let { flows ->
                        if (flows.isEmpty()) flowOf(emptyList())
                        else combine(flows) { it.toList() }
                    }
            }
            val updatedBooks by updatedBooksFlow.collectAsStateWithLifecycle(emptyList())
            val sortedUpdatedBooks = remember(
                selectedBookshelfUiState.updatedBookFlows,
                updatedBooks,
                selectedBookshelfUiState.sortType,
                selectedBookshelfUiState.sortReversed,
            ) {
                sortBooks(
                    source = updatedBooks,
                    allBookIds = allBookIds,
                    sortType = selectedBookshelfUiState.sortType,
                    sortReversed = selectedBookshelfUiState.sortReversed,
                )
            }

            val pinnedBooksFlow = remember(selectedBookshelfUiState.pinnedBookFlows) {
                selectedBookshelfUiState.pinnedBookFlows
                    .map { pair ->
                        pair.second.map { result ->
                            val item: Result<BookshelfBookItem, WebRequestError>? = result
                            pair.first to item
                        }.onStart { emit(pair.first to null) }
                    }
                    .let { flows ->
                        if (flows.isEmpty()) flowOf(emptyList())
                        else combine(flows) { it.toList() }
                    }
            }
            val pinnedBooks by pinnedBooksFlow.collectAsStateWithLifecycle(emptyList())
            val sortedPinnedBooks = remember(
                selectedBookshelfUiState.pinnedBookFlows,
                pinnedBooks,
                selectedBookshelfUiState.sortType,
                selectedBookshelfUiState.sortReversed,
            ) {
                sortBooks(
                    source = pinnedBooks,
                    allBookIds = allBookIds,
                    sortType = selectedBookshelfUiState.sortType,
                    sortReversed = selectedBookshelfUiState.sortReversed,
                )
            }

            val sections = listOf(
                BookshelfSection(
                    "updated",
                    R.drawable.autorenew_24px,
                    R.string.bookshelf_group_title_updated,
                    sortedUpdatedBooks,
                    uiState.updatedExpanded,
                ) { uiState.updatedExpanded = !uiState.updatedExpanded },
                BookshelfSection(
                    "pinned",
                    R.drawable.keep_24px,
                    R.string.bookshelf_group_title_pinned,
                    sortedPinnedBooks,
                    uiState.pinnedExpanded,
                ) { uiState.pinnedExpanded = !uiState.pinnedExpanded },
                BookshelfSection(
                    "book",
                    R.drawable.outline_bookmark_24px,
                    R.string.bookshelf_group_title_all,
                    sortedAllBooks,
                    uiState.allExpanded,
                ) { uiState.allExpanded = !uiState.allExpanded },
            ).filter { it.books.isNotEmpty() }

            val itemKeys = sections.flatMap { section ->
                listOf(section.key) + if (section.expanded) section.books.map { section.bookKey(it.first) } else emptyList()
            }
            var previousLayout by rememberSaveable(uiState.selectedBookshelfId) { mutableStateOf(uiState.layout) }
            LaunchedEffect(uiState.layout, uiState.selectedBookshelfId) {
                if (previousLayout == uiState.layout) return@LaunchedEffect
                // Keep the source-qualified book (and its section), even if sorting moved its index.
                val visibleKeys = if (previousLayout == BookshelfLayout.List) {
                    listState.layoutInfo.visibleItemsInfo.filter { it.contentType == "book_card" }.map { it.key }
                } else {
                    gridState.layoutInfo.visibleItemsInfo.filter { it.contentType == "book_card" }.map { it.key }
                }
                val targetIndex = visibleKeys.firstNotNullOfOrNull { key -> itemKeys.indexOf(key).takeIf { it >= 0 } }
                previousLayout = uiState.layout
                if (targetIndex != null) {
                    if (uiState.layout == BookshelfLayout.List) listState.scrollToItem(targetIndex)
                    else gridState.scrollToItem(targetIndex)
                }
            }

            val selectedBookIdSet = uiState.selectedBookIds.toHashSet()
            var loadingCards by remember { mutableIntStateOf(0) }
            val onLoadingChanged: (Boolean) -> Unit = remember {
                { loading -> loadingCards = (loadingCards + if (loading) 1 else -1).coerceAtLeast(0) }
            }
            val shimmerBounds = remember { mutableStateOf<Rect?>(null) }
            val shimmerInstance = if (loadingCards > 0) rememberShimmer(ShimmerBounds.Custom) else null
            SideEffect { shimmerInstance?.updateBounds(shimmerBounds.value) }
            val density = LocalDensity.current

            val card: @Composable (Pair<String, Result<BookshelfBookItem, WebRequestError>?>) -> Unit = { pair ->
                BookshelfBookCard(
                    id = pair.first,
                    bookshelfBookItem = pair.second,
                    selected = pair.first in selectedBookIdSet,
                    selectMode = uiState.selectMode,
                    layout = uiState.layout,
                    shimmer = shimmerInstance,
                    onLoadingChanged = onLoadingChanged,
                    onBookClick = uiState.onBookClick,
                    onBookSelect = uiState.changeBookSelectState,
                    onLongPress = { bookId ->
                        if (!uiState.selectMode) uiState.onEnableSelectMode()
                        uiState.changeBookSelectState(bookId)
                    },
                )
            }

            AnimatedVisibility(
                visible = selectedBookshelfUiState.allBookFlows.isEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                EmptyPage(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .bottomBarPadding(),
                    icon = painterResource(R.drawable.bookmarks_90px),
                    title = stringResource(R.string.nothing_here),
                    description = stringResource(R.string.nothing_here_desc_bookshelf),
                )
            }

            val contentModifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .onGloballyPositioned {
                    shimmerBounds.value = it.unclippedBoundsInWindow()
                    shimmerInstance?.updateBounds(shimmerBounds.value)
                }

            if (uiState.layout == BookshelfLayout.List) {
                LazyColumn(
                    modifier = contentModifier,
                    state = listState,
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    sections.forEach { section ->
                        stickyHeader(key = section.key) { section.Header() }
                        if (section.expanded) {
                            items(
                                items = section.books,
                                key = { section.bookKey(it.first) },
                                contentType = { "book_card" },
                            ) { card(it) }
                        }
                    }
                    if (sections.isNotEmpty()) {
                        item(key = "footer") { BookshelfFooter(sortedAllBooks.size, uiState.allExpanded) }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(116.dp * density.fontScale.coerceAtLeast(1f)),
                    modifier = contentModifier,
                    state = gridState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEach { section ->
                        item(
                            key = section.key,
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "header",
                        ) {
                            section.Header(horizontalInset = 0.dp)
                        }
                        if (section.expanded) {
                            items(
                                items = section.books,
                                key = { section.bookKey(it.first) },
                                contentType = { "book_card" },
                            ) { card(it) }
                        }
                    }
                    if (sections.isNotEmpty()) {
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            BookshelfFooter(sortedAllBooks.size, uiState.allExpanded)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookshelfPillTabStrip(
    bookshelves: List<BookshelfUiState>,
    selectedBookshelfId: Int,
    selectedIndex: Int,
    onSelectBookshelf: (Int) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val rowState = rememberLazyListState()

    LaunchedEffect(selectedIndex) {
        if (selectedIndex in bookshelves.indices) {
            rowState.animateScrollToItem(selectedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(
                items = bookshelves,
                key = { _, shelf -> shelf.id },
            ) { _, bookshelf ->
                val selected = bookshelf.id == selectedBookshelfId
                val bgColor by animateColorAsState(
                    targetValue = if (selected) tokens.activePillFill else tokens.cardSurface,
                    animationSpec = tween(200),
                    label = "shelfTabBg",
                )
                val borderColor by animateColorAsState(
                    targetValue = if (selected) tokens.activePillBorder else tokens.cardBorder,
                    animationSpec = tween(200),
                    label = "shelfTabBorder",
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                    animationSpec = tween(200),
                    label = "shelfTabText",
                )

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(if (selected) 1.dp else 0.75.dp, borderColor, CircleShape)
                        .selectable(
                            selected = selected,
                            onClick = { onSelectBookshelf(bookshelf.id) },
                            role = Role.Tab,
                        )
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = bookshelf.name,
                        modifier = Modifier.widthIn(max = 180.dp),
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
        EditorialHairlineDivider()
    }
}

private class BookshelfSection(
    val key: String,
    val icon: Int,
    val title: Int,
    val books: List<Pair<String, Result<BookshelfBookItem, WebRequestError>?>>,
    val expanded: Boolean,
    val onToggle: () -> Unit,
) {
    // The same book can occur in several sections; the id is already SourceBookId.storageKey.
    fun bookKey(id: String) = "${key}_$id"

    @Composable
    fun Header(horizontalInset: androidx.compose.ui.unit.Dp = 16.dp) {
        CollapseHeader(
            icon = painterResource(icon),
            title = stringResource(title, books.size),
            expanded = expanded,
            onToggleExpand = onToggle,
            horizontalInset = horizontalInset,
        )
    }
}

@Composable
private fun BookshelfFooter(count: Int, expanded: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .bottomBarPadding(),
    ) {
        if (count > 0 && expanded) {
            Text(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 18.dp),
                text = stringResource(R.string.n_books, count),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.W600,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BookshelfBookCard(
    id: String,
    bookshelfBookItem: Result<BookshelfBookItem, WebRequestError>?,
    selected: Boolean,
    selectMode: Boolean,
    layout: BookshelfLayout,
    shimmer: Shimmer?,
    onLoadingChanged: (Boolean) -> Unit,
    onBookClick: (String) -> Unit,
    onBookSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    Crossfade(
        targetState = bookshelfBookItem,
        label = "BookCardCrossfade",
    ) { result ->
        // Include an outgoing placeholder until its existing Crossfade has finished.
        if (result == null) {
            DisposableEffect(onLoadingChanged) {
                onLoadingChanged(true)
                onDispose { onLoadingChanged(false) }
            }
        }
        val onClick = { if (selectMode) onBookSelect(id) else onBookClick(id) }
        if (layout == BookshelfLayout.Grid) {
            BookshelfGridCard(
                id = id,
                result = result,
                selected = selected,
                selectMode = selectMode,
                shimmer = shimmer,
                onClick = onClick,
                onLongPress = { onLongPress(id) },
            )
        } else if (result?.isErr == true) {
            BookshelfBookError(selected, onClick) { onLongPress(id) }
        } else {
            result?.onOk {
                BookCardContent(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    bookInformation = it.bookInformation,
                    selected = selected,
                    collected = false,
                    onClick = onClick,
                    onLongPress = { onLongPress(id) },
                    latestChapterTitle = it.lastUpdatedChapterTitle,
                    titleHeight = null,
                )
            } ?: BookCardContentSkeleton(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .then(if (shimmer != null) Modifier.shimmer(shimmer) else Modifier),
            )
        }
    }
}

@Composable
private fun CollapseHeader(
    icon: Painter,
    title: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    horizontalInset: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface.copy(alpha = 0.96f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = horizontalInset, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorialIconBadge(
                painter = icon,
                size = 28.dp,
                iconSize = 15.dp,
                cornerRadius = 9.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.W600,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 0f else 180f,
                label = "collapseHeaderArrow",
            )
            Icon(
                modifier = Modifier
                    .rotate(rotation)
                    .size(20.dp),
                painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                tint = colors.onSurfaceVariant,
            )
        }
        EditorialHairlineDivider()
    }
}

private fun sortBooks(
    source: List<Pair<String, Result<BookshelfBookItem, WebRequestError>?>>,
    allBookIds: List<String>,
    sortType: BookshelfSortType,
    sortReversed: Boolean,
): List<Pair<String, Result<BookshelfBookItem, WebRequestError>?>> {
    val stableIndexMap = allBookIds.withIndex().associate { it.value to it.index }
    val locale = Locale.getDefault()
    val collator = Collator.getInstance(locale)
    val sorted = when (sortType) {
        BookshelfSortType.Default -> source.sortedBy {
            stableIndexMap[it.first] ?: Int.MAX_VALUE
        }
        BookshelfSortType.Latest -> source.sortedWith(
            compareByDescending<Pair<String, Result<BookshelfBookItem, WebRequestError>?>> { pair ->
                pair.second?.map { it.bookInformation.lastUpdated }?.get()
            }.thenBy { stableIndexMap[it.first] ?: Int.MAX_VALUE },
        )
        BookshelfSortType.Name -> source.sortedWith(
            Comparator { left, right ->
                val leftTitle = left.second?.map { it.bookInformation.title }?.getOrElse { "" }.orEmpty()
                val rightTitle = right.second?.map { it.bookInformation.title }?.getOrElse { "" }.orEmpty()
                val nameCompare = collator.compare(
                    titleSortKey(leftTitle, locale),
                    titleSortKey(rightTitle, locale),
                )
                if (nameCompare != 0) {
                    nameCompare
                } else {
                    (stableIndexMap[left.first] ?: Int.MAX_VALUE).compareTo(stableIndexMap[right.first] ?: Int.MAX_VALUE)
                }
            },
        )
        BookshelfSortType.WordCount -> source.sortedWith(
            compareByDescending<Pair<String, Result<BookshelfBookItem, WebRequestError>?>> { pair ->
                pair.second?.map { it.bookInformation.wordCount.count }?.getOrElse { 0L } ?: 0L
            }.thenBy { stableIndexMap[it.first] ?: Int.MAX_VALUE },
        )
    }
    return if (sortType != BookshelfSortType.Default && sortReversed) {
        sorted.reversed()
    } else {
        sorted
    }
}

private fun titleSortKey(
    title: String,
    locale: Locale,
): String {
    if (title.any { Pinyin.isChinese(it) }) {
        return Pinyin.toPinyin(title, "").lowercase(locale)
    }
    return title.lowercase(locale)
}
