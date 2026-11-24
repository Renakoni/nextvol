package indi.renakoni.nextvol.ui.home.reading.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.valentinilk.shimmer.Shimmer
import com.valentinilk.shimmer.shimmer
import com.valentinilk.shimmer.unclippedBoundsInWindow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.bookDescriptionForDisplay
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.Loading
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.components.rememberSkeletonShimmer
import indi.renakoni.nextvol.ui.home.HomeSettingsAction
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.bottomBarPadding
import indi.renakoni.nextvol.utils.bottomBarSpacer
import indi.renakoni.nextvol.utils.formReadingDuration
import indi.renakoni.nextvol.utils.formTime
import indi.renakoni.nextvol.utils.navigationBarSpacer
import indi.renakoni.nextvol.utils.removeFromBookshelfAction
import indi.renakoni.nextvol.utils.showSnackbar
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import me.saket.swipe.SwipeAction
import me.saket.swipe.SwipeableActionsBox
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    recentReadingBooks: List<Pair<String, StateFlow<Result<RecentReadingBook, WebRequestError>?>>>,
    readingListLoading: Boolean,
    onClickBook: (String) -> Unit,
    onClickContinueReading: (String, String) -> Unit,
    onClickDownloadManager: () -> Unit,
    onClickStats: () -> Unit,
    onRemoveBook: (String) -> Unit,
    onAddBook: (String) -> Unit,
    onClickOpenChapters: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val contentHazeState = rememberHazeState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            ReadingTopBar(
                scrollBehavior = scrollBehavior,
                hazeState = contentHazeState,
                onClickDownloadManager = onClickDownloadManager,
                onClickStats = onClickStats,
                onSettings = onSettings,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        if (readingListLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                Loading()
            }
        } else if (recentReadingBooks.isEmpty()) {
            EmptyPage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .navigationBarsPadding()
                    .bottomBarPadding(),
                icon = painterResource(R.drawable.empty_90dp),
                title = stringResource(R.string.nothing_here),
                description = stringResource(R.string.nothing_here_desc_reading),
            )
        } else {
            ReadingContent(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(contentHazeState),
                topPadding = paddingValues.calculateTopPadding(),
                onClickBook = onClickBook,
                onClickContinueReading = onClickContinueReading,
                onClickOpenChapters = onClickOpenChapters,
                onAddBook = onAddBook,
                onRemoveBook = onRemoveBook,
                recentReadingBooks = recentReadingBooks,
            )
        }
    }
}

@Composable
private fun ReadingContent(
    modifier: Modifier,
    topPadding: Dp,
    onClickBook: (String) -> Unit,
    onClickContinueReading: (String, String) -> Unit,
    onAddBook: (String) -> Unit,
    onRemoveBook: (String) -> Unit,
    recentReadingBooks: List<Pair<String, StateFlow<Result<RecentReadingBook, WebRequestError>?>>>,
    onClickOpenChapters: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHost.current
    val listState = rememberLazyListState()

    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1.seconds)
        started = true
    }

    // Share the list-wide shimmer wave, stopping its frame clock when no placeholders remain.
    var loadingCards by remember { mutableIntStateOf(0) }
    val shimmerBounds = remember { mutableStateOf<Rect?>(null) }
    val shimmerInstance = if (loadingCards > 0) {
        rememberSkeletonShimmer(
            baseColor = colorScheme.surfaceContainerLow,
            highlightColor = colorScheme.surfaceContainerHigh,
        )
    } else {
        null
    }
    SideEffect { shimmerInstance?.updateBounds(shimmerBounds.value) }

    val removedItemString = stringResource(R.string.removed_item)
    val undoString = stringResource(R.string.undo)

    val deleteAction = remember(
        onRemoveBook,
        onAddBook,
        removedItemString,
        undoString,
    ) {
        { bookId: String, bookTitle: String ->
            removeFromBookshelfAction.toSwipeAction {
                onRemoveBook(bookId)
                showSnackbar(
                    coroutineScope = coroutineScope,
                    hostState = snackbarHostState,
                    message = removedItemString + bookTitle,
                    actionLabel = undoString,
                ) {
                    when (it) {
                        SnackbarResult.Dismissed -> {}
                        SnackbarResult.ActionPerformed -> onAddBook(bookId)
                    }
                }
            }
        }
    }

    val loadErrorLabel = stringResource(R.string.bookshelf_layout_load_error)

    LazyColumn(
        modifier = modifier.onGloballyPositioned {
            shimmerBounds.value = it.unclippedBoundsInWindow()
            shimmerInstance?.updateBounds(shimmerBounds.value)
        },
        contentPadding = PaddingValues(top = topPadding + 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        state = listState,
    ) {
        item(key = "hero_section") {
            Column(
                modifier = Modifier.animateItem(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionHeader(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    text = stringResource(R.string.continue_reading),
                )
                ReadingHeaderCardPager(
                    items = recentReadingBooks.take(3),
                    onClickBook = onClickBook,
                    onClickContinueReading = onClickContinueReading,
                    onClickOpenChapters = onClickOpenChapters,
                    shimmerInstance = if (started) shimmerInstance else null,
                    onPlaceholderPresenceChanged = { present ->
                        loadingCards = (loadingCards + if (present) 1 else -1).coerceAtLeast(0)
                    },
                )
            }
        }

        item(key = "recent_header") {
            SectionHeader(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                text = stringResource(
                    R.string.recent_reads,
                    recentReadingBooks.size,
                ),
            )
        }

        items(
            items = recentReadingBooks,
            key = { it.first },
            contentType = { "ReadingBookCard" },
        ) { pair ->
            Box(
                modifier = Modifier
                    .animateItem()
                    .padding(horizontal = 16.dp),
            ) {
                val result by pair.second.collectAsStateWithLifecycle()
                result?.onOk {
                    ReadingBookCard(
                        bookInformation = it.bookInformation,
                        userReadingData = it.userReadingData,
                        onClick = { onClickBook(it.id) },
                        swipeToLeftActions = remember(it.id, it.bookInformation.title) {
                            listOf(deleteAction(it.id, it.bookInformation.title))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }?.onErr {
                    ReadingBookErrorCard(
                        bookId = pair.first,
                        onClick = { onClickBook(pair.first) },
                        swipeToLeftActions = remember(pair.first, loadErrorLabel) {
                            listOf(deleteAction(pair.first, loadErrorLabel))
                        },
                    )
                } ?: run {
                    DisposableEffect(Unit) {
                        loadingCards++
                        onDispose { loadingCards-- }
                    }
                    ReadingBookCardSkeleton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (started && shimmerInstance != null) Modifier.shimmer(shimmerInstance)
                                else Modifier,
                            ),
                    )
                }
            }
        }

        navigationBarSpacer()
        bottomBarSpacer()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    hazeState: HazeState,
    onClickDownloadManager: () -> Unit,
    onClickStats: () -> Unit,
    onSettings: () -> Unit,
) {
    EditorialTopBar(
        title = stringResource(R.string.nav_reading),
        scrollBehavior = scrollBehavior,
        hazeState = hazeState,
        frosted = true,
        actions = {
            EditorialIconButton(
                onClick = onClickDownloadManager,
                painter = painterResource(R.drawable.download_24px),
                contentDescription = stringResource(R.string.book_manager_tab_downloads),
            )
            EditorialIconButton(
                onClick = onClickStats,
                painter = painterResource(R.drawable.analytics_24px),
                contentDescription = stringResource(R.string.nav_statistics),
            )
            HomeSettingsAction(onSettings)
            Spacer(Modifier.width(4.dp))
        },
    )
}

@Composable
fun ReadingHeaderCardPager(
    items: List<Pair<String, StateFlow<Result<RecentReadingBook, WebRequestError>?>>>,
    modifier: Modifier = Modifier,
    onClickBook: (String) -> Unit,
    onClickContinueReading: (String, String) -> Unit,
    onClickOpenChapters: (String) -> Unit,
    shimmerInstance: Shimmer? = null,
    onPlaceholderPresenceChanged: (Boolean) -> Unit = {},
) {
    val pageCount = items.size.coerceIn(1, 3)
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val (bookId, recentReadingBookFlow) = items.getOrNull(page) ?: return@HorizontalPager
            val recentReadingBookResult by recentReadingBookFlow.collectAsStateWithLifecycle()

            recentReadingBookResult?.onOk {
                EditorialHeroBookCard(
                    info = it.bookInformation,
                    data = it.userReadingData,
                    onClickBook = onClickBook,
                    onClickContinueReading = onClickContinueReading,
                    onClickOpenChapters = onClickOpenChapters,
                )
            }?.onErr {
                EditorialHeroErrorCard(
                    onClick = { onClickBook(bookId) },
                )
            } ?: run {
                DisposableEffect(bookId) {
                    onPlaceholderPresenceChanged(true)
                    onDispose { onPlaceholderPresenceChanged(false) }
                }
                EditorialHeroBookCardSkeleton(
                    modifier = if (shimmerInstance != null) Modifier.shimmer(shimmerInstance) else Modifier,
                )
            }
        }

        if (pageCount > 1) {
            HorizontalCapsuleIndicator(
                pageCount = pageCount,
                currentPage = pagerState.currentPage,
            )
        }
    }
}

@Composable
private fun HorizontalCapsuleIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            val width by animateDpAsState(
                targetValue = if (selected) 22.dp else 6.dp,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "heroIndicatorWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) colorScheme.primary else colorScheme.outlineVariant,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                label = "heroIndicatorColor",
            )
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun EditorialHeroBookCard(
    info: BookInformation,
    data: UserReadingData,
    onClickBook: (String) -> Unit,
    onClickContinueReading: (String, String) -> Unit,
    onClickOpenChapters: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    val dateFormatter = remember { DateTimeFormatter.ofPattern("MM/dd") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateText = remember(data.lastReadTime) { data.lastReadTime?.format(dateFormatter) }
    val timeText = remember(data.lastReadTime) { data.lastReadTime?.format(timeFormatter) }
    val progressPercent = remember(data.readingProgress) {
        (data.readingProgress.coerceIn(0f, 1f) * 100f).toInt()
    }
    val heroCorner = 24.dp

    val ambientBrush = remember(tokens.isDark, colorScheme.primaryContainer, colorScheme.tertiaryContainer, tokens.cardSurface) {
        Brush.linearGradient(
            colors = listOf(
                colorScheme.primaryContainer.copy(alpha = if (tokens.isDark) 0.34f else 0.42f),
                colorScheme.tertiaryContainer.copy(alpha = if (tokens.isDark) 0.16f else 0.18f),
                tokens.cardSurface,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .squircleBackground(color = tokens.cardSurface, cornerRadius = heroCorner)
            .squircleClip(heroCorner)
            .background(ambientBrush)
            .squircleBorder(width = 0.85.dp, color = tokens.cardBorder, cornerRadius = heroCorner)
            .clickable { onClickBook(info.id) }
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = tokens.glassShadow,
                    spotColor = tokens.glassShadow,
                    clip = false,
                ),
            ) {
                Cover(
                    bookId = info.id,
                    width = 112.dp,
                    height = 164.dp,
                    uri = info.coverUri,
                    title = info.title,
                    author = info.author,
                    rounded = 12.dp,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(164.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dateText != null && timeText != null) {
                        FrostedMetaChip(
                            text = "$dateText $timeText",
                            leadingPainter = painterResource(R.drawable.calendar_today_24px),
                        )
                    }
                    FrostedMetaChip(
                        text = "$progressPercent%",
                        emphasized = true,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = info.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = typography.displayMedium.copy(fontWeight = FontWeight.W700),
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = data.lastReadChapterTitle ?: stringResource(R.string.never_read),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.primary,
                    )
                }

                EditorialProgressBar(
                    progress = data.readingProgress,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val contentsDesc = stringResource(R.string.detail_contents)
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .squircleBackground(
                                color = tokens.cardElevatedSurface.copy(alpha = 0.86f),
                                cornerRadius = 13.dp,
                            )
                            .squircleBorder(
                                width = 0.75.dp,
                                color = tokens.cardBorder,
                                cornerRadius = 13.dp,
                            )
                            .squircleClip(13.dp)
                            .clickable { onClickOpenChapters(info.id) }
                            .semantics {
                                role = Role.Button
                                contentDescription = contentsDesc
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.view_list_24px),
                            contentDescription = null,
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Button(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        onClick = {
                            val chapterId = data.lastReadChapterId
                            if (chapterId != null) {
                                onClickContinueReading(info.id, chapterId)
                            } else {
                                onClickBook(info.id)
                            }
                        },
                        shape = RoundedCornerShape(13.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.resume_last_reading),
                            style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialHeroBookCardSkeleton(
    modifier: Modifier = Modifier,
) {
    val tokens = NextVolDesign.tokens
    val blockColor = colorScheme.surfaceContainerHigh
    EditorialCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp, 164.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(blockColor),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(164.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(blockColor),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(blockColor),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(blockColor),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(blockColor),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(tokens.cardElevatedSurface),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(blockColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorialHeroErrorCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EditorialCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 24.dp,
        contentPadding = PaddingValues(20.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorialIconBadge(
                painter = painterResource(R.drawable.error_24px),
                size = 52.dp,
                iconSize = 26.dp,
                cornerRadius = 16.dp,
                containerColor = colorScheme.errorContainer.copy(alpha = 0.72f),
                contentColor = colorScheme.error,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.bookshelf_layout_load_error),
                    style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.detail_title),
                    style = typography.labelMedium,
                    color = colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ReadingBookCard(
    modifier: Modifier = Modifier,
    bookInformation: BookInformation,
    userReadingData: UserReadingData,
    onClick: () -> Unit,
    swipeToRightActions: List<SwipeAction> = emptyList(),
    swipeToLeftActions: List<SwipeAction> = emptyList(),
) {
    val tokens = NextVolDesign.tokens
    val cardRadius = 18.dp
    val neverRead = stringResource(R.string.never_read)
    val lastRead = remember(userReadingData.lastReadTime, neverRead) {
        userReadingData.lastReadTime?.let(::formTime) ?: neverRead
    }
    val readingTime = remember(userReadingData.totalReadTime) {
        formReadingDuration(userReadingData.totalReadTime.toLong())
    }
    val progressPercent = remember(userReadingData.readingProgress) {
        "${(userReadingData.readingProgress.coerceIn(0f, 1f) * 100f).toInt()}%"
    }
    val infoText = "$lastRead · $readingTime"
    val description = remember(bookInformation.id, bookInformation.description) {
        bookDescriptionForDisplay(bookInformation.id, bookInformation.description).trim()
    }

    Box(
        modifier = modifier
            .squircleBackground(color = tokens.cardSurface, cornerRadius = cardRadius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = cardRadius)
            .squircleClip(cardRadius),
    ) {
        SwipeableActionsBox(
            startActions = swipeToRightActions,
            endActions = swipeToLeftActions,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.cardSurface)
                    .combinedClickable(onClick = onClick)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(
                    bookId = bookInformation.id,
                    width = 84.dp,
                    height = 122.dp,
                    uri = bookInformation.coverUri,
                    title = bookInformation.title,
                    author = bookInformation.author,
                    rounded = 10.dp,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = bookInformation.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.onSurface,
                    )

                    if (bookInformation.author.isNotBlank()) {
                        Text(
                            text = bookInformation.author,
                            style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
                            color = colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.outline_schedule_24px),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            modifier = Modifier.weight(1f),
                            text = infoText,
                            style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = progressPercent,
                            style = typography.labelSmall.copy(
                                fontWeight = FontWeight.W600,
                                fontFeatureSettings = "tnum",
                            ),
                            color = colorScheme.primary,
                            maxLines = 1,
                        )
                    }

                    EditorialProgressBar(
                        progress = userReadingData.readingProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingBookErrorCard(
    bookId: String,
    onClick: () -> Unit,
    swipeToLeftActions: List<SwipeAction>,
) {
    val tokens = NextVolDesign.tokens
    val cardRadius = 18.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .squircleBackground(color = tokens.cardSurface, cornerRadius = cardRadius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = cardRadius)
            .squircleClip(cardRadius),
    ) {
        SwipeableActionsBox(
            endActions = swipeToLeftActions,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.cardSurface)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.error_24px),
                    size = 40.dp,
                    iconSize = 20.dp,
                    cornerRadius = 12.dp,
                    containerColor = colorScheme.errorContainer.copy(alpha = 0.72f),
                    contentColor = colorScheme.error,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.bookshelf_layout_load_error),
                        style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = bookId,
                        style = typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun ReadingBookCardSkeleton(
    modifier: Modifier = Modifier,
) {
    val roundedSmall = RoundedCornerShape(6.dp)
    val roundedLarge = RoundedCornerShape(10.dp)
    val baseColor = colorScheme.surfaceContainerHigh

    EditorialCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp, 122.dp)
                    .clip(roundedLarge)
                    .background(baseColor),
            )

            Column(
                modifier = Modifier
                    .height(122.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .height(22.dp)
                        .clip(roundedSmall)
                        .background(baseColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(15.dp)
                        .clip(roundedSmall)
                        .background(baseColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .height(30.dp)
                        .clip(roundedSmall)
                        .background(baseColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(baseColor),
                )
            }
        }
    }
}

@Composable
private fun EditorialProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(4.dp)
            .clip(CircleShape)
            .background(colorScheme.surfaceContainerHighest),
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colorScheme.primary),
            )
        }
    }
}

@Composable
private fun FrostedMetaChip(
    text: String,
    modifier: Modifier = Modifier,
    leadingPainter: Painter? = null,
    emphasized: Boolean = false,
) {
    val tokens = NextVolDesign.tokens
    val shape = RoundedCornerShape(8.dp)
    val bg = if (emphasized) tokens.activePillFill else tokens.cardElevatedSurface.copy(alpha = 0.78f)
    val borderColor = if (emphasized) tokens.activePillBorder else tokens.cardBorder
    val fg = if (emphasized) colorScheme.primary else colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(0.6.dp, borderColor, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leadingPainter != null) {
            Icon(
                painter = leadingPainter,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = fg,
            )
        }
        Text(
            text = text,
            style = typography.labelSmall.copy(
                fontWeight = FontWeight.W600,
                fontFeatureSettings = "tnum",
            ),
            color = fg,
            maxLines = 1,
        )
    }
}
