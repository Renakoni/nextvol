package indi.renakoni.nextvol.ui.home.bookshelf.reorder

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfBookItem
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfUiState
import indi.renakoni.nextvol.ui.home.bookshelf.home.BookshelfHomeUiState
import indi.renakoni.nextvol.utils.navigationBarSpacer
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.Flow
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfReorderBooksScreen(
    bookshelfId: Int,
    uiState: BookshelfHomeUiState,
    prepare: (Int) -> Unit,
    onExit: () -> Unit,
    moveBook: (Int, Int) -> Unit,
    onClickBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler {
        onExit()
        onClickBack()
    }
    LaunchedEffect(bookshelfId) {
        prepare(bookshelfId)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ReorderTopBar(
                title = stringResource(R.string.bookshelf_adjust_order),
                scrollBehavior = scrollBehavior,
                onBack = {
                    onExit()
                    onClickBack()
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            BookshelfReorderContent(
                reorderBooks = uiState.reorderBookIds,
                nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                moveBook = moveBook,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfReorderBookshelvesScreen(
    uiState: BookshelfHomeUiState,
    prepare: () -> Unit,
    onExit: (List<Int>) -> Unit,
    onClickBack: () -> Unit,
    onClickEditBookshelf: (Int) -> Unit,
) {
    var reorderedBookshelfIds by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    BackHandler {
        onExit(reorderedBookshelfIds)
        onClickBack()
    }
    LaunchedEffect(uiState.bookshelfList) {
        prepare()
        val currentBookshelfIds = uiState.bookshelfList.map { it.id }
        if (reorderedBookshelfIds.isEmpty()) {
            reorderedBookshelfIds = currentBookshelfIds
            return@LaunchedEffect
        }
        val currentBookshelfIdSet = currentBookshelfIds.toHashSet()
        val preservedBookshelfIds = reorderedBookshelfIds.filter(currentBookshelfIdSet::contains)
        val newBookshelfIds = currentBookshelfIds.filterNot(preservedBookshelfIds::contains)
        reorderedBookshelfIds = preservedBookshelfIds + newBookshelfIds
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ReorderTopBar(
                title = stringResource(R.string.bookshelf_adjust_bookshelf_order),
                scrollBehavior = scrollBehavior,
                onBack = {
                    onExit(reorderedBookshelfIds)
                    onClickBack()
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            BookshelfListReorderContent(
                reorderBookshelfIds = reorderedBookshelfIds,
                bookshelfList = uiState.bookshelfList,
                nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                moveBookshelf = { fromIndex, toIndex ->
                    reorderedBookshelfIds = reorderedBookshelfIds.toMutableList().apply {
                        add(toIndex, removeAt(fromIndex))
                    }
                },
                onClickEditBookshelf = onClickEditBookshelf,
            )
        }
    }
}

@Composable
fun BookshelfReorderContent(
    reorderBooks: List<Pair<String, Flow<Result<BookshelfBookItem, WebRequestError>>>>,
    nestedScrollConnection: NestedScrollConnection,
    moveBook: (Int, Int) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = WindowInsets.systemBars.asPaddingValues(),
    ) { from, to ->
        moveBook(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
        state = lazyListState,
        contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = reorderBooks,
            key = { it.first },
        ) { pair ->
            ReorderableItem(reorderableLazyListState, key = pair.first) { isDragging ->
                val result by pair.second.collectAsStateWithLifecycle(null)
                val elevation by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "bookReorderElevation")
                val cardRadius = 18.dp
                val borderColor = if (isDragging) colors.primary else tokens.cardBorder
                val cardBg = if (isDragging) tokens.cardElevatedSurface else tokens.cardSurface

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .shadow(
                            elevation = elevation,
                            shape = RoundedCornerShape(cardRadius),
                            ambientColor = tokens.glassShadow,
                            spotColor = tokens.glassShadow,
                            clip = false,
                        )
                        .squircleBackground(color = cardBg, cornerRadius = cardRadius)
                        .squircleBorder(
                            width = if (isDragging) 1.4.dp else 0.75.dp,
                            color = borderColor,
                            cornerRadius = cardRadius,
                        )
                        .squircleClip(cardRadius),
                ) {
                    result?.onOk { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(
                                bookId = item.bookInformation.id,
                                width = 56.dp,
                                height = 82.dp,
                                uri = item.bookInformation.coverUri,
                                title = item.bookInformation.title,
                                rounded = 8.dp,
                                author = item.bookInformation.author,
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = item.bookInformation.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                                    color = colors.onSurface,
                                )
                                Text(
                                    text = item.bookInformation.author,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.W500,
                                        letterSpacing = 0.15.sp,
                                    ),
                                    color = colors.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            DragHandleButton(
                                contentDescription = stringResource(R.string.bookshelf_adjust_order),
                            )
                        }
                    }?.onErr {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            EditorialIconBadge(
                                painter = painterResource(R.drawable.error_24px),
                                size = 40.dp,
                                iconSize = 20.dp,
                                cornerRadius = 12.dp,
                                containerColor = colors.errorContainer.copy(alpha = 0.72f),
                                contentColor = colors.error,
                            )
                            Text(
                                text = stringResource(R.string.bookshelf_layout_load_error),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            DragHandleButton(
                                contentDescription = stringResource(R.string.bookshelf_adjust_order),
                            )
                        }
                    } ?: Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 56.dp, height = 82.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceContainerHigh),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.82f)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colors.surfaceContainerHigh),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.45f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colors.surfaceContainerHigh),
                            )
                        }
                        DragHandleButton(
                            contentDescription = stringResource(R.string.bookshelf_adjust_order),
                        )
                    }
                }
            }
        }

        item(key = "book_count_footer") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    modifier = Modifier.padding(vertical = 12.dp),
                    text = stringResource(R.string.n_books, reorderBooks.size),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.W600,
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.onSurfaceVariant,
                )
            }
        }

        navigationBarSpacer()
    }
}

@Composable
fun BookshelfListReorderContent(
    reorderBookshelfIds: List<Int>,
    bookshelfList: List<BookshelfUiState>,
    nestedScrollConnection: NestedScrollConnection,
    moveBookshelf: (Int, Int) -> Unit,
    onClickEditBookshelf: (Int) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val hapticFeedback = LocalHapticFeedback.current
    val bookshelfMap = bookshelfList.associateBy { it.id }
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = WindowInsets.systemBars.asPaddingValues(),
    ) { from, to ->
        moveBookshelf(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
        state = lazyListState,
        contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = reorderBookshelfIds,
            key = { it },
        ) { id ->
            val bookshelf = bookshelfMap[id] ?: return@items
            ReorderableItem(reorderableLazyListState, key = id) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 10.dp else 0.dp, label = "bookshelfElevation")
                val cardRadius = 18.dp
                val borderColor = if (isDragging) colors.primary else tokens.cardBorder
                val cardBg = if (isDragging) tokens.cardElevatedSurface else tokens.cardSurface

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .shadow(
                            elevation = elevation,
                            shape = RoundedCornerShape(cardRadius),
                            ambientColor = tokens.glassShadow,
                            spotColor = tokens.glassShadow,
                            clip = false,
                        )
                        .squircleBackground(color = cardBg, cornerRadius = cardRadius)
                        .squircleBorder(
                            width = if (isDragging) 1.4.dp else 0.75.dp,
                            color = borderColor,
                            cornerRadius = cardRadius,
                        )
                        .squircleClip(cardRadius),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EditorialIconBadge(
                            painter = painterResource(R.drawable.outline_shelves_24px),
                            size = 38.dp,
                            iconSize = 19.dp,
                            cornerRadius = 12.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = bookshelf.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                                color = colors.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.n_books, bookshelf.allBookFlows.size),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = colors.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onClickEditBookshelf(bookshelf.id) },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.edit_square_24px),
                                contentDescription = stringResource(R.string.bookshelf_settings),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                        DragHandleButton(
                            contentDescription = stringResource(R.string.bookshelf_adjust_bookshelf_order),
                        )
                    }
                }
            }
        }

        navigationBarSpacer()
    }
}

@Composable
private fun ReorderableCollectionItemScope.DragHandleButton(
    contentDescription: String,
) {
    val hapticFeedback = LocalHapticFeedback.current
    IconButton(
        modifier = Modifier.draggableHandle(
            onDragStarted = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            },
            onDragStopped = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
        ),
        onClick = {},
    ) {
        Icon(
            painter = painterResource(R.drawable.drag_indicator_24px),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorderTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
) {
    EditorialTopBar(
        title = title,
        scrollBehavior = scrollBehavior,
        hazeState = null,
        frosted = false,
        navigationIcon = {
            EditorialIconButton(
                onClick = onBack,
                painter = painterResource(R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
        },
    )
}
