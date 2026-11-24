package indi.renakoni.nextvol.ui.book.detail

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.valentinilk.shimmer.shimmer
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.availableVolumes
import indi.renakoni.nextvol.data.book.get
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.LnrSnackbar
import indi.renakoni.nextvol.ui.components.Loading
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.GlassVariant
import indi.renakoni.nextvol.ui.components.design.LiquidGlassSurface
import indi.renakoni.nextvol.ui.components.downloadStatusLabel
import indi.renakoni.nextvol.ui.components.downloadStatusText
import indi.renakoni.nextvol.ui.components.rememberSkeletonShimmer
import indi.renakoni.nextvol.ui.home.bookshelf.home.BookStatusIcon
import indi.renakoni.nextvol.ui.home.settings.textformatting.rules.navigateToSettingsTextFormattingRulesDestination
import indi.renakoni.nextvol.ui.localbook.LocalBookMissingFile
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.dateFormatter
import indi.renakoni.nextvol.utils.fadingEdge
import indi.renakoni.nextvol.utils.navigationBarSpacer
import indi.renakoni.nextvol.utils.textToast
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
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
    val tokens = NextVolDesign.tokens
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = LocalSnackbarHost.current
    val context = LocalContext.current
    val markReadStateFailedText by rememberUpdatedState(stringResource(R.string.mark_read_state_failed))
    val contentHazeState = rememberHazeState()

    var showExportBottomSheet by remember { mutableStateOf(false) }
    var showInfoBottomSheet by remember { mutableStateOf(false) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }

    val lazyListState = rememberLazyListState()
    val selectionListState = rememberLazyListState()
    var readAction by rememberSaveable { mutableStateOf<ChapterReadAction?>(null) }
    val selectingChapters = readAction != null
    val actionText = if (readAction == ChapterReadAction.Read) R.string.mark_read_action else R.string.mark_unread_action
    var selectedChapterIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirmReadState by remember { mutableStateOf(false) }
    var savingReadState by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val catalogIds = remember(uiState.bookVolumes) {
        uiState.bookVolumes?.availableVolumes()?.volumes.orEmpty()
            .flatMap { it.chapters }.mapTo(mutableSetOf()) { it.id }
    }
    val selectedIds = selectedChapterIds.intersect(catalogIds)
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
    val hasAvailableVolumes = remember(uiState.bookVolumes) {
        uiState.bookVolumes?.availableVolumes()?.volumes?.any { it.chapters.isNotEmpty() } == true
    }
    val volumesEmpty = uiState.bookVolumes?.get()?.volumes?.any { it.chapters.isNotEmpty() } != true

    val isCollapsed by remember {
        derivedStateOf {
            val visibleItems = lazyListState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) false else visibleItems.none { it.index == 0 }
        }
    }

    val claim = LocalClaimSnackbarHost.current
    DisposableEffect(Unit) {
        claim(true)
        onDispose { claim(false) }
    }

    // Always reachable whenever the book has readable chapters, regardless of list length or scroll position.
    val canRead = uiState.readingAvailable && hasAvailableVolumes && uiState.userReadingData != null && !selectingChapters
    val isStartReading = uiState.userReadingData?.lastReadChapterId == null
    val fabTextRes = if (isStartReading) R.string.start_reading else R.string.continue_reading

    val snackbarBottomPad by animateDpAsState(
        targetValue = if (canRead) 84.dp else 16.dp,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "detailSnackbarBottomPad",
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selectingChapters) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.chapters_selected, selectedIds.size),
                            style = typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        EditorialIconButton(
                            onClick = exitSelection,
                            enabled = !savingReadState,
                            painter = painterResource(R.drawable.close_24px),
                            contentDescription = stringResource(R.string.cancel),
                        )
                    },
                    actions = {
                        TextButton(
                            enabled = !savingReadState && catalogIds.isNotEmpty(),
                            onClick = {
                                selectedChapterIds = if (selectedIds == catalogIds) emptyList() else catalogIds.toList()
                            },
                        ) {
                            Text(
                                text = stringResource(if (selectedIds == catalogIds) R.string.deselect_all else R.string.select_all),
                                fontWeight = FontWeight.W600,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    },
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorScheme.surface,
                        scrolledContainerColor = tokens.topBarFallbackSurface,
                    ),
                )
            } else {
                DetailTopBar(
                    title = uiState.bookInformation?.map { it.title }?.getOrElse { "" } ?: "",
                    volumesEmpty = volumesEmpty,
                    readingAvailable = uiState.readingAvailable,
                    onClickBackButton = onClickBackButton,
                    onClickExport = { showExportBottomSheet = true },
                    onClickTextFormatting = {
                        uiState.bookInformation?.onOk {
                            navController.navigateToSettingsTextFormattingRulesDestination(it.id)
                        }?.onErr {
                            textToast(context, it.message, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClickMarkAsRead = { enterSelection(ChapterReadAction.Read) },
                    onClickMarkAsUnread = { enterSelection(ChapterReadAction.Unread) },
                    canMarkChapters = uiState.userReadingData != null,
                    blockingMenu = blockingMenu,
                    scrollBehavior = scrollBehavior,
                    isCollapsed = isCollapsed,
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = snackbarBottomPad.coerceAtLeast(0.dp)),
            ) { data ->
                LnrSnackbar(data)
            }
        },
        bottomBar = {
            if (selectingChapters) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.cardElevatedSurface)
                        .navigationBarsPadding(),
                ) {
                    EditorialHairlineDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = exitSelection,
                            enabled = !savingReadState,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            enabled = selectedIds.isNotEmpty() && !savingReadState,
                            shape = RoundedCornerShape(14.dp),
                            onClick = { confirmReadState = true },
                        ) {
                            Text(
                                text = stringResource(if (savingReadState) R.string.processing else actionText),
                                fontWeight = FontWeight.W600,
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(contentHazeState),
            ) {
                Crossfade(
                    targetState = uiState.bookInformation,
                    animationSpec = tween(260),
                    label = "DetailScreenCrossfade",
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
                                    if (!savingReadState) {
                                        selectedChapterIds =
                                            if (id in selectedChapterIds) selectedChapterIds - id else selectedChapterIds + id
                                    }
                                } else {
                                    onClickChapter(id)
                                }
                            },
                            lazyListState = if (selectingChapters) selectionListState else lazyListState,
                            selectingChapters = selectingChapters,
                            selectedChapterIds = selectedIds,
                            selectionEnabled = !savingReadState,
                            canRead = canRead,
                            cacheBook = cacheBook,
                            requestAddBookToBookshelf = requestAddBookToBookshelf,
                            onClickTag = onClickTag,
                            onClickCover = onClickCover,
                            onClickShowInfo = { showInfoBottomSheet = true },
                            onRetryVolumes = onRetryVolumes,
                        )
                    }?.onErr {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (localFileMissing) LocalBookMissingFile(onRelink)
                            EditorialCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 20.dp,
                                contentPadding = PaddingValues(18.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = it.title,
                                        style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                                        color = colorScheme.onSurface,
                                    )
                                    Text(
                                        text = it.message,
                                        style = typography.bodyMedium,
                                        color = colorScheme.onSurfaceVariant,
                                    )
                                    FilledTonalButton(
                                        onClick = onRetry,
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(stringResource(R.string.discovery_retry))
                                    }
                                }
                            }
                        }
                    } ?: DetailContentSkeleton(
                        Modifier
                            .fillMaxSize()
                            .background(colorScheme.surface),
                    )
                }
            }

            AnimatedVisibility(
                visible = canRead,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 20.dp, bottom = 20.dp),
                enter = slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(180)),
                exit = slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(140)),
            ) {
                LiquidGlassSurface(
                    shape = CircleShape,
                    variant = GlassVariant.Liquid,
                    hazeState = contentHazeState,
                    elevation = 12.dp,
                    tintColor = colorScheme.primary.copy(alpha = if (tokens.isDark) 0.82f else 0.88f),
                    fallbackColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary,
                    borderWidth = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 52.dp)
                            .clickable(role = Role.Button, onClick = onClickRead)
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.filled_menu_book_24px),
                            contentDescription = null,
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(fabTextRes),
                            style = typography.titleSmall.copy(fontWeight = FontWeight.W700),
                            color = colorScheme.onPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (showExportBottomSheet && uiState.readingAvailable) {
        uiState.bookVolumes?.onOk { bookVolumes ->
            ExportBottomSheet(
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                bookVolumes = bookVolumes,
                settings = exportSettings,
                onSettingsChange = { exportSettings = it },
                onDismissRequest = { showExportBottomSheet = false },
                onClickExport = onClickExportToEpub,
            )
        }
    }
    if (showInfoBottomSheet) {
        uiState.bookInformation?.onOk { bookInformation ->
            BookInfoBottomSheet(
                bookInformation = bookInformation,
                bookVolumes = uiState.bookVolumes?.get(),
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                onDismissRequest = { showInfoBottomSheet = false },
            )
        }
    }

    if (confirmReadState) {
        val radius = tokens.sheetRadius
        Dialog(onDismissRequest = { if (!savingReadState) confirmReadState = false }) {
            Column(
                modifier = Modifier
                    .sizeIn(minWidth = 280.dp, maxWidth = 420.dp)
                    .squircleBackground(color = tokens.cardElevatedSurface, cornerRadius = radius)
                    .squircleBorder(width = 0.85.dp, color = tokens.cardBorder, cornerRadius = radius)
                    .squircleClip(radius)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EditorialIconBadge(
                    painter = painterResource(
                        if (readAction == ChapterReadAction.Read) R.drawable.done_all_24px else R.drawable.deselect_24px,
                    ),
                    size = 48.dp,
                    iconSize = 24.dp,
                    cornerRadius = 15.dp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(actionText),
                    style = typography.displayMedium.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        if (readAction == ChapterReadAction.Read) R.string.mark_read_confirmation
                        else R.string.mark_unread_confirmation,
                        selectedIds.size,
                    ),
                    style = typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    TextButton(
                        enabled = !savingReadState,
                        onClick = { confirmReadState = false },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        enabled = !savingReadState && selectedIds.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            if (savingReadState) return@Button
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
                        },
                    ) {
                        Text(
                            text = stringResource(if (savingReadState) R.string.processing else R.string.confirm),
                            fontWeight = FontWeight.W600,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailContentSkeleton(modifier: Modifier = Modifier) {
    val rounded = RoundedCornerShape(8.dp)
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(0.4.seconds)
        started = true
    }

    val baseColor = colorScheme.surfaceContainerLow
    val highlightColor = colorScheme.surfaceContainerHigh
    val shimmer = rememberSkeletonShimmer(baseColor, highlightColor)

    Column(
        modifier = modifier
            .then(if (started) Modifier.shimmer(shimmer) else Modifier)
            .padding(horizontal = itemHorizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(176.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 174.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(baseColor),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.fillMaxWidth(0.92f).height(22.dp).clip(rounded).background(baseColor))
                Box(Modifier.fillMaxWidth(0.70f).height(18.dp).clip(rounded).background(baseColor))
                Box(Modifier.fillMaxWidth(0.50f).height(16.dp).clip(rounded).background(baseColor))
                Box(Modifier.fillMaxWidth(0.60f).height(14.dp).clip(rounded).background(baseColor))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(baseColor),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(baseColor),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.32f).height(22.dp).clip(rounded).background(baseColor))
            repeat(4) {
                Box(Modifier.fillMaxWidth().height(16.dp).clip(rounded).background(baseColor))
            }
        }
    }
}

private val itemHorizontalPadding = 16.dp

@Composable
private fun DetailContent(
    modifier: Modifier = Modifier,
    uiState: DetailUiState,
    bookInformation: BookInformation,
    lazyListState: LazyListState,
    onClickChapter: (String) -> Unit,
    cacheBook: (String) -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickTag: (String) -> Unit,
    onClickCover: (Uri) -> Unit,
    onClickShowInfo: () -> Unit,
    selectingChapters: Boolean,
    selectedChapterIds: Set<String>,
    selectionEnabled: Boolean,
    canRead: Boolean,
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
    val closeSearch = {
        directory.closeSearch()
        focusManager.clearFocus()
    }
    BackHandler(directory.searchExpanded, onBack = closeSearch)

    val showHeaderBlocks = !selectingChapters && !searching
    val hasTagsBlock = bookInformation.publishingHouse.isNotEmpty() || bookInformation.tags.isNotEmpty()
    val hasIntroBlock = bookInformation.description.isNotBlank()

    LaunchedEffect(
        directory.scrollToDirectory,
        directory.chapterToLocate,
        data.page,
        data.directoryItemKeys,
        searching,
        data.matches,
        uiState.readingAvailable,
        showHeaderBlocks,
        hasTagsBlock,
        hasIntroBlock,
    ) {
        if (!directory.scrollToDirectory || (searching && data.matches == null)) return@LaunchedEffect
        val headerIndex =
            (if (showHeaderBlocks) 2 + (if (hasTagsBlock) 1 else 0) + (if (hasIntroBlock) 1 else 0) else 0) +
                (if (!uiState.readingAvailable) 1 else 0)
        val chapterKey = data.chapters.firstOrNull { it.chapter.id == directory.chapterToLocate }?.key
        val chapterIndex = chapterKey?.let(data.directoryItemKeys::indexOf) ?: -1
        val errorItemCount = if (uiState.bookVolumes?.isErr == true) 1 else 0
        activeListState.scrollToItem(
            if (chapterIndex >= 0 && !searching) headerIndex + 1 + errorItemCount + chapterIndex else headerIndex,
        )
        directory.consumeScrollRequest()
    }

    LazyColumn(
        state = activeListState,
        modifier = modifier,
    ) {
        if (showHeaderBlocks) {
            item(key = "hero") {
                BookCardBlock(
                    bookInformation = bookInformation,
                    showReadingMetadata = uiState.readingAvailable,
                    modifier = Modifier
                        .graphicsLayer {
                            translationY = if (lazyListState.firstVisibleItemIndex == 0) {
                                (lazyListState.firstVisibleItemScrollOffset * 0.22f).coerceIn(0f, 96f)
                            } else {
                                0f
                            }
                        }
                        .fillMaxWidth(),
                    onClickCover = onClickCover,
                )
            }

            if (hasTagsBlock) {
                item(key = "tags") {
                    TagsBlock(
                        bookInformation = bookInformation,
                        onClickTag = onClickTag,
                    )
                }
            }

            item(key = "quick-operations") {
                QuickOperationsBlock(
                    isInBookshelf = uiState.isInBookshelf,
                    downloadState = uiState.downloadState,
                    canCache = uiState.canCache,
                    downloadItem = uiState.downloadItem,
                    onClickAddToBookShelf = { requestAddBookToBookshelf(bookInformation.id) },
                    onClickCache = { cacheBook(bookInformation.id) },
                    onClickShowInfo = onClickShowInfo,
                )
            }

            if (hasIntroBlock) {
                item(key = "intro") {
                    IntroBlock(description = bookInformation.description)
                }
            }
        }

        if (!uiState.readingAvailable) {
            item(key = "reading-unavailable") {
                if (localFileMissing) {
                    LocalBookMissingFile(
                        onRelink = onRelink,
                        modifier = Modifier.padding(horizontal = itemHorizontalPadding, vertical = 8.dp),
                    )
                } else {
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = itemHorizontalPadding, vertical = 8.dp),
                        cornerRadius = 16.dp,
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.metadataOnly) R.string.source_metadata_only else R.string.source_reading_unavailable,
                            ),
                            style = typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (uiState.directoryAvailable) {
            item(key = "directory-toolbar") {
                EditorialHairlineDivider(Modifier.padding(horizontal = itemHorizontalPadding, vertical = 6.dp))
                DirectoryToolbar(
                    chapterCount = data.allChapters.size,
                    page = data.page,
                    onPageChange = directory::changePage,
                    descending = directory.descending,
                    hideRead = directory.hideReadChapters,
                    selecting = selectingChapters,
                    canLocate = data.readingIndex >= 0,
                    searchExpanded = directory.searchExpanded,
                    query = directory.query,
                    matchCount = if (searching) results?.size else null,
                    onToggleOrder = directory::toggleOrder,
                    onToggleHideRead = directory::toggleHideRead,
                    onLocate = { directory.locateCurrentChapter(data) { focusManager.clearFocus() } },
                    onToggleSearch = { if (directory.searchExpanded) closeSearch() else directory.openSearch() },
                    onQueryChange = directory::changeQuery,
                )
            }

            uiState.bookVolumes?.onErr { error ->
                item(key = "directory-error") {
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = itemHorizontalPadding, vertical = 8.dp),
                        cornerRadius = 18.dp,
                        containerColor = colorScheme.errorContainer.copy(alpha = if (NextVolDesign.tokens.isDark) 0.28f else 0.36f),
                        borderColor = colorScheme.error.copy(alpha = 0.36f),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (uiState.bookVolumes.availableVolumes() != null) {
                                    stringResource(R.string.book_directory_incomplete)
                                } else {
                                    error.title
                                },
                                style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                                color = colorScheme.onSurface,
                            )
                            Text(
                                text = error.message,
                                style = typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onRetryVolumes) {
                                Text(stringResource(R.string.discovery_retry))
                            }
                        }
                    }
                }
            }

            bookVolumes?.let {
                if (searching) {
                    when {
                        results == null -> item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Loading()
                            }
                        }
                        results.isEmpty() -> item {
                            EmptyPage(
                                icon = painterResource(R.drawable.not_found_90dp),
                                title = stringResource(R.string.reader_directory_no_results),
                                description = stringResource(R.string.reader_directory_no_results_hint),
                            )
                        }
                        else -> items(results, key = { it.key }, contentType = { "chapter" }) { result ->
                            ChapterItem(
                                chapter = result.chapter,
                                isRead = result.chapter.id in data.readIds,
                                isLastRead = result.chapter.id == currentChapterId,
                                readingProgress = progress[result.chapter.id] ?: 0f,
                                onClick = {
                                    focusManager.clearFocus()
                                    onClickChapter(result.chapter.id)
                                },
                                selecting = selectingChapters,
                                selected = result.chapter.id in selectedChapterIds,
                                selectionEnabled = selectionEnabled,
                                readingEnabled = uiState.readingAvailable,
                                volumeTitle = result.volumeTitle,
                            )
                        }
                    }
                } else if (data.allChapters.isNotEmpty()) {
                    data.pageVolumes.forEach { (volumeId, entries) ->
                        val volume = data.volumesById.getValue(volumeId)
                        if (volume.volumeTitle.isNotBlank()) {
                            item(key = "volume:$volumeId", contentType = "volume") {
                                val first = data.chapterOrdinals.getValue(entries.first().key)
                                val last = data.chapterOrdinals.getValue(entries.last().key)
                                DirectoryVolumeHeader(
                                    volumeId = volumeId,
                                    title = volume.volumeTitle,
                                    readCount = data.readCounts.getValue(volumeId),
                                    chapterCount = volume.chapters.size,
                                    pageRange = if (entries.size < volume.chapters.size) {
                                        stringResource(
                                            R.string.detail_directory_volume_page_range,
                                            minOf(first, last),
                                            maxOf(first, last),
                                        )
                                    } else {
                                        null
                                    },
                                    expanded = directory.isVolumeExpanded(volumeId, data),
                                    onToggle = { directory.toggleVolume(volumeId, data) },
                                )
                            }
                        }
                        entries.forEach { entry ->
                            if (entry.key in data.visibleChapterKeys) {
                                item(key = entry.key, contentType = "chapter") {
                                    val chapter = entry.chapter
                                    ChapterItem(
                                        chapter = chapter,
                                        isRead = chapter.id in data.readIds,
                                        isLastRead = chapter.id == currentChapterId,
                                        readingProgress = progress[chapter.id] ?: 0f,
                                        onClick = { onClickChapter(chapter.id) },
                                        selecting = selectingChapters,
                                        selected = chapter.id in selectedChapterIds,
                                        selectionEnabled = selectionEnabled,
                                        readingEnabled = uiState.readingAvailable,
                                    )
                                }
                            }
                        }
                    }
                    if (data.chapters.isEmpty() && data.hideRead) {
                        item {
                            EditorialCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = itemHorizontalPadding, vertical = 12.dp),
                                cornerRadius = 18.dp,
                                contentPadding = PaddingValues(18.dp),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.detail_directory_filtered_empty),
                                        style = typography.bodyMedium,
                                        color = colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                    FilledTonalButton(
                                        onClick = directory::showReadChapters,
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(stringResource(R.string.detail_directory_show_all))
                                    }
                                }
                            }
                        }
                    }
                    if (data.pageCount > 1) {
                        item(key = "directory-pagination") {
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                DirectoryPageNavigation(
                                    chapterCount = data.allChapters.size,
                                    page = data.page,
                                    descending = directory.descending,
                                    onPageChange = directory::changePage,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            text = stringResource(R.string.info_volume_chapters_count, 0),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            style = typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (uiState.bookVolumes == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Loading()
                    }
                }
            }
        }

        item(key = "bottom-clearance") {
            Spacer(Modifier.height(if (canRead) 92.dp else 20.dp))
        }
        navigationBarSpacer()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
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
    val tokens = NextVolDesign.tokens
    val titleProgress by animateFloatAsState(
        targetValue = if (isCollapsed) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "detailTitleProgress",
    )

    TopAppBar(
        title = {
            val offset = 14.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = stringResource(R.string.detail_title),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.displayLarge,
                    color = colorScheme.onSurface,
                    modifier = Modifier
                        .offset { IntOffset(0, (-offset * titleProgress).toPx().toInt()) }
                        .graphicsLayer { alpha = 1f - titleProgress },
                )
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.titleLarge.copy(fontWeight = FontWeight.W700),
                    color = colorScheme.onSurface,
                    modifier = Modifier
                        .offset { IntOffset(0, (offset * (1f - titleProgress)).toPx().toInt()) }
                        .graphicsLayer { alpha = titleProgress },
                )
            }
        },
        navigationIcon = {
            EditorialIconButton(
                onClick = onClickBackButton,
                painter = painterResource(id = R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
        },
        actions = {
            if (readingAvailable || blockingMenu != null) {
                DetailTopBarActions(
                    volumesEmpty = volumesEmpty || !readingAvailable,
                    onClickExport = onClickExport,
                    onClickTextFormatting = onClickTextFormatting,
                    onClickMarkAsRead = onClickMarkAsRead,
                    onClickMarkAsUnread = onClickMarkAsUnread,
                    canMarkChapters = canMarkChapters,
                    blockingMenu = blockingMenu,
                )
            }
            Spacer(Modifier.width(4.dp))
        },
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorScheme.surface,
            scrolledContainerColor = tokens.topBarFallbackSurface,
        ),
    )
}

@Composable
private fun DetailTopBarActions(
    volumesEmpty: Boolean,
    onClickExport: () -> Unit,
    onClickTextFormatting: () -> Unit,
    onClickMarkAsRead: () -> Unit,
    onClickMarkAsUnread: () -> Unit,
    canMarkChapters: Boolean,
    blockingMenu: (@Composable (() -> Unit) -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMarkItems = !volumesEmpty && canMarkChapters
    val hasMenuItems = hasMarkItems || blockingMenu != null

    EditorialIconButton(
        enabled = !volumesEmpty,
        onClick = onClickExport,
        painter = painterResource(id = R.drawable.file_export_24px),
        contentDescription = stringResource(R.string.export),
    )
    EditorialIconButton(
        enabled = !volumesEmpty,
        onClick = onClickTextFormatting,
        painter = painterResource(id = R.drawable.find_replace_24px),
        contentDescription = stringResource(R.string.settings_text_formatting),
    )
    if (hasMenuItems) {
        Box {
            EditorialIconButton(
                onClick = { menuExpanded = true },
                painter = painterResource(id = R.drawable.more_vert_24px),
                contentDescription = stringResource(R.string.action_more_options),
            )
            EditorialPopupMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (hasMarkItems) {
                    EditorialPopupMenuItem(
                        text = stringResource(R.string.mark_as_read),
                        leadingIcon = painterResource(R.drawable.done_all_24px),
                        onClick = {
                            menuExpanded = false
                            onClickMarkAsRead()
                        },
                    )
                    EditorialPopupMenuItem(
                        text = stringResource(R.string.mark_as_unread),
                        leadingIcon = painterResource(R.drawable.deselect_24px),
                        onClick = {
                            menuExpanded = false
                            onClickMarkAsUnread()
                        },
                    )
                }
                if (blockingMenu != null) {
                    if (hasMarkItems) {
                        EditorialHairlineDivider(Modifier.padding(vertical = 4.dp))
                    }
                    blockingMenu { menuExpanded = false }
                }
            }
        }
    }
}

@Composable
private fun BookCardBlock(
    bookInformation: BookInformation,
    showReadingMetadata: Boolean,
    modifier: Modifier,
    onClickCover: (Uri) -> Unit,
) {
    val tokens = NextVolDesign.tokens
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
            bookInformation.lastUpdated.format(dateFormatter()),
        )
    } else {
        null
    }
    val wordCountText = if (bookInformation.wordCount.count > 0) bookInformation.wordCount.get() else null
    val heroBackdrop = Brush.verticalGradient(
        colors = listOf(
            colorScheme.primaryContainer.copy(alpha = if (tokens.isDark) 0.18f else 0.24f),
            colorScheme.surface,
        ),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(heroBackdrop),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 190.dp)
                .padding(horizontal = itemHorizontalPadding, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .shadow(
                        elevation = 10.dp,
                        shape = RoundedCornerShape(12.dp),
                        ambientColor = tokens.glassShadow,
                        spotColor = tokens.glassShadow,
                        clip = false,
                    )
                    .clickable(
                        onClick = {
                            if (coverFallback) {
                                coroutineScope.launch {
                                    val uri = withContext(Dispatchers.IO) {
                                        DefaultBookCoverRenderer.cacheUri(
                                            context,
                                            bookInformation.title,
                                            bookInformation.id,
                                            bookInformation.author,
                                        )
                                    }
                                    onClickCover(uri)
                                }
                            } else {
                                onClickCover(bookInformation.coverUri)
                            }
                        },
                    ),
            ) {
                Cover(
                    bookId = bookInformation.id,
                    height = 174.dp,
                    width = 120.dp,
                    uri = bookInformation.coverUri,
                    title = bookInformation.title,
                    author = bookInformation.author,
                    rounded = 12.dp,
                    onFallbackChanged = { coverFallback = it },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = bookInformation.title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.displayMedium.copy(fontWeight = FontWeight.W700),
                    color = colorScheme.onSurface,
                )
                if (bookInformation.subtitle.isNotEmpty()) {
                    Text(
                        text = bookInformation.subtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodyMedium,
                    )
                }
                if (bookInformation.author.isNotBlank()) {
                    Text(
                        text = bookInformation.author,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                        color = colorScheme.primary,
                    )
                }
                if (showReadingMetadata && (updateText != null || wordCountText != null)) {
                    Column(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (updateText != null) {
                            InfoRow(
                                icon = { BookStatusIcon(bookInformation.isComplete) },
                                text = updateText,
                            )
                        }
                        if (wordCountText != null) {
                            InfoRow(
                                icon = {
                                    Icon(
                                        painter = painterResource(R.drawable.text_snippet_24px),
                                        contentDescription = null,
                                        tint = colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                },
                                text = wordCountText,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagsBlock(
    bookInformation: BookInformation,
    onClickTag: (String) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = itemHorizontalPadding, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bookInformation.publishingHouse.isNotEmpty()) {
            // Non-interactive metadata badge: publisher has no tap action.
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tokens.cardElevatedSurface)
                    .border(0.75.dp, tokens.cardBorder, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = bookInformation.publishingHouse,
                    style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        bookInformation.tags.forEach { tag ->
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(tokens.cardSurface)
                    .border(0.75.dp, tokens.cardBorder, CircleShape)
                    .clickable(role = Role.Button) { onClickTag(tag) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = tag,
                    style = typography.labelMedium.copy(fontWeight = FontWeight.W500),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                )
            }
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
    active: Boolean = false,
    supportingText: String? = null,
) {
    val contentTint = when {
        !enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        active -> colorScheme.primary
        else -> colorScheme.onSurface
    }
    val iconTint = when {
        !enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> colorScheme.primary
    }
    Column(
        modifier = modifier
            .heightIn(min = 68.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Icon(
            modifier = Modifier.size(20.dp),
            painter = icon,
            tint = iconTint,
            contentDescription = null,
        )
        Text(
            text = title,
            style = typography.labelLarge.copy(fontWeight = if (active) FontWeight.W600 else FontWeight.W500),
            color = contentTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        supportingText?.let {
            Text(
                text = it,
                style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickOperationsBlock(
    isInBookshelf: Boolean,
    downloadState: BookDownloadStatus,
    canCache: Boolean,
    downloadItem: DownloadItem?,
    onClickAddToBookShelf: () -> Unit,
    onClickCache: () -> Unit,
    onClickShowInfo: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val phase = downloadState.displayPhase
    val showCache = canCache || phase != BookDownloadPhase.None

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surface)
            .padding(horizontal = itemHorizontalPadding, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickOperationButton(
                    icon = painterResource(
                        if (isInBookshelf) R.drawable.filled_bookmark_add_24px else R.drawable.bookmark_add_24px,
                    ),
                    title = stringResource(
                        if (isInBookshelf) R.string.activity_collections else R.string.add_to_bookshelf,
                    ),
                    active = isInBookshelf,
                    onClick = onClickAddToBookShelf,
                    modifier = Modifier.weight(1f),
                )

                if (showCache) {
                    VerticalDivider(Modifier.height(34.dp), color = tokens.divider)
                    QuickOperationButton(
                        icon = painterResource(
                            if (phase == BookDownloadPhase.Complete) R.drawable.filled_cloud_download_24px
                            else R.drawable.cloud_download_24px,
                        ),
                        title = downloadStatusLabel(downloadState),
                        active = phase == BookDownloadPhase.Complete,
                        supportingText = if (phase == BookDownloadPhase.Updating) {
                            downloadItem?.progress?.takeIf { it >= 0f && it < 1f }?.let { "${(it * 100).toInt()}%" }
                        } else {
                            null
                        },
                        enabled = canCache && phase != BookDownloadPhase.Updating,
                        onClick = onClickCache,
                        modifier = Modifier.weight(1f),
                    )
                }

                VerticalDivider(Modifier.height(34.dp), color = tokens.divider)

                QuickOperationButton(
                    icon = painterResource(R.drawable.info_24px),
                    title = stringResource(R.string.action_show_info),
                    onClick = onClickShowInfo,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (downloadState.content.phase != BookDownloadPhase.None) {
            Text(
                text = downloadStatusText(downloadState),
                modifier = Modifier.padding(horizontal = 6.dp),
                style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntroBlock(description: String) {
    val tokens = NextVolDesign.tokens
    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    var hasOverflow by remember(description) { mutableStateOf(false) }

    val fadingBrush = remember {
        Brush.verticalGradient(
            0.72f to Color.White,
            1f to Color.Transparent,
        )
    }
    val whiteBrush = remember {
        Brush.verticalGradient(listOf(Color.White, Color.White))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = itemHorizontalPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.detail_introduction),
            style = typography.displayMedium.copy(fontWeight = FontWeight.W700),
            color = colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )

        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 18.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing))
                        .fadingEdge(if (!expanded && hasOverflow) fadingBrush else whiteBrush),
                ) {
                    Text(
                        text = description,
                        style = typography.bodyLarge,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        color = colorScheme.onSurface,
                        onTextLayout = { layout ->
                            if (!expanded) {
                                hasOverflow = layout.hasVisualOverflow
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (hasOverflow || expanded) {
                    val rotation by animateFloatAsState(
                        targetValue = if (expanded) 0f else 180f,
                        animationSpec = tween(200),
                        label = "introArrowRotation",
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(CircleShape)
                            .background(tokens.activePillFill)
                            .border(0.75.dp, tokens.activePillBorder, CircleShape)
                            .clickable(role = Role.Button) { expanded = !expanded }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(rotation),
                            tint = colorScheme.primary,
                        )
                        Text(
                            text = stringResource(if (expanded) R.string.collapse else R.string.expand),
                            style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                            color = colorScheme.primary,
                        )
                    }
                }
            }
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
    readingEnabled: Boolean,
    volumeTitle: String? = null,
) {
    val tokens = NextVolDesign.tokens
    val radius = 14.dp
    val bgColor = when {
        selected -> tokens.activePillFill
        isLastRead -> tokens.activePillFill
        else -> Color.Transparent
    }
    val borderColor = when {
        selected || isLastRead -> tokens.activePillBorder
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .squircleBackground(color = bgColor, cornerRadius = radius)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.squircleBorder(width = 0.75.dp, color = borderColor, cornerRadius = radius)
                } else {
                    Modifier
                },
            )
            .squircleClip(radius)
            .then(
                if (selecting) {
                    Modifier.toggleable(
                        value = selected,
                        enabled = selectionEnabled,
                        role = Role.Checkbox,
                        onValueChange = { onClick() },
                    )
                } else {
                    Modifier.clickable(enabled = readingEnabled, onClick = onClick)
                },
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = selectionEnabled,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = chapter.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = typography.bodyLarge.copy(
                    fontWeight = when {
                        isLastRead -> FontWeight.W600
                        isRead -> FontWeight.W400
                        else -> FontWeight.W500
                    },
                ),
                color = when {
                    isLastRead -> colorScheme.primary
                    isRead -> colorScheme.onSurfaceVariant
                    else -> colorScheme.onSurface
                },
            )
            if (volumeTitle != null) {
                Text(
                    text = volumeTitle,
                    style = typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isLastRead) {
            Icon(
                modifier = Modifier.size(20.dp),
                painter = painterResource(R.drawable.target_24px),
                tint = colorScheme.primary,
                contentDescription = stringResource(R.string.last_read),
            )
        } else if (readingProgress > 0f) {
            // Correct Float rounding at integer percentages without reporting completion early.
            val percent = (readingProgress.coerceIn(0f, 1f) * 100).nextUp().toInt()
                .coerceAtMost(if (isRead) 100 else 99)
            Text(
                text = "$percent%",
                maxLines = 1,
                style = typography.labelMedium.copy(
                    fontWeight = if (isRead) FontWeight.W400 else FontWeight.W600,
                    fontFeatureSettings = "tnum",
                ),
                color = if (isRead) colorScheme.onSurfaceVariant else colorScheme.primary,
            )
        }
    }
}
