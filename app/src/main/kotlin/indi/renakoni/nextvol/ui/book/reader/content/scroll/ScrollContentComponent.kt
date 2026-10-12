@file:Suppress("AssignedValueIsNeverRead")

package indi.renakoni.nextvol.ui.book.reader.content.scroll

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.resolveReaderBodyLayout
import indi.renakoni.nextvol.ui.book.reader.LocalReaderLayoutResult
import indi.renakoni.nextvol.ui.book.reader.readerBodyGeometry
import indi.renakoni.nextvol.ui.book.reader.content.ReaderMode
import indi.renakoni.nextvol.ui.book.reader.ReaderFontFamilySettings
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentError
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentLoading
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderPositionSession
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderRendererActive
import androidx.compose.ui.semantics.clearAndSetSemantics
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPosition
import indi.renakoni.nextvol.ui.book.reader.content.RegisterReaderPositionCapture
import indi.renakoni.nextvol.ui.book.reader.content.componet.readerTextColor as readerContentTextColor
import indi.renakoni.nextvol.ui.book.reader.content.readerTapGestures
import indi.renakoni.nextvol.ui.book.reader.content.readerVolumeKeys
import indi.renakoni.nextvol.ui.book.reader.content.volumeKeyScrollDistance
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechFollow
import indi.renakoni.nextvol.ui.book.reader.content.PrepareReaderSpeechIndex
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechRanges
import indi.renakoni.nextvol.ui.book.reader.content.readerSpeechManualScroll
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.ui.book.reader.readerTextColor
import indi.renakoni.nextvol.ui.book.reader.rememberReaderBackgroundPainter
import indi.renakoni.nextvol.ui.book.reader.usesBackgroundImage
import indi.renakoni.nextvol.ui.book.reader.rememberReaderFontFamily
import indi.renakoni.nextvol.utils.showSnackbar
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkPosition
import indi.renakoni.nextvol.ui.book.reader.bookmark.RegisterBookmarkCapture
import indi.renakoni.nextvol.ui.book.reader.bookmark.anchorIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle

@Composable
fun ScrollContentComponent(
    modifier: Modifier,
    uiState: ScrollContentUiState,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    chapterTitle: (String) -> String? = { null },
) {
    ScrollContentTextComponent(
        modifier = modifier,
        uiState = uiState,
        settingState = settingState,
        fontFamilySettings = fontFamilySettings,
        paddingValues = paddingValues,
        changeIsImmersive = changeIsImmersive,
        onClickPrevChapter = onClickPrevChapter,
        onClickNextChapter = onClickNextChapter,
        chapterTitle = chapterTitle,
    )
}

@Composable
fun ScrollContentTextComponent(
    modifier: Modifier,
    uiState: ScrollContentUiState,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    chapterTitle: (String) -> String? = { null },
) {
    val snackbarHostState = LocalSnackbarHost.current
    val active by rememberUpdatedState(LocalReaderRendererActive.current)
    val density = LocalDensity.current
    val screenHeight = LocalResources.current.displayMetrics.heightPixels
    val listState = uiState.lazyListState
    val scope = rememberCoroutineScope()
    var hostSize by remember { mutableStateOf(IntSize.Zero) }
    val layoutResult = resolveReaderBodyLayout(hostSize, paddingValues, ReaderMode.Scroll,
        preference = settingState.pageLayout)
    val geometry = layoutResult.geometry
    val layoutStatus = LocalReaderLayoutResult.current
    val lazyColumnSize = geometry?.leafSize ?: IntSize.Zero
    // The controller's viewport and text preparation use exactly the pixels measured by the body.
    SideEffect {
        if (active) {
            uiState.setLazyColumnSize(lazyColumnSize)
            layoutStatus?.value = layoutResult
        }
    }
    val textLayout = LocalReaderTextLayout.current
    // Lazy items can recompose during scrolling before this parent does. Capture the window
    // once so their keys, neighbours and prepared bodies always use the same slot ordering.
    val chapterEntries = uiState.contentList.toList()
    val showAdjacentChapters = settingState.isUsingContinuousScrolling &&
        chapterEntries.getOrNull(1)?.second?.isErr != true
    val preparedChapters = chapterEntries.mapIndexed { index, entry ->
        key(listState, entry?.first ?: "placeholder-$index") {
            PrepareReaderSpeechIndex(entry?.second?.get())
            rememberPreparedScrollChapter(entry?.second?.get(), textLayout,
                lazyColumnSize.width, lazyColumnSize.height)
        }
    }
    val bookmarks = LocalReaderBookmarks.current
    val positions = LocalReaderPositionSession.current
    var bookmarkReady by remember(listState) { mutableStateOf(false) }
    var entryReady by remember(listState) { mutableStateOf(false) }
    var readingPosition by remember(listState) { mutableStateOf<ReaderPosition?>(null) }
    var anchoredViewport by remember(listState) { mutableStateOf<Pair<Int, Int>?>(null) }
    var restoredGeometry by remember(listState) { mutableStateOf(textLayout to IntSize.Zero) }
    var restorationOwner by remember(listState) { mutableStateOf<Any?>(null) }
    val speech by rememberUpdatedState(LocalReaderSpeechFollow.current)
    var followedSpeechPosition by remember(uiState) { mutableStateOf(speech.position) }
    val latestPrepared by rememberUpdatedState(preparedChapters)
    val reduceMotion by rememberUpdatedState(settingState.reduceMotion)
    fun viewport() = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
    fun capturePosition(): ReaderPosition? {
        if (!active || !bookmarkReady || uiState.lazyListState !== listState || listState.isScrollInProgress ||
            positions?.pending != null || bookmarks?.pending != null ||
            restoredGeometry != (textLayout to lazyColumnSize)) return null
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 } ?: return null
        val prepared = latestPrepared.getOrNull(item.index)?.takeIf {
            it.content === uiState.contentList.getOrNull(item.index)?.second?.get() &&
                (textLayout == null || it.layout == textLayout && it.size == lazyColumnSize)
        } ?: return null
        if (anchoredViewport == viewport() && readingPosition?.resolve(uiState.bookId, prepared.content) != null)
            return readingPosition
        return prepared.anchorAt((-item.offset).coerceAtLeast(0))?.let {
            ReaderPosition.capture(uiState.bookId, prepared.content, it)
        }
    }
    // Local function references compare equal even when their captured list/geometry changes.
    val renderer = RegisterReaderPositionCapture(uiState) { capturePosition() }
    fun ownsRenderer() = active && (positions == null || positions.ownsRenderer(uiState, renderer))
    SideEffect {
        capturePosition()?.let { readingPosition = it; anchoredViewport = viewport() }
    }
    fun speechTarget(initialPlacement: Boolean = false): Pair<Int, Int>? {
        if (!active || !bookmarkReady && !initialPlacement) return null
        if (!speech.following || !speech.active && !initialPlacement) return null
        val index = latestPrepared.indexOfFirst { it?.content?.id == speech.position?.chapterId }
        val prepared = latestPrepared.getOrNull(index) ?: return null
        val anchor = speech.anchor(prepared.content) ?: return null
        return prepared.offsetFor(anchor)?.let { index to it }
    }

    RegisterBookmarkCapture {
        if (!bookmarkReady || uiState.lazyListState !== listState || listState.isScrollInProgress || bookmarks?.pending != null) null
        else listState.layoutInfo.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 }?.let { item ->
            val prepared = latestPrepared.getOrNull(item.index)?.takeIf {
                it.content === uiState.contentList.getOrNull(item.index)?.second?.get() &&
                    (textLayout == null || it.layout == textLayout && it.size == lazyColumnSize)
            }
            prepared?.anchorAt((-item.offset).coerceAtLeast(0))?.let { anchor ->
                ReaderBookmarkPosition(uiState.bookId, prepared.content, anchor,
                    (lazyColumnSize.height - item.offset).toFloat() / item.size.coerceAtLeast(1))
            }
        }
    }
    val bookmark = bookmarks?.pending
    val bookmarkChapter = preparedChapters.firstOrNull { it?.content?.id == bookmark?.chapterId }
    LaunchedEffect(active, listState, bookmark, uiState.readingChapterId, bookmarkChapter, textLayout, lazyColumnSize) {
        if (!active || bookmark == null || bookmark.bookId != uiState.bookId || bookmark.chapterId != uiState.readingChapterId) return@LaunchedEffect
        snapshotFlow { bookmarkReady }.first { it }
        val prepared = bookmarkChapter?.takeIf {
            it.content === uiState.readingChapterContent?.get() &&
                (textLayout == null || it.layout == textLayout && it.size == lazyColumnSize)
        } ?: return@LaunchedEffect
        val anchor = withContext(Dispatchers.Default) { bookmark.anchorIn(prepared.content) }
        if (!ownsRenderer()) return@LaunchedEffect
        if (anchor == null) { bookmarks.finish(bookmark, false); return@LaunchedEffect }
        val index = latestPrepared.indexOf(prepared)
        if (index < 0 || bookmarks.pending !== bookmark) return@LaunchedEffect
        val attempt = Any().also { restorationOwner = it }
        var positioned = false
        uiState.onProgressRestoring(listState)
        try {
            listState.scrollToItem(index)
            val offset = snapshotFlow { prepared.offsetFor(anchor) }.filterNotNull().first()
            if (bookmarks.pending !== bookmark || uiState.lazyListState !== listState ||
                !ownsRenderer()) return@LaunchedEffect
            listState.scrollToItem(index, offset)
            if (bookmarks.pending !== bookmark || !ownsRenderer())
                return@LaunchedEffect
            readingPosition = ReaderPosition.capture(uiState.bookId, prepared.content, anchor)
            anchoredViewport = viewport()
            positions?.positioned(uiState, readingPosition)
            bookmarks.finish(bookmark, true)
            positioned = true
        } finally {
            if (restorationOwner === attempt && ownsRenderer()) {
                uiState.onProgressRestored(listState)
                if (positioned) uiState.onReadingPositioned(listState)
            }
        }
    }

    val reachedTopMsg = stringResource(R.string.reader_reached_top)
    val prevChapterLabel = stringResource(R.string.previous_chapter)
    val reachedBottomMsg = stringResource(R.string.reader_reached_bottom)
    val nextChapterLabel = stringResource(R.string.next_chapter)
    val confirmLabel = stringResource(R.string.confirm)
    val reachedStartMsg = stringResource(R.string.reader_reached_start)
    val reachedEndMsg = stringResource(R.string.reader_reached_end)

    val positionRequest = positions?.pending
    LaunchedEffect(active, listState, textLayout, lazyColumnSize, uiState.readingChapterContent, positionRequest) {
        // Promoting an adjacent chapter is natural reading, not a new percentage restore.
        if (bookmarkReady && restoredGeometry == (textLayout to lazyColumnSize) && positions?.pending == null)
            return@LaunchedEffect
        if (!ownsRenderer()) return@LaunchedEffect
        val reflow = bookmarkReady || positionRequest != null || readingPosition != null
        var publishInitial = false
        val attempt = Any().also { restorationOwner = it }
        bookmarkReady = false
        positions?.reflow(uiState)
        uiState.onProgressRestoring(listState)
        try {
            snapshotFlow {
                uiState.readingChapterContent?.isErr == true ||
                    lazyColumnSize.width > 0 && lazyColumnSize.height > 0 &&
                    latestPrepared.any { it?.content === uiState.readingChapterContent?.get() &&
                        it != null && (textLayout == null || it.layout == textLayout && it.size == lazyColumnSize) } &&
                    listState.layoutInfo.visibleItemsInfo.isNotEmpty()
            }.first { it }
            if (uiState.readingChapterContent?.isErr == true) {
                positions?.pending?.let { positions.finish(uiState, it, null) }
                listState.scrollToItem(1)
                if (uiState.lazyListState === listState && ownsRenderer()) entryReady = true
                return@LaunchedEffect
            }
            val prepared = latestPrepared.first { it?.content === uiState.readingChapterContent?.get() }!!
            val index = latestPrepared.indexOf(prepared)
            val request = positions?.pending
            val restoredProgress = uiState.readingProgress
            snapshotFlow { !speech.awaitingIndex(prepared.content) }.first { it }
            val speechAnchor = speech.anchor(prepared.content)
            val source = if (bookmarks?.pending == null) request?.position ?: readingPosition else null
            val anchor = speechAnchor ?: source?.resolve(uiState.bookId, prepared.content)?.anchor
            // Place the chapter before waiting for its component coordinates.
            withFrameNanos { }
            listState.scrollToItem(index)
            val item = snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == prepared.content.id && it.contentType == true }
            }.filterNotNull().first()
            val sourceOffset = if (anchor != null) {
                snapshotFlow { prepared.isPositioned(anchor.componentIndex) }.first { it }
                prepared.offsetFor(anchor)
            } else null
            if (uiState.lazyListState !== listState || !ownsRenderer() ||
                request != null && !positions.isCurrent(uiState, request))
                return@LaunchedEffect
            val offset = when {
                sourceOffset != null && speechAnchor != null -> (sourceOffset - lazyColumnSize.height * 0.22f).toInt().coerceAtLeast(0)
                sourceOffset != null -> sourceOffset
                restoredProgress <= 0f -> 0
                else -> ((item.size * restoredProgress).toInt() - lazyColumnSize.height).coerceAtLeast(0)
            }
            listState.scrollToItem(index, offset)
            if (!ownsRenderer() ||
                request != null && !positions.isCurrent(uiState, request)) return@LaunchedEffect
            readingPosition = anchor?.takeIf { sourceOffset != null }?.let {
                ReaderPosition.capture(uiState.bookId, prepared.content, it)
            }
            anchoredViewport = viewport()
            if (request != null) positions.finish(uiState, request, readingPosition)
            else if (speechAnchor != null) positions?.positioned(uiState, readingPosition)
            restoredGeometry = textLayout to lazyColumnSize
            bookmarkReady = true
            publishInitial = !reflow && restoredProgress <= 0f && anchor == null && bookmarks?.pending == null
        } finally {
            // Cancellation hands restoration to the replacement effect. Releasing the barrier
            // here can promote a neighbour under the old pixel offset while the body reflows.
            if (currentCoroutineContext().isActive && restorationOwner === attempt && ownsRenderer()) {
                uiState.onProgressRestored(listState)
                if (publishInitial) uiState.onReadingPositioned(listState)
            }
        }
    }
    LaunchedEffect(active, listState) {
        if (!active) return@LaunchedEffect
        snapshotFlow { if (bookmarkReady && !uiState.isRestoringProgress) speechTarget() else null }.collectLatest { target ->
            if (target == null || !ownsRenderer()) return@collectLatest
            val viewport = listState.layoutInfo.viewportSize.height
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target.first }
            val y = visible?.offset?.plus(target.second)
            if (y == null || y !in (viewport * 0.15f).toInt()..(viewport * 0.72f).toInt()) {
                val targetOffset = (target.second - viewport * 0.22f).toInt().coerceAtLeast(0)
                if (!reduceMotion && y != null && kotlin.math.abs(y) < viewport * 2)
                    listState.animateScrollToItem(target.first, targetOffset)
                else listState.scrollToItem(target.first, targetOffset)
            }
            if (!ownsRenderer()) return@collectLatest
            val chapter = latestPrepared.getOrNull(target.first)?.content ?: return@collectLatest
            readingPosition = speech.anchor(chapter)?.let { ReaderPosition.capture(uiState.bookId, chapter, it) }
            anchoredViewport = viewport()
            positions?.positioned(uiState, readingPosition)
            if (followedSpeechPosition != speech.position) uiState.onReadingPositioned(listState)
            followedSpeechPosition = speech.position
        }
    }
    LaunchedEffect(active, listState) {
        if (!active) return@LaunchedEffect
        var atTop = false
        var atBottom = false

        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val isAtTop = !listState.canScrollBackward
                    val isAtBottom = !listState.canScrollForward

                    when {
                        isAtTop -> {
                            if (atTop) {
                                if (uiState.readingChapterContent?.map { it.hasPrevChapter() }?.get() == true)
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedTopMsg,
                                            actionLabel = prevChapterLabel
                                        ) { if (it == SnackbarResult.ActionPerformed) onClickPrevChapter() }
                                    }
                                else
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedStartMsg,
                                            actionLabel = confirmLabel
                                        )
                                    }
                            }
                            atTop = true; atBottom = false
                        }

                        isAtBottom -> {
                            if (atBottom) {
                                if (uiState.readingChapterContent?.map { it.hasNextChapter() }?.get() == true)
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedBottomMsg,
                                            actionLabel = nextChapterLabel
                                        ) { if (it == SnackbarResult.ActionPerformed) onClickNextChapter() }
                                    }
                                else
                                    launch {
                                        showSnackbar(
                                            coroutineScope = this,
                                            hostState = snackbarHostState,
                                            message = reachedEndMsg,
                                            actionLabel = confirmLabel
                                        )
                                    }
                            }
                            atBottom = true; atTop = false
                        }

                        else -> {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            atTop = false; atBottom = false
                        }
                    }
                }
            }
    }

    if (settingState.usesBackgroundImage && settingState.backgroundImageDisplayMode == MenuOptions.ReaderBgImageDisplayModeOptions.Loop) {
        Image(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) {
                    screenHeight.toDp()
                })
                .offset {
                    IntOffset(0, (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset
                        ?: 0) % screenHeight + screenHeight)
                },
            painter = rememberReaderBackgroundPainter(settingState),
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
        Image(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) {
                    screenHeight.toDp()
                })
                .offset {
                    IntOffset(0, (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.offset
                        ?: 0) % screenHeight)
                },
            painter = rememberReaderBackgroundPainter(settingState),
            contentDescription = null,
            contentScale = ContentScale.Crop
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (active) uiState.writeProgressRightNow()
    }
    // Measure and position the list behind one loader; revealing it earlier exposes all three
    // chapter placeholders and the intermediate scrollToItem steps. Reflows keep the body visible.
    val showContent = entryReady || (bookmarkReady && bookmarks?.pending == null && !uiState.isRestoringProgress)
    SideEffect { if (showContent) entryReady = true }
    Box(
        modifier = modifier
                .fillMaxSize()
                .onSizeChanged { hostSize = it }
                .readerSpeechManualScroll {
                    if (ownsRenderer())
                        uiState.onReadingPositioned(listState)
                }
                .readerVolumeKeys(
                    enabled = settingState.isUsingVolumeKeyFlip && !settingState.isUsingFlipPage &&
                        showContent &&
                        !uiState.isRestoringProgress &&
                        uiState.readingChapterContent?.get() != null && lazyColumnSize.height > 0,
                    intervalSeconds = settingState.volumeKeyContinuousFlipInterval,
                ) { direction ->
                    speech.onManualNavigation()
                    val distance = volumeKeyScrollDistance(
                        listState.layoutInfo.viewportSize.height,
                        settingState.volumeKeyScrollFraction,
                        direction,
                    )
                    val canScroll = if (distance > 0f) listState.canScrollForward else listState.canScrollBackward
                    listState.scroll {
                        var consumed = 0f
                        fun moveTo(target: Float) {
                            val delta = scrollBy(target - consumed)
                            consumed += delta
                            // Record actual movement, including frames before cancellation.
                            if (delta != 0f && ownsRenderer()) uiState.onReadingPositioned(listState)
                        }
                        if (reduceMotion || !canScroll) {
                            moveTo(distance)
                        } else {
                            animate(0f, distance, animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) {
                                value, _ -> moveTo(value)
                            }
                        }
                    }
                }
                .readerTapGestures { changeIsImmersive() },
    ) {
        if (geometry != null) LazyColumn(
            modifier = Modifier.fillMaxSize().readerBodyGeometry(geometry)
                .drawWithContent { if (showContent) drawContent() }
                .then(if (active && showContent) Modifier else Modifier.clearAndSetSemantics { }),
            state = listState,
            userScrollEnabled = active && showContent && (!uiState.isRestoringProgress || uiState.readingChapterContent?.isErr == true),
        ) {
            itemsIndexed(
                items = chapterEntries,
                key = { index, pair -> pair?.first ?: "placeholder-$index" },
                contentType = { index, pair -> pair?.second?.isOk == true && preparedChapters[index] != null },
            ) { index, pair ->
                if (index != 1 && !showAdjacentChapters) return@itemsIndexed
                pair?.second.let { result ->
                    chapterEntries.getOrNull(index + 1)?.second?.get()?.let {
                        if (!it.hasPrevChapter()) return@itemsIndexed
                    }
                    chapterEntries.getOrNull(index - 1)?.second?.get()?.let {
                        if (!it.hasNextChapter()) return@itemsIndexed
                    }
                    result?.onOk {
                        val prepared = preparedChapters[index]
                        if (prepared == null) Box(Modifier.fillParentMaxHeight().fillMaxWidth()) {
                            if (showContent) ChapterContentLoading()
                        } else TextContent(
                            modifier = modifier,
                            settingState = settingState,
                            fontFamilySettings = fontFamilySettings,
                            prepared = prepared,
                        )
                    }?.onErr {
                        ChapterContentError(it, pair?.first?.let(chapterTitle)) {
                            pair?.first?.let(uiState.retryChapter)
                        }
                    } ?: Box(Modifier.fillParentMaxHeight().fillMaxWidth()) {
                        if (showContent) ChapterContentLoading()
                    }
                }
            }
        }
        if (!showContent) Box(Modifier.matchParentSize().pointerInput(Unit) {
            // Hidden chapter controls must not receive gestures while the list is being positioned.
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        }) { ChapterContentLoading() }
    }
}

@Composable
private fun TextContent(
    modifier: Modifier,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    prepared: PreparedScrollChapter,
) {
    val content = prepared.content
    val speechRanges = LocalReaderSpeechFollow.current.ranges(content)
    val density = LocalDensity.current
    val screenHeight = LocalResources.current.displayMetrics.heightPixels
    val textColor = readerTextColor(settingState)
    val fontFamily = rememberReaderFontFamily(fontFamilySettings)
    Column(
        Modifier.defaultMinSize(
            minHeight = with(density) {
                screenHeight.toDp()
            }
        )
    ) {
        if (settingState.isUsingContinuousScrolling) {
            val titleRegex = Regex("^(第[一二三四五六七八九十]+卷)\\s+(.*)")
            val matchResult = titleRegex.find(content.title)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (matchResult != null) {
                    val (volumeTitle, chapterTitle) = matchResult.destructured
                    Text(
                        text = volumeTitle,
                        textAlign = TextAlign.Center,
                        fontSize = (settingState.fontSize + 2).sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = fontFamily,
                        color = textColor,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        text = chapterTitle,
                        textAlign = TextAlign.Center,
                        fontSize = (settingState.fontSize + 6).sp,
                        lineHeight = (settingState.fontSize + settingState.fontLineHeight + 6).sp,
                        fontWeight = FontWeight((settingState.fontWeigh.toInt() + 100)),
                        fontFamily = fontFamily,
                        color = textColor
                    )
                } else {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        text = content.title,
                        textAlign = TextAlign.Center,
                        fontSize = (settingState.fontSize + 6).sp,
                        lineHeight = (settingState.fontSize + settingState.fontLineHeight + 6).sp,
                        fontWeight = FontWeight((settingState.fontWeigh.toInt() + 100)),
                        fontFamily = fontFamily,
                        color = textColor
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalDivider(
                        modifier = Modifier.width(48.dp),
                        color = textColor
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        val colors = LocalReaderStyle.current
        prepared.blocks.forEach { block ->
            val component = content.content[block.componentIndex]
            val text = block.text
            Box(Modifier.onPlaced {
                block.top = it.positionInParent().y.toInt()
            }) {
                CompositionLocalProvider(LocalReaderSpeechRanges provides speechRanges,
                    indi.renakoni.nextvol.ui.LocalReaderChapterId provides content.id) {
                    if (text != null) ScrollTextContent(text, readerContentTextColor(colors.textColor, colors.textDarkColor), modifier)
                    else component.Content(modifier)
                }
            }
        }
    }
}
