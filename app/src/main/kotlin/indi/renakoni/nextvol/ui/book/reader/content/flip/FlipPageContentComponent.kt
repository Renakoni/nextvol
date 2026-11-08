package indi.renakoni.nextvol.ui.book.reader.content.flip

import kotlinx.coroutines.flow.first

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.clearAndSetSemantics
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderRendererActive
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.ReaderSettings
import indi.renakoni.nextvol.ui.book.reader.animatePageTurns
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.resolveReaderBodyLayout
import indi.renakoni.nextvol.ui.book.reader.LocalReaderLayoutResult
import indi.renakoni.nextvol.ui.book.reader.ReaderLayoutResult
import indi.renakoni.nextvol.ui.book.reader.ReaderLayoutReason
import indi.renakoni.nextvol.ui.book.reader.content.componet.ImageComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.content.ReaderMode
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentError
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentLoading
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderPositionSession
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPosition
import indi.renakoni.nextvol.ui.book.reader.content.RegisterReaderPositionCapture
import indi.renakoni.nextvol.ui.book.reader.content.readerTapGestures
import indi.renakoni.nextvol.ui.book.reader.content.readerPageSwipe
import indi.renakoni.nextvol.ui.book.reader.content.readerBoundarySwipe
import indi.renakoni.nextvol.ui.book.reader.content.ReaderVolumeDirection
import indi.renakoni.nextvol.ui.book.reader.content.readerVolumeKeys
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechFollow
import indi.renakoni.nextvol.ui.book.reader.content.PrepareReaderSpeechIndex
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechRanges
import indi.renakoni.nextvol.ui.book.reader.content.readerSpeechManualScroll
import indi.renakoni.nextvol.ui.book.reader.ReaderBackgroundImageMode
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.ui.book.reader.rememberReaderBackgroundPainter
import indi.renakoni.nextvol.ui.book.reader.usesBackgroundImage
import indi.renakoni.nextvol.utils.showSnackbar
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkPosition
import indi.renakoni.nextvol.ui.book.reader.bookmark.RegisterBookmarkCapture
import indi.renakoni.nextvol.ui.book.reader.bookmark.anchorIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun FlipPageContentComponent(
    modifier: Modifier,
    uiState: FlipPageContentUiState,
    settingState: ReaderSettings,
    paddingValues: PaddingValues,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    chapterTitle: (String) -> String? = { null },
) {
    val positions = LocalReaderPositionSession.current
    val request = positions?.pending
    LaunchedEffect(uiState.readingChapterContent, request) {
        if (request != null && uiState.readingChapterContent?.isErr == true)
            positions.finish(uiState, request, null)
    }
    uiState.readingChapterContent?.onOk {
        SimpleFlipPageTextComponent(
            modifier = modifier,
            paddingValues = paddingValues,
            uiState = uiState,
            chapterContent = it,
            settingState = settingState,
            changeIsImmersive = changeIsImmersive,
            onClickNextChapter = onClickNextChapter,
            onClickPrevChapter = onClickPrevChapter,
        )
    }?.onErr {
        ChapterContentError(it, uiState.readingChapterId?.let(chapterTitle)) {
            uiState.readingChapterId?.let(uiState.changeChapter)
        }
    } ?: ChapterContentLoading()
}

private data class PreparedFlipChapter(
    val input: FlipPaginationInput,
    val pages: List<AbstractContentComponent<*>>,
)

@Composable
private fun SimpleFlipPageTextComponent(
    modifier: Modifier,
    paddingValues: PaddingValues,
    uiState: FlipPageContentUiState,
    chapterContent: ChapterContentUiState,
    settingState: ReaderSettings,
    changeIsImmersive: () -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val active by rememberUpdatedState(LocalReaderRendererActive.current)
    val speech by rememberUpdatedState(LocalReaderSpeechFollow.current)
    PrepareReaderSpeechIndex(chapterContent)
    val speechRanges = speech.ranges(chapterContent)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var preparedChapter by remember { mutableStateOf<PreparedFlipChapter?>(null) }
    var slippedContentComponentList by remember(chapterContent.id, chapterContent.content) {
        mutableStateOf(preparedChapter?.takeIf {
            it.input.chapterId == chapterContent.id && it.input.content === chapterContent.content
        }?.pages.orEmpty())
    }
    var readingAnchor by remember(chapterContent.id, chapterContent.content) { mutableStateOf<ReaderContentAnchor?>(null) }
    var anchoredPage by remember(chapterContent.id, chapterContent.content) { mutableStateOf<ReaderPage?>(null) }
    var renderedInput by remember { mutableStateOf<FlipPaginationInput?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val readerStyle = LocalReaderStyle.current
    val textLayout = LocalReaderTextLayout.current
    val textLocaleList = LocalTextLocaleList.current
    val layoutResult = resolveReaderBodyLayout(contentSize, paddingValues, ReaderMode.Flip,
        preference = settingState.pageLayout,
        supportsDoublePage = chapterContent.content.all { it is SimpleTextComponent || it is ImageComponent })
    val geometry = layoutResult.geometry
    val pagination = remember(scope) { FlipPaginationCoordinator(scope) }
    val adjacentPagination = remember(scope) { FlipPaginationCoordinator(scope) }
    val paginationInput = FlipPaginationInput(
        chapterId = chapterContent.id,
        content = chapterContent.content,
        geometry = geometry,
        density = density,
        layoutDirection = layoutDirection,
        fontSize = textLayout?.settings?.fontSize ?: readerStyle.fontSize,
        fontLineHeight = textLayout?.settings?.lineSpacing ?: readerStyle.fontLineHeight,
        fontWeight = textLayout?.settings?.fontWeight ?: readerStyle.fontWeight,
        fontFamilyUri = settingState.fontFamilyUri,
        textLocaleList = textLocaleList,
        textLayout = textLayout,
    )
    val layoutStatus = LocalReaderLayoutResult.current
    SideEffect {
        if (active) layoutStatus?.value = if (renderedInput == paginationInput) layoutResult
            else ReaderLayoutResult(null, ReaderLayoutReason.AwaitingMeasurement)
    }
    val bookmarks = LocalReaderBookmarks.current
    val positions = LocalReaderPositionSession.current
    val visibleLeaves = uiState.visibleLeafRange
    val visiblePage = visibleLeaves.firstOrNull()?.let { slippedContentComponentList.getOrNull(it) } as? ReaderPage
    val renderer = RegisterReaderPositionCapture(uiState) {
        val anchor = readingAnchor ?: visiblePage?.anchor
        if (anchor == null || visiblePage == null || renderedInput != paginationInput ||
            uiState.pagerState.isScrollInProgress || bookmarks?.pending != null ||
            uiState.readingChapterContent?.get() !== chapterContent) null
        else ReaderPosition.capture(uiState.bookId, chapterContent, anchor)
    }
    val positionRequest = positions?.pending
    var followedSpeechPosition by remember(uiState) { mutableStateOf(speech.position) }
    var positioningOwner by remember(uiState) { mutableStateOf<Any?>(null) }
    suspend fun positionPager(
        pager: PagerState, publishReading: Boolean, isCurrent: () -> Boolean, place: suspend () -> Unit,
    ): Boolean {
        if (!active) return false
        val attempt = Any().also { positioningOwner = it }
        var positioned = false
        uiState.onProgressRestoring(pager)
        try {
            place()
            positioned = active && positioningOwner === attempt && uiState.pagerState === pager && isCurrent() &&
                (positions == null || positions.ownsRenderer(uiState, renderer))
            return positioned
        } finally {
            if (active && positioningOwner === attempt && uiState.pagerState === pager &&
                (positions == null || positions.ownsRenderer(uiState, renderer))) {
                if (positioned && publishReading) uiState.updateSpeechPageState(pager)
                else uiState.updateAnchoredPageState(pager)
            }
        }
    }
    LaunchedEffect(active, positionRequest, renderedInput, slippedContentComponentList) {
        if (!active || positionRequest == null || renderedInput != paginationInput ||
            !positions.isCurrent(uiState, positionRequest)) return@LaunchedEffect
        val anchor = speech.anchor(chapterContent)
            ?: positionRequest.position.resolve(uiState.bookId, chapterContent)?.anchor
        val target = anchor?.let { source ->
            slippedContentComponentList.indexOfFirst { (it as? ReaderPage)?.contains(source) == true }
        } ?: -1
        if (target >= 0) {
            val pager = uiState.pagerState
            val positioned = positionPager(pager, false, { positions.isCurrent(uiState, positionRequest) }) {
                pager.scrollToPage(pager.readerLeaves.screenForLeaf(target))
            }
            if (!positioned) return@LaunchedEffect
            readingAnchor = anchor
            anchoredPage = slippedContentComponentList[target] as ReaderPage
        }
        positions.finish(uiState, positionRequest,
            anchor?.takeIf { target >= 0 }?.let { ReaderPosition.capture(uiState.bookId, chapterContent, it) })
    }
    RegisterBookmarkCapture {
        val anchor = readingAnchor ?: visiblePage?.anchor
        if (anchor == null || visiblePage == null || uiState.pagerState.isScrollInProgress ||
            uiState.readingChapterContent?.get() !== chapterContent || bookmarks?.pending != null) null
        else ReaderBookmarkPosition(uiState.bookId, chapterContent,
            if (chapterContent.content.getOrNull(anchor.componentIndex) is indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent)
                anchor else anchor.copy(offset = 0), uiState.readingProgress)
    }
    val bookmark = bookmarks?.pending
    LaunchedEffect(active, bookmark, chapterContent, slippedContentComponentList, uiState.pagerState) {
        if (!active || bookmark == null || bookmark.bookId != uiState.bookId || bookmark.chapterId != chapterContent.id ||
            slippedContentComponentList.isEmpty()) return@LaunchedEffect
        val anchor = withContext(Dispatchers.Default) { bookmark.anchorIn(chapterContent) }
        val target = anchor?.let { position -> slippedContentComponentList.indexOfFirst { (it as? ReaderPage)?.contains(position) == true } } ?: -1
        if (!active || bookmarks.pending !== bookmark || uiState.readingChapterContent?.get() !== chapterContent ||
            positions != null && !positions.ownsRenderer(uiState, renderer)) return@LaunchedEffect
        if (target >= 0) {
            val pager = uiState.pagerState
            // Explicit positions, like speech anchors, cancel any late percentage recovery.
            val positioned = positionPager(pager, true, { bookmarks.pending === bookmark }) {
                pager.scrollToPage(pager.readerLeaves.screenForLeaf(target))
            }
            if (!positioned) return@LaunchedEffect
            readingAnchor = anchor
            anchoredPage = slippedContentComponentList[target] as ReaderPage
            positions?.positioned(uiState, anchor?.let { ReaderPosition.capture(uiState.bookId, chapterContent, it) })
        }
        bookmarks.finish(bookmark, target >= 0)
    }
    val pending = uiState.pendingChapter
    val pendingContent = pending?.result?.get()
    val pendingInput = pendingContent?.let {
        paginationInput.copy(chapterId = it.id, content = it.content,
            geometry = resolveReaderBodyLayout(contentSize, paddingValues, ReaderMode.Flip,
                preference = settingState.pageLayout,
                supportsDoublePage = it.content.all { component -> component is SimpleTextComponent || component is ImageComponent }).geometry)
    }
    val isDragged by uiState.pagerState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pending, uiState.pagerState) {
        val pager = uiState.pagerState
        if (pending == null) return@LaunchedEffect
        snapshotFlow {
            val boundary = if (pending.entry == ChapterEntry.End) 0 else pager.pageCount - 1
            isDragged && (pager.currentPage + pager.currentPageOffsetFraction - boundary).absoluteValue > 0.001f
        }.collect { movedInward ->
            if (movedInward && uiState.pendingChapter === pending && uiState.pagerState === pager)
                uiState.cancelPendingChapter()
        }
    }
    SideEffect {
        pagination.syncInput(paginationInput)
        if (pendingInput != null) adjacentPagination.syncInput(pendingInput)
        else adjacentPagination.cancelPending()
        // A user page change establishes a new position; a reflow keeps the original character.
        if (visiblePage != null && visibleLeaves.none { slippedContentComponentList.getOrNull(it) === anchoredPage } && renderedInput == paginationInput &&
            positions?.pending == null && bookmarks?.pending == null && !uiState.pagerState.isScrollInProgress) {
            readingAnchor = visiblePage.anchor
            anchoredPage = visiblePage
        }
    }
    DisposableEffect(pagination) {
        onDispose { pagination.close(); adjacentPagination.close() }
    }
    LaunchedEffect(active, paginationInput) {
        if (!active) {
            pagination.cancelPending()
            adjacentPagination.cancelPending()
            return@LaunchedEffect
        }
        if (uiState.readingChapterContent?.get() !== chapterContent) return@LaunchedEffect
        val prepared = preparedChapter
        preparedChapter = null
        if (prepared?.input == paginationInput) {
            renderedInput = paginationInput
            return@LaunchedEffect
        }
        positions?.reflow(uiState)
        positioningOwner = Any()
        uiState.onProgressRestoring(uiState.pagerState)
        if (geometry == null) {
            pagination.cancelPending()
            return@LaunchedEffect
        }
        slippedContentComponentList = emptyList()
        uiState.updatePageState(PagerState { 0 })
        snapshotFlow { !speech.awaitingIndex(chapterContent) }.first { it }
        pagination.submit(paginationInput, chapterContent.content, geometry.leafSize.height, geometry.leafSize.width, onError = { error ->
            positions?.pending?.let { positions.finish(uiState, it, null) }
            throw error
        }) { result ->
            if (!active || uiState.readingChapterContent?.get() !== chapterContent) return@submit
            slippedContentComponentList = result
            renderedInput = paginationInput
            val speechAnchor = speech.anchor(chapterContent)
            val sessionAnchor = positions?.pending?.position?.resolve(uiState.bookId, chapterContent)?.anchor
            val anchor = speechAnchor ?: sessionAnchor ?: readingAnchor
            val target = if (anchor == null) -1 else result.indexOfFirst { (it as? ReaderPage)?.contains(anchor) == true }
            if (target >= 0) {
                readingAnchor = anchor
                anchoredPage = result[target] as ReaderPage
                val mapping = ReaderLeafMapping(result.size, geometry.columns)
                val pager = ReaderSpreadPagerState(mapping, mapping.screenForLeaf(target))
                uiState.updateAnchoredPageState(pager)
            } else {
                readingAnchor = null
                anchoredPage = null
                uiState.updatePageState(ReaderSpreadPagerState(ReaderLeafMapping(result.size, geometry.columns)))
            }
        }
    }
    val speechAnchor = speech.takeIf { it.active }?.anchor(chapterContent)
    val speechPage = speechAnchor?.let { anchor ->
        slippedContentComponentList.indexOfFirst { (it as? ReaderPage)?.contains(anchor) == true }
    } ?: -1
    LaunchedEffect(active, speechAnchor, speechPage, uiState.pagerState) {
        if (!active || speechPage < 0 || renderedInput != paginationInput ||
            positions != null && !positions.ownsRenderer(uiState, renderer)) return@LaunchedEffect
        val pager = uiState.pagerState
        val target = pager.readerLeaves.screenForLeaf(speechPage)
        if (target < 0) return@LaunchedEffect
        val advancesReading = followedSpeechPosition != speech.position
        val speechPosition = speech.position
        val positioned = positionPager(pager, advancesReading, { speech.position == speechPosition && speech.following }) {
            if (target != pager.currentPage) {
                if (settingState.animatePageTurns && (target - pager.currentPage).absoluteValue == 1)
                    pager.animateScrollToPage(target)
                else pager.scrollToPage(target)
            }
        }
        if (!positioned) return@LaunchedEffect
        followedSpeechPosition = speech.position
        readingAnchor = speechAnchor
        anchoredPage = slippedContentComponentList.getOrNull(speechPage) as? ReaderPage
        positions?.positioned(uiState, speechAnchor?.let { ReaderPosition.capture(uiState.bookId, chapterContent, it) })
    }
    val snackbarHostState = LocalSnackbarHost.current
    val retryLabel = stringResource(R.string.action_retry)
    val emptyChapterError = WebRequestError(stringResource(R.string.reader_chapter), stringResource(R.string.reader_empty_chapter))
    val paginationError = WebRequestError(stringResource(R.string.reader_chapter), stringResource(R.string.reader_pagination_failed))
    LaunchedEffect(active, pending, pendingInput) {
        if (!active || pending == null || pendingInput == null) return@LaunchedEffect
        val pendingGeometry = pendingInput.geometry
        if (pendingGeometry == null) {
            adjacentPagination.cancelPending()
            return@LaunchedEffect
        }
        adjacentPagination.submit(pendingInput, pendingInput.content, pendingGeometry.leafSize.height, pendingGeometry.leafSize.width,
            onError = { uiState.failPendingChapter(pending, paginationError) }) { pages ->
            if (!active || uiState.pendingChapter !== pending) return@submit
            if (pages.isEmpty()) {
                uiState.failPendingChapter(pending, emptyChapterError)
                return@submit
            }
            val mapping = ReaderLeafMapping(pages.size, pendingGeometry.columns)
            val firstPage = if (pending.entry == ChapterEntry.End) mapping.screenCount - 1 else 0
            val prepared = PreparedFlipChapter(pendingInput, pages)
            preparedChapter = prepared
            if (uiState.commitPendingChapter(pending, ReaderSpreadPagerState(mapping, firstPage))) {
                pagination.cancelPending()
            } else {
                preparedChapter = null
            }
        }
    }
    LaunchedEffect(pending) {
        pending?.result?.onErr { error ->
            if (snackbarHostState.showSnackbar(
                    message = "${error.title}: ${error.message}", actionLabel = retryLabel,
                    duration = SnackbarDuration.Indefinite,
                ) == SnackbarResult.ActionPerformed && uiState.pendingChapter === pending) {
                uiState.retryPendingChapter()
            }
        }
    }

    // 仅在启用背景图时才创建 painter：rememberReaderBackgroundPainter 会发起图片加载副作用，
    // 无条件调用会导致未开启背景时也去联网加载内置牛皮纸，失败时误报「加载失败」(见 issue #444)。
    val bgPainter = if (
        settingState.usesBackgroundImage &&
        settingState.backgroundImageDisplayMode == ReaderBackgroundImageMode.Loop
    ) {
        rememberReaderBackgroundPainter(settingState)
    } else null

    val pageWidthPx = contentSize.width.toFloat()
    val pageRangeDescription = if (visibleLeaves.isEmpty()) "" else stringResource(
        R.string.reader_visible_page_range, visibleLeaves.first + 1, visibleLeaves.last + 1, uiState.realLeafCount)
    val readerFirstPageText = stringResource(R.string.reader_first_page)
    val previousChapterText = stringResource(R.string.previous_chapter)
    val reachedStartText = stringResource(R.string.reader_reached_start)
    suspend fun lastPage(pagerState: PagerState) {
        if (!active || pagerState !== uiState.pagerState || geometry == null || renderedInput != paginationInput) return
        speech.onManualNavigation()
        if (pagerState.pageCount == 0 || slippedContentComponentList.isEmpty()) return
        if (uiState.pendingChapter?.entry == ChapterEntry.Start) uiState.cancelPendingChapter()
        if (pagerState.currentPage != 0) {
            uiState.cancelPendingChapter()
            if (settingState.animatePageTurns) {
                pagerState.animateScrollToPage(pagerState.currentPage - 1)
            } else {
                pagerState.scrollToPage(pagerState.currentPage - 1)
            }
        } else if (!chapterContent.hasPrevChapter()) {
            snackbarHostState.showSnackbar(reachedStartText)
        } else if (settingState.fastChapterChange) {
            uiState.loadPrevChapter.invoke()
        } else {
            showSnackbar(
                coroutineScope = scope,
                hostState = snackbarHostState,
                duration = SnackbarDuration.Short,
                message = readerFirstPageText,
                actionLabel = previousChapterText
            ) {
                if (it == SnackbarResult.ActionPerformed &&
                    active && uiState.readingChapterContent?.get() === chapterContent &&
                    uiState.pagerState === pagerState && !pagerState.isScrollInProgress &&
                    pagerState.currentPage == 0
                ) {
                    onClickPrevChapter()
                }
            }

        }
    }

    val readerLastPageText = stringResource(R.string.reader_last_page)
    val nextPageText = stringResource(R.string.next_chapter)
    val reachedEndText = stringResource(R.string.reader_reached_end)

    suspend fun nextPage(pagerState: PagerState) {
        if (!active || pagerState !== uiState.pagerState || geometry == null || renderedInput != paginationInput) return
        speech.onManualNavigation()
        if (pagerState.pageCount == 0 || slippedContentComponentList.isEmpty()) return
        if (uiState.pendingChapter?.entry == ChapterEntry.End) uiState.cancelPendingChapter()
        if (pagerState.currentPage + 1 < pagerState.pageCount) {
            uiState.cancelPendingChapter()
            if (settingState.animatePageTurns) {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            } else {
                pagerState.scrollToPage(pagerState.currentPage + 1)
            }
        } else if (!chapterContent.hasNextChapter()) {
            snackbarHostState.showSnackbar(reachedEndText)
        } else if (settingState.fastChapterChange) {
            uiState.loadNextChapter.invoke()
        } else {
            showSnackbar(
                coroutineScope = scope,
                hostState = snackbarHostState,
                duration = SnackbarDuration.Short,
                message = readerLastPageText,
                actionLabel = nextPageText
            ) {
                if (it == SnackbarResult.ActionPerformed &&
                    active && uiState.readingChapterContent?.get() === chapterContent &&
                    uiState.pagerState === pagerState && !pagerState.isScrollInProgress &&
                    pagerState.currentPage == pagerState.pageCount - 1
                ) {
                    onClickNextChapter()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
            .onSizeChanged { contentSize = it }
            .then(
                if (bgPainter != null)
                    Modifier.paint(
                        painter = bgPainter,
                        contentScale = ContentScale.Crop
                    )
                else Modifier
            )
    ) {
        // A replaced pager must attach its own first-layout callback before restoring position.
        if (geometry != null) key(uiState.pagerState) {
            HorizontalPager(
                state = uiState.pagerState,
                key = { it },
                userScrollEnabled = active && settingState.animatePageTurns,
                modifier = modifier
                    .semantics {
                        stateDescription = pageRangeDescription
                        fun turn(right: Boolean): Boolean {
                            if (!active || slippedContentComponentList.isEmpty()) return false
                            scope.launch {
                                if (right != (layoutDirection == LayoutDirection.Rtl)) nextPage(uiState.pagerState)
                                else lastPage(uiState.pagerState)
                            }
                            return true
                        }
                        pageLeft { turn(false) }
                        pageRight { turn(true) }
                        scrollBy { x, _ -> x != 0f && turn(x > 0f) }
                    }
                    .readerSpeechManualScroll()
                    .readerBoundarySwipe(uiState.pagerState, enabled = settingState.animatePageTurns &&
                        slippedContentComponentList.isNotEmpty()) { forward ->
                        scope.launch {
                            if (forward) nextPage(uiState.pagerState) else lastPage(uiState.pagerState)
                        }
                    }
                    .readerPageSwipe(enabled = !settingState.animatePageTurns, gestureKey = uiState.pagerState) { forward ->
                        scope.launch {
                            if (forward) nextPage(uiState.pagerState) else lastPage(uiState.pagerState)
                        }
                    }
                    .readerVolumeKeys(
                        enabled = settingState.isUsingVolumeKeyFlip && settingState.isUsingFlipPage &&
                            slippedContentComponentList.isNotEmpty(),
                        intervalSeconds = settingState.volumeKeyContinuousFlipInterval,
                    ) { direction ->
                        when (direction) {
                            ReaderVolumeDirection.Backward -> lastPage(uiState.pagerState)
                            ReaderVolumeDirection.Forward -> nextPage(uiState.pagerState)
                        }
                    }
                    .draggable(
                        enabled = active && settingState.isUsingFlipPage,
                        interactionSource = remember { MutableInteractionSource() },
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState {},
                        onDragStopped = {
                            if (active && it.absoluteValue > 60) changeIsImmersive.invoke()
                        }
                    )
                    .readerTapGestures { position ->
                        if (settingState.isUsingFlipPage && settingState.isUsingClickFlipPage)
                            when {
                                position.x < pageWidthPx / 3f -> scope.launch {
                                    if (layoutDirection == LayoutDirection.Rtl) nextPage(uiState.pagerState) else lastPage(uiState.pagerState)
                                }
                                position.x > pageWidthPx * 2f / 3f -> scope.launch {
                                    if (layoutDirection == LayoutDirection.Rtl) lastPage(uiState.pagerState) else nextPage(uiState.pagerState)
                                }
                                else -> changeIsImmersive.invoke()
                            }
                        else changeIsImmersive.invoke()
                    },
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (settingState.usesBackgroundImage && settingState.backgroundImageDisplayMode == ReaderBackgroundImageMode.Loop) {
                        Image(
                            modifier = Modifier.fillMaxSize(),
                            painter = rememberReaderBackgroundPainter(settingState),
                            contentDescription = null,
                            contentScale = ContentScale.Crop
                        )
                    }
                    CompositionLocalProvider(LocalReaderSpeechRanges provides speechRanges,
                        indi.renakoni.nextvol.ui.LocalReaderChapterId provides chapterContent.id) {
                        ReaderSpreadContent(slippedContentComponentList, uiState.pagerState.readerLeaves, it, geometry)
                    }
                }
            }
        }
        if (pending != null && pending.result?.isErr != true) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}
