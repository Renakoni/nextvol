package indi.renakoni.nextvol.ui.book.detail

import indi.renakoni.nextvol.data.book.availableVolumes
import android.net.Uri
import android.widget.Toast
import indi.renakoni.nextvol.utils.textToast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.valentinilk.shimmer.shimmer
import indi.renakoni.nextvol.ui.localbook.LocalBookMissingFile
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.get
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.ui.components.downloadStatusLabel
import indi.renakoni.nextvol.ui.components.downloadStatusText
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.LnrSnackbar
import indi.renakoni.nextvol.ui.components.Loading
import indi.renakoni.nextvol.ui.components.rememberSkeletonShimmer
import indi.renakoni.nextvol.ui.home.bookshelf.home.BookStatusIcon
import indi.renakoni.nextvol.ui.home.settings.textformatting.rules.navigateToSettingsTextFormattingRulesDestination
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.dateFormatter
import indi.renakoni.nextvol.utils.fadeInOnce
import indi.renakoni.nextvol.utils.fadingEdge
import indi.renakoni.nextvol.utils.isScrollingUp
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.nextUp
import kotlin.time.Duration.Companion.seconds

private enum class ChapterReadAction { Read, Unread }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    uiState: DetailUiState,
    onClickExportToEpub: (ExportSettings) -> Unit,
    onClickBackButton: () -> Unit,
    onClickChapter: (String) -> Unit,
    onClickRead: () -> Unit,
    cacheBook: (String) -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onClickCover: (Uri) -> Unit,
    onRetry: () -> Unit = {},
    localFileMissing: Boolean = false,
    onRelink: () -> Unit = {},
    onSetChaptersRead: suspend (Set<String>, Boolean) -> Unit = { _, _ -> },
    onRetryVolumes: () -> Unit = onRetry,
    blockingMenu: (@Composable (() -> Unit) -> Unit)? = null,
) {
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = LocalSnackbarHost.current
    val context = LocalContext.current
    val markReadStateFailedText by rememberUpdatedState(stringResource(R.string.mark_read_state_failed))

    val exportBottomSheetState = rememberBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val infoBottomSheetState = rememberBottomSheetState(initialValue = SheetValue.PartiallyExpanded)

    var showExportBottomSheet by remember { mutableStateOf(false) }
    var showInfoBottomSheet by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }

    val lazyListState = rememberLazyListState()
    var activeDirectoryListState by remember { mutableStateOf(lazyListState) }
    val selectionListState = rememberLazyListState()
    var readAction by rememberSaveable { mutableStateOf<ChapterReadAction?>(null) }
    val selectingChapters = readAction != null
    val actionText = if (readAction == ChapterReadAction.Read) R.string.mark_read_action else R.string.mark_unread_action
    var selectedChapterIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmReadState by remember { mutableStateOf(false) }
    var savingReadState by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val catalogIds = uiState.bookVolumes?.availableVolumes()?.volumes.orEmpty()
        .flatMap { it.chapters }.mapTo(mutableSetOf()) { it.id }
    val selectedIds = selectedChapterIds.toSet().intersect(catalogIds)
    val exitSelection = {
        readAction = null
        selectedChapterIds = emptyList()
        confirmReadState = false
    }
    val enterSelection: (ChapterReadAction) -> Unit = { action ->
        selectedChapterIds = emptyList()
        readAction = action
    }
    BackHandler(selectingChapters) { if (!savingReadState) exitSelection() }
    val volumesEmpty = uiState.bookVolumes?.get()?.volumes?.any { it.chapters.isNotEmpty() } != true

    val isCollapsed by remember {
        derivedStateOf {
            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) false
            else visibleItems.none { it.index == 0 }
        }
    }

    val claim = LocalClaimSnackbarHost.current

    DisposableEffect(Unit) {
        claim(true)
        onDispose { claim(false) }
    }

    val scrollingUp by activeDirectoryListState.isScrollingUp()
    val fabVisible by remember(uiState.bookVolumes, activeDirectoryListState) {
        derivedStateOf {
            val hasVolumes = uiState.bookVolumes?.availableVolumes()?.volumes?.any { it.chapters.isNotEmpty() } == true
            val allowByDirection = !activeDirectoryListState.isScrollInProgress || scrollingUp
            val canGoForward = activeDirectoryListState.canScrollForward

            uiState.readingAvailable && hasVolumes && uiState.userReadingData != null && canGoForward && allowByDirection
        }
    }

    val isStartReading = uiState.userReadingData?.lastReadChapterId == null
    val fabTextRes = if (isStartReading) R.string.start_reading else R.string.continue_reading

    val fabContent = remember {
        movableContentOf<Boolean, Int, () -> Unit> { visible, textRes, onClick ->
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                ) + fadeIn(tween(200, easing = FastOutSlowInEasing)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 4 },
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(150, easing = FastOutSlowInEasing))
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier.padding(end = 28.dp, bottom = 28.dp),
                    onClick = onClick,
                    icon = { Icon(painterResource(R.drawable.filled_menu_book_24px), null) },
                    text = { Text(stringResource(textRes)) }
                )
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                val targetBottomPad = if (fabVisible) 90.dp else 32.dp
                val snackbarBottomPad by animateDpAsState(
                    targetValue = targetBottomPad,
                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                    label = "snackbarBottomPad"
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = snackbarBottomPad)
                ) { data ->
                    LnrSnackbar(data)
                }

                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    fabContent(fabVisible && !selectingChapters, fabTextRes, onClickRead)
                }
            }
        },
        bottomBar = {
            if (selectingChapters) Surface(color = colorScheme.surfaceContainerLow) {
                Button(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    enabled = selectedIds.isNotEmpty() && !savingReadState,
                    onClick = { confirmReadState = true },
                ) {
                    Text(stringResource(if (savingReadState) R.string.processing else actionText))
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectingChapters) TopAppBar(
                title = { Text(stringResource(R.string.chapters_selected, selectedIds.size),
                    style = typography.displayLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = exitSelection, enabled = !savingReadState) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.cancel))
                    }
                },
                actions = {
                    TextButton(enabled = !savingReadState && catalogIds.isNotEmpty(), onClick = {
                        selectedChapterIds = if (selectedIds == catalogIds) emptyList() else catalogIds.toList()
                    }) {
                        Text(stringResource(if (selectedIds == catalogIds) R.string.deselect_all else R.string.select_all))
                    }
                },
            ) else TopBar(
                title = uiState.bookInformation?.map { it.title }?.getOrElse { "" } ?: "",
                volumesEmpty = volumesEmpty,
                readingAvailable = uiState.readingAvailable,
                onClickBackButton = onClickBackButton,
                onClickExport = { showExportBottomSheet = true },
                onClickTextFormatting = {
                    uiState.bookInformation?.onOk {
                        navController.navigateToSettingsTextFormattingRulesDestination(
                            it.id
                        )
                    }?.onErr {
                        textToast(context, it.message, Toast.LENGTH_SHORT).show()
                    }
                },
                onClickMarkAsRead = { enterSelection(ChapterReadAction.Read) },
                onClickMarkAsUnread = { enterSelection(ChapterReadAction.Unread) },
                canMarkChapters = catalogIds.isNotEmpty() && uiState.userReadingData != null,
                blockingMenu = blockingMenu,
                scrollBehavior = scrollBehavior,
                isCollapsed = isCollapsed
            )

            Crossfade(
                targetState = uiState.bookInformation,
                animationSpec = tween(300),
                label = "DetailScreenCrossfade"
            ) { result ->
                result?.onOk {
                    DetailContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.surface),
                        uiState = uiState,
                        localFileMissing = localFileMissing,
                        onRelink = onRelink,
                        bookInformation = it,
                        onClickChapter = { id ->
                            if (selectingChapters) {
                                if (!savingReadState) selectedChapterIds =
                                    if (id in selectedChapterIds) selectedChapterIds - id else selectedChapterIds + id
                            } else onClickChapter(id)
                        },
                        lazyListState = if (selectingChapters) selectionListState else lazyListState,
                        onActiveListStateChange = { activeDirectoryListState = it },
                        selectingChapters = selectingChapters,
                        selectedChapterIds = selectedIds,
                        selectionEnabled = !savingReadState,
                        cacheBook = cacheBook,
                        requestAddBookToBookshelf = requestAddBookToBookshelf,
                        onClickTag = onClickTag,
                        onClickCover = onClickCover,
                        onClickShowInfo = { showInfoBottomSheet = true },
                        onRetryVolumes = onRetryVolumes,
                    )
                }?.onErr {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (localFileMissing) LocalBookMissingFile(onRelink)
                        Text(it.title, style = typography.titleMedium)
                        Text(it.message, style = typography.bodyMedium)
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.discovery_retry)) }
                    }
                } ?: DetailContentSkeleton(
                        Modifier
                            .fillMaxSize()
                            .background(colorScheme.surface)
                    )
            }
        }


        if (showExportBottomSheet && uiState.readingAvailable) {
            uiState.bookVolumes?.onOk { bookVolumes ->
                ExportBottomSheet(
                    sheetState = exportBottomSheetState,
                    bookVolumes = bookVolumes,
                    settings = exportSettings,
                    onSettingsChange = { exportSettings = it },
                    onDismissRequest = { showExportBottomSheet = false },
                    onClickExport = onClickExportToEpub
                )
            }
        }
        AnimatedVisibility(visible = showInfoBottomSheet) {
            uiState.bookInformation?.onOk { bookInformation ->
                BookInfoBottomSheet(
                    bookInformation = bookInformation,
                    bookVolumes = uiState.bookVolumes?.get(),
                    sheetState = infoBottomSheetState,
                    onDismissRequest = { showInfoBottomSheet = false }
                )
            }
        }
    }
    if (confirmReadState) AlertDialog(
        onDismissRequest = { if (!savingReadState) confirmReadState = false },
        title = { Text(stringResource(actionText)) },
        text = { Text(stringResource(if (readAction == ChapterReadAction.Read) R.string.mark_read_confirmation
            else R.string.mark_unread_confirmation, selectedIds.size)) },
        confirmButton = {
            TextButton(enabled = !savingReadState && selectedIds.isNotEmpty(), onClick = {
                if (savingReadState) return@TextButton
                savingReadState = true
                val read = readAction == ChapterReadAction.Read
                coroutineScope.launch {
                    try {
                        onSetChaptersRead(selectedIds, read)
                        exitSelection()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.e("DetailScreen", "Could not update chapter read state", error)
                        confirmReadState = false
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(markReadStateFailedText)
                        }
                    } finally {
                        savingReadState = false
                    }
                }
            }) { Text(stringResource(if (savingReadState) R.string.processing else R.string.confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !savingReadState, onClick = { confirmReadState = false }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}


@Composable
private fun DetailContentSkeleton(modifier: Modifier = Modifier) {
    val rounded = RoundedCornerShape(6.dp)
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(0.5.seconds)
        started = true
    }

    val baseColor = colorScheme.surfaceContainerLow
    val highlightColor = colorScheme.surfaceContainerHigh

    val shimmer = rememberSkeletonShimmer(
        baseColor, highlightColor
    )

    Column(
        modifier = modifier
            .then(if (started) Modifier.shimmer(shimmer) else Modifier),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(188.dp)
                .padding(horizontal = itemHorizontalPadding, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 122.dp, height = 178.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(baseColor)
            )
            Column(
                modifier = Modifier.padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(20.dp)
                            .clip(rounded)
                            .background(baseColor)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(baseColor)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(90.dp)
                        .padding(vertical = itemVerticalPadding)
                        .clip(RoundedCornerShape(8.dp))
                        .background(baseColor)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(24.dp)
                    .clip(rounded)
                    .background(baseColor)
            )
            Spacer(Modifier.height(10.dp))
            repeat(4) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(rounded)
                        .background(baseColor)
                )
            }
        }
    }
}


private val itemHorizontalPadding = 18.dp
private val itemVerticalPadding = 8.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    modifier: Modifier = Modifier,
    uiState: DetailUiState,
    bookInformation: BookInformation,
    lazyListState: LazyListState,
    onActiveListStateChange: (LazyListState) -> Unit,
    onClickChapter: (String) -> Unit,
    cacheBook: (String) -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onClickCover: (Uri) -> Unit,
    onClickShowInfo: () -> Unit,
    selectingChapters: Boolean,
    selectedChapterIds: Set<String>,
    selectionEnabled: Boolean,
    onRetryVolumes: () -> Unit,
    localFileMissing: Boolean,
    onRelink: () -> Unit,
) {
    val bookVolumes = uiState.bookVolumes?.availableVolumes()
    val progress = uiState.userReadingData?.maxChapterReadingProgressMap.orEmpty()
    val currentChapterId = uiState.userReadingData?.lastReadChapterId
    val directory = rememberDetailDirectoryState(bookInformation.id)
    val data = directory.data(bookVolumes, progress, currentChapterId, selectingChapters)
    val focusManager = LocalFocusManager.current
    val searching = directory.searching
    val results = data.results
    val searchListState = rememberLazyListState()
    val activeListState = if (searching) searchListState else lazyListState
    LaunchedEffect(activeListState) { onActiveListStateChange(activeListState) }
    val closeSearch = {
        directory.closeSearch()
        focusManager.clearFocus()
    }
    BackHandler(directory.searchExpanded, onBack = closeSearch)
    val deferred = 6
    val framesPerStep = 2
    var visible by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (visible < deferred) {
            repeat(framesPerStep) { withFrameNanos { } }
            visible += 1
        }
    }

    LaunchedEffect(directory.scrollToDirectory, directory.chapterToLocate, data.page, data.directoryItemKeys, searching, data.matches, visible) {
        if (!directory.scrollToDirectory || visible < deferred || (searching && data.matches == null)) return@LaunchedEffect
        val headerIndex = if (selectingChapters || searching) 0 else 4
        val chapterKey = data.chapters.firstOrNull { it.chapter.id == directory.chapterToLocate }?.key
        val chapterIndex = chapterKey?.let(data.directoryItemKeys::indexOf) ?: -1
        val errorItemCount = if (uiState.bookVolumes?.isErr == true) 1 else 0
        activeListState.scrollToItem(if (chapterIndex >= 0 && !searching) headerIndex + 1 + errorItemCount + chapterIndex else headerIndex)
        directory.consumeScrollRequest()
    }

    LazyColumn(
        state = activeListState,
        modifier = modifier
    ) {
        if (visible >= 1 && !selectingChapters && !searching) item {
            BookCardBlock(
                bookInformation = bookInformation,
                showReadingMetadata = uiState.readingAvailable,
                modifier = Modifier
                    .fadeInOnce("book")
                    .graphicsLayer {
                        translationY = lazyListState.firstVisibleItemScrollOffset * 0.5f
                    }
                    .fillMaxWidth(),
                onClickCover = onClickCover
            )
        }

        if (visible >= 2 && !selectingChapters && !searching) item {
            TagsBlock(
                modifier = Modifier.fadeInOnce("tags"),
                bookInformation = bookInformation,
                onClickTag = onClickTag
            )
        }

        if (visible >= 3 && !selectingChapters && !searching) item {
            QuickOperationsBlock(
                modifier = Modifier.fadeInOnce("op"),
                isInBookshelf = uiState.isInBookshelf,
                downloadState = uiState.downloadState,
                canCache = uiState.canCache,
                downloadItem = uiState.downloadItem,
                onClickAddToBookShelf = { requestAddBookToBookshelf(bookInformation.id) },
                onClickCache = { cacheBook(bookInformation.id) },
                onClickShowInfo = onClickShowInfo
            )
            if (uiState.downloadState.content.phase != BookDownloadPhase.None) {
                Text(downloadStatusText(uiState.downloadState),
                    Modifier.padding(horizontal = itemHorizontalPadding, vertical = 4.dp),
                    style = typography.bodySmall, color = colorScheme.onSurfaceVariant)
            }
        }

        if (visible >= 4 && !selectingChapters && !searching) item {
            IntroBlock(
                modifier = Modifier.fadeInOnce("intro"),
                description = bookInformation.description
            )
        }

        if (visible >= 5 && !uiState.readingAvailable) item {
            if (localFileMissing) LocalBookMissingFile(onRelink,
                Modifier.padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding))
            else Text(stringResource(if (uiState.metadataOnly) R.string.source_metadata_only else R.string.source_reading_unavailable),
                Modifier.padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
                style = typography.bodyMedium, color = colorScheme.onSurfaceVariant)
        }
        if (visible >= 5 && uiState.readingAvailable) item(key = "directory-toolbar") {
            DirectoryToolbar(
                chapterCount = data.allChapters.size, page = data.page, onPageChange = directory::changePage,
                descending = directory.descending, hideRead = directory.hideReadChapters, selecting = selectingChapters,
                canLocate = data.readingIndex >= 0, searchExpanded = directory.searchExpanded, query = directory.query,
                matchCount = if (searching) results?.size else null,
                onToggleOrder = directory::toggleOrder,
                onToggleHideRead = directory::toggleHideRead,
                onLocate = { directory.locateCurrentChapter(data) { focusManager.clearFocus() } },
                onToggleSearch = { if (directory.searchExpanded) closeSearch() else directory.openSearch() },
                onQueryChange = directory::changeQuery,
            )
        }

        if (visible >= 6 && uiState.readingAvailable) {
            uiState.bookVolumes?.onErr { error ->
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (uiState.bookVolumes?.availableVolumes() != null) stringResource(R.string.book_directory_incomplete)
                            else error.title, style = typography.titleMedium)
                        Text(error.message, style = typography.bodyMedium)
                        TextButton(onClick = onRetryVolumes) { Text(stringResource(R.string.discovery_retry)) }
                    }
                }
            }
            bookVolumes?.let {
                if (searching) {
                    when {
                        results == null -> item { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Loading() } }
                        results.isEmpty() -> item {
                            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.reader_directory_no_results), style = typography.titleMedium)
                                Text(stringResource(R.string.reader_directory_no_results_hint), style = typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                            }
                        }
                        else -> items(results, key = { it.key }, contentType = { "chapter" }) { result ->
                            ChapterItem(
                                chapter = result.chapter, isRead = result.chapter.id in data.readIds,
                                isLastRead = result.chapter.id == currentChapterId, readingProgress = progress[result.chapter.id] ?: 0f,
                                onClick = { focusManager.clearFocus(); onClickChapter(result.chapter.id) },
                                selecting = selectingChapters, selected = result.chapter.id in selectedChapterIds,
                                selectionEnabled = selectionEnabled, volumeTitle = result.volumeTitle,
                            )
                        }
                    }
                } else if (data.allChapters.isNotEmpty()) {
                    data.pageVolumes.forEach { (volumeId, entries) ->
                        val volume = data.volumesById.getValue(volumeId)
                        if (volume.volumeTitle.isNotBlank()) item(key = "volume:$volumeId", contentType = "volume") {
                            val first = data.chapterOrdinals.getValue(entries.first().key)
                            val last = data.chapterOrdinals.getValue(entries.last().key)
                            DirectoryVolumeHeader(
                                volumeId = volumeId, title = volume.volumeTitle,
                                readCount = data.readCounts.getValue(volumeId), chapterCount = volume.chapters.size,
                                pageRange = if (entries.size < volume.chapters.size)
                                    stringResource(R.string.detail_directory_volume_page_range, minOf(first, last), maxOf(first, last)) else null,
                                expanded = directory.isVolumeExpanded(volumeId, data),
                                onToggle = { directory.toggleVolume(volumeId, data) },
                            )
                        }
                        entries.forEach { entry ->
                            if (entry.key in data.visibleChapterKeys) item(key = entry.key, contentType = "chapter") {
                                val chapter = entry.chapter
                                ChapterItem(
                                    chapter = chapter, isRead = chapter.id in data.readIds, isLastRead = chapter.id == currentChapterId,
                                    readingProgress = progress[chapter.id] ?: 0f, onClick = { onClickChapter(chapter.id) },
                                    selecting = selectingChapters, selected = chapter.id in selectedChapterIds, selectionEnabled = selectionEnabled,
                                )
                            }
                        }
                    }
                    if (data.chapters.isEmpty() && data.hideRead) item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.detail_directory_filtered_empty), style = typography.bodyMedium, color = colorScheme.onSurfaceVariant)
                            TextButton(onClick = directory::showReadChapters) { Text(stringResource(R.string.detail_directory_show_all)) }
                        }
                    }
                    if (data.pageCount > 1) item(key = "directory-pagination") {
                        Box(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                            DirectoryPageNavigation(data.allChapters.size, data.page, directory.descending, directory::changePage)
                        }
                    }
                } else item {
                    Text(
                        stringResource(R.string.info_volume_chapters_count, 0),
                        Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        style = typography.bodyMedium, color = colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (uiState.bookVolumes == null) item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Loading()
                }
            }
        }

        item {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    volumesEmpty: Boolean,
    readingAvailable: Boolean,
    onClickBackButton: () -> Unit,
    onClickExport: () -> Unit,
    onClickTextFormatting: () -> Unit,
    onClickMarkAsRead: () -> Unit,
    onClickMarkAsUnread: () -> Unit,
    canMarkChapters: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    isCollapsed: Boolean,
    blockingMenu: (@Composable (() -> Unit) -> Unit)?,
) {
    val titleProgress by animateFloatAsState(
        targetValue = if (isCollapsed) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "titleProgress"
    )

    Box {
        TopAppBar(
            title = {
                val offset = 16.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = stringResource(R.string.detail_title),
                        maxLines = 1,
                        style = typography.displayLarge,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (-offset * titleProgress).toPx().toInt()
                                )
                            }
                            .graphicsLayer { alpha = 1f - titleProgress }
                    )
                    Text(
                        text = title,
                        maxLines = 1,
                        style = typography.displayLarge,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .offset {
                                IntOffset(
                                    x = 0,
                                    y = (offset * (1f - titleProgress)).toPx().toInt()
                                )
                            }
                            .graphicsLayer { alpha = titleProgress }
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onClickBackButton) {
                    Icon(painterResource(id = R.drawable.arrow_back_24px), contentDescription = stringResource(R.string.sources_back))
                }
            },
            actions = {
                if (readingAvailable || blockingMenu != null) TopBarActions(
                    volumesEmpty = volumesEmpty || !readingAvailable,
                    onClickExport = onClickExport,
                    onClickTextFormatting = onClickTextFormatting,
                    onClickMarkAsRead = onClickMarkAsRead,
                    onClickMarkAsUnread = onClickMarkAsUnread,
                    canMarkChapters = canMarkChapters,
                    blockingMenu = blockingMenu,
                )
            },
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
private fun TopBarActions(
    volumesEmpty: Boolean,
    onClickExport: () -> Unit,
    onClickTextFormatting: () -> Unit,
    onClickMarkAsRead: () -> Unit,
    onClickMarkAsUnread: () -> Unit,
    canMarkChapters: Boolean,
    blockingMenu: (@Composable (() -> Unit) -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    IconButton(enabled = !volumesEmpty, onClick = onClickExport) {
        Icon(painterResource(id = R.drawable.file_export_24px), contentDescription = stringResource(R.string.export))
    }
    IconButton(enabled = !volumesEmpty, onClick = onClickTextFormatting) {
        Icon(painterResource(id = R.drawable.find_replace_24px), contentDescription = stringResource(R.string.settings_text_formatting))
    }
    Box {
        IconButton(enabled = !volumesEmpty || blockingMenu != null, onClick = { menuExpanded = true }) {
            Icon(painterResource(id = R.drawable.more_vert_24px), contentDescription = stringResource(R.string.action_more_options))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mark_as_unread), style = typography.bodyLarge) },
                enabled = !volumesEmpty && canMarkChapters,
                onClick = {
                    menuExpanded = false
                    onClickMarkAsUnread()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mark_as_read), style = typography.bodyLarge) },
                enabled = !volumesEmpty && canMarkChapters,
                onClick = {
                    menuExpanded = false
                    onClickMarkAsRead()
                }
            )
            if (blockingMenu != null) {
                HorizontalDivider()
                blockingMenu { menuExpanded = false }
            }
        }
    }
}


@Composable
private fun BookCardBlock(
    bookInformation: BookInformation,
    showReadingMetadata: Boolean,
    modifier: Modifier,
    onClickCover: (Uri) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var coverFallback by remember(bookInformation.id, bookInformation.coverUri) {
        mutableStateOf(bookInformation.coverUri.toString().isBlank())
    }
    val updateText = if (bookInformation.isComplete) {
        stringResource(R.string.book_completed)
    } else if (bookInformation.lastUpdated.year > 1970) {
        stringResource(
            R.string.book_info_update_date,
            bookInformation.lastUpdated.format(dateFormatter())
        )
    } else null
    val wordCountText = if (bookInformation.wordCount.count > 0) bookInformation.wordCount.get() else null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(188.dp)
            .padding(horizontal = itemHorizontalPadding, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .clickable(
                    onClick = {
                        if (coverFallback) {
                            coroutineScope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    DefaultBookCoverRenderer.cacheUri(
                                        context,
                                        bookInformation.title,
                                        bookInformation.id,
                                        bookInformation.author
                                    )
                                }
                                onClickCover(uri)
                            }
                        } else {
                            onClickCover(bookInformation.coverUri)
                        }
                    }
                )
        ) {
            Cover(
                bookId = bookInformation.id,
                height = 178.dp,
                width = 122.dp,
                uri = bookInformation.coverUri,
                title = bookInformation.title,
                author = bookInformation.author,
                rounded = 8.dp,
                onFallbackChanged = { coverFallback = it }
            )
        }
        Column(
            modifier = Modifier
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = bookInformation.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.W600,
                style = typography.displayMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            if (bookInformation.subtitle.isNotEmpty()) {
                Text(
                    text = bookInformation.subtitle,
                    maxLines = 2,
                    color = colorScheme.secondary,
                    style = typography.bodyMedium
                )
            }
            Text(
                text = bookInformation.author,
                maxLines = 1,
                fontWeight = FontWeight.W600,
                color = colorScheme.primary,
                style = typography.bodyLarge
            )
            if (showReadingMetadata) Column {
                if (updateText != null) InfoRow(
                    icon = { BookStatusIcon(bookInformation.isComplete) },
                    text = updateText
                )
                if (updateText != null && wordCountText != null) Spacer(Modifier.height(2.dp))
                if (wordCountText != null) InfoRow(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.text_snippet_24px),
                            contentDescription = null,
                            tint = colorScheme.outline,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                    },
                    text = wordCountText
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Text(
            text = text,
            maxLines = 1,
            style = typography.labelMedium,
            color = colorScheme.secondary
        )
    }
}


@Composable
private fun TagsBlock(
    modifier: Modifier,
    bookInformation: BookInformation,
    onClickTag: (String) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = itemVerticalPadding, horizontal = itemHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (bookInformation.publishingHouse.isNotEmpty()) {
            SuggestionChip(
                label = { Text(bookInformation.publishingHouse) },
                onClick = {}
            )
        }

        bookInformation.tags.forEach { tag ->
            SuggestionChip(
                label = { Text(tag) },
                onClick = { onClickTag(tag) }
            )
        }
    }
}


@Composable
fun QuickOperationButton(
    icon: Painter,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    Button(
        contentPadding = PaddingValues(12.dp),
        modifier = modifier
            .heightIn(min = 88.dp)
            .fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorScheme.surfaceContainerLow,
            contentColor = colorScheme.primary,
        ),
        shape = RoundedCornerShape(0.dp),
        onClick = onClick,
        enabled = enabled,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                modifier = Modifier.size(18.dp),
                painter = icon,
                contentDescription = null,
            )
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.let {
                Text(it, style = typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun QuickOperationsBlock(
    modifier: Modifier,
    isInBookshelf: Boolean,
    downloadState: BookDownloadStatus,
    canCache: Boolean,
    downloadItem: DownloadItem?,
    onClickAddToBookShelf: () -> Unit,
    onClickCache: () -> Unit,
    onClickShowInfo: () -> Unit
) {
    val bookmark = painterResource(R.drawable.bookmark_add_24px)
    val filledBookmark = painterResource(R.drawable.filled_bookmark_add_24px)
    val cloud = painterResource(R.drawable.cloud_download_24px)
    val filledCloud = painterResource(R.drawable.filled_cloud_download_24px)
    val info = painterResource(R.drawable.info_24px)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding)
            .clip(RoundedCornerShape(16.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isInBookshelf) {
            QuickOperationButton(
                icon = filledBookmark,
                title = stringResource(R.string.activity_collections),
                onClick = onClickAddToBookShelf,
                modifier = Modifier.weight(1f)
            )
        } else {
            QuickOperationButton(
                icon = bookmark,
                title = stringResource(R.string.add_to_bookshelf),
                onClick = onClickAddToBookShelf,
                modifier = Modifier.weight(1f)
            )
        }

        val phase = downloadState.displayPhase
        if (canCache || phase != BookDownloadPhase.None) {
            // The status alone; tapping opens the download page. Only a running download adds its progress.
            QuickOperationButton(
                icon = if (phase == BookDownloadPhase.Complete) filledCloud else cloud,
                title = downloadStatusLabel(downloadState),
                supportingText = if (phase == BookDownloadPhase.Updating)
                    downloadItem?.progress?.takeIf { it >= 0f && it < 1f }?.let { "${(it * 100).toInt()}%" } else null,
                enabled = canCache && phase != BookDownloadPhase.Updating,
                onClick = onClickCache,
                modifier = Modifier.weight(1f),
            )
        }

        QuickOperationButton(
            icon = info,
            title = stringResource(R.string.action_show_info),
            onClick = onClickShowInfo,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun IntroBlock(
    modifier: Modifier,
    description: String
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val overflowed = remember(description) { description.length > 220 }

    val fadingBrush = remember {
        Brush.verticalGradient(
            0.7f to Color.White,
            1f to Color.Transparent
        )
    }
    val whiteBrush = remember {
        Brush.verticalGradient(listOf(Color.White, Color.White))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = itemHorizontalPadding, vertical = itemVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            modifier = Modifier.padding(vertical = 16.dp),
            text = stringResource(R.string.detail_introduction),
            style = typography.displayMedium,
            fontWeight = FontWeight.W600
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .fadingEdge(
                    if (!expanded && overflowed) fadingBrush else whiteBrush
                )
        ) {
            Text(
                text = description,
                style = typography.bodyLarge,
                maxLines = if (!expanded && overflowed) 4 else Int.MAX_VALUE,
                color = colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (overflowed) {
            val rotation by animateFloatAsState(if (expanded) 0f else 180f)
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = { expanded = !expanded },
                colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent)
            ) {
                Icon(
                    painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                    tint = colorScheme.primary
                )
                Text(
                    text = if (expanded)
                        stringResource(R.string.collapse)
                    else
                        stringResource(R.string.expand),
                    color = colorScheme.primary
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: ChapterInformation,
    isRead: Boolean,
    isLastRead: Boolean,
    readingProgress: Float,
    onClick: () -> Unit,
    selecting: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    volumeTitle: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(when {
                selected -> colorScheme.secondaryContainer
                isLastRead -> colorScheme.primaryContainer.copy(alpha = .35f)
                else -> Color.Transparent
            })
            .then(if (selecting) Modifier.toggleable(
                value = selected, enabled = selectionEnabled, role = Role.Checkbox,
                onValueChange = { onClick() },
            ) else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) Checkbox(
                checked = selected, onCheckedChange = null, enabled = selectionEnabled,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = chapter.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.titleMedium,
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.W600,
                    color = if (isRead) colorScheme.secondary
                    else colorScheme.onSurface
                )
                if (volumeTitle != null) Text(volumeTitle, style = typography.labelSmall,
                    color = colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isLastRead) {
                Icon(
                    modifier = Modifier
                        .padding(start = 22.dp)
                        .size(24.dp),
                    painter = painterResource(R.drawable.target_24px),
                    tint = colorScheme.primary,
                    contentDescription = stringResource(R.string.last_read)
                )
            } else if (readingProgress > 0f) {
                // Correct Float rounding at integer percentages without reporting completion early.
                val percent = (readingProgress.coerceIn(0f, 1f) * 100).nextUp().toInt()
                    .coerceAtMost(if (isRead) 100 else 99)
                Text(
                    modifier = Modifier.padding(start = 22.dp),
                    text = "$percent%",
                    maxLines = 1,
                    style = typography.titleSmall,
                    fontWeight = FontWeight.Normal,
                    color = if (isRead) colorScheme.secondary else colorScheme.primary
                )
            }
        }
    }
}
