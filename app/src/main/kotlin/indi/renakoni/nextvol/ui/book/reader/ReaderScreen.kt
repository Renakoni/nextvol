package indi.renakoni.nextvol.ui.book.reader

import indi.renakoni.nextvol.data.book.availableVolumes
import android.annotation.SuppressLint
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import coil3.compose.AsyncImagePainter
import com.github.michaelbull.result.get
import com.github.michaelbull.result.map
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.ui.book.reader.content.ContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderVolumeKeysEnabled
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderSpeechFollow
import indi.renakoni.nextvol.ui.book.reader.content.ReaderSpeechFollow
import indi.renakoni.nextvol.ui.components.AnimatedText
import indi.renakoni.nextvol.ui.components.AnimatedTextLine
import indi.renakoni.nextvol.ui.components.LnrSnackbar
import indi.renakoni.nextvol.ui.home.settings.data.MenuOptions
import indi.renakoni.nextvol.ui.tts.ReadAloudSheet
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.readerBackgroundColor
import indi.renakoni.nextvol.utils.rememberReaderBackgroundPainter
import indi.renakoni.nextvol.utils.showSnackbar
import kotlinx.coroutines.launch
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.ui.book.reader.bookmark.LocalReaderBookmarks
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderPositionSession
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkSession
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarkPosition
import indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarksSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.time.LocalTime
import java.util.Locale

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    readingScreenUiState: ReaderScreenUiState,
    settingState: ReaderSettingsEditor,
    fontFamilySettings: ReaderFontFamilySettings,
    onClickBackButton: () -> Unit,
    accumulateReadTime: (bookId: String, Int) -> Unit,
    updateTotalReadingTime: (bookId: String, Int) -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    onChangeChapter: (chapterId: String) -> Unit,
    onClickThemeSettings: () -> Unit,
    speechState: ReadAloudState,
    onStartReadAloud: () -> Unit,
    onSpeechCommand: (SpeechAction) -> Unit,
    onSpeechSettings: () -> Unit,
    onSleepTimer: (Int?) -> Unit,
    bookmarks: List<ReadingBookmark> = emptyList(),
    bookmarksBusy: Boolean = false,
    bookmarkNotice: Int? = null,
    onBookmarkNoticeShown: () -> Unit = {},
    onAddBookmark: (ReadingBookmark) -> Unit = {},
    onDeleteBookmark: (ReadingBookmark) -> Unit = {},
    onSourcePanel: (() -> Unit)? = null,
    sourcePanelVisible: Boolean = false,
) = ReaderMotionTheme(settingState.reduceMotion) {
    ReaderPaperTheme(settingState, manageSystemBars = true) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isImmersive by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHost.current
    val backBlockMode = settingState.backBlockMode
    var lastBackPressTime: Long by remember { mutableLongStateOf(0) }
    var showSettingsBottomSheet by remember { mutableStateOf(false) }
    val contentState = readingScreenUiState.contentUiState
    // Scroll geometry depends on the viewport, not the chapter. Flip capability still does.
    val layoutResult = remember(contentState,
        if (contentState is ScrollContentUiState) null else contentState?.readingChapterContent?.get()) {
        mutableStateOf<ReaderLayoutResult?>(null)
    }
    var showChapterSelectionBottomSheet by remember { mutableStateOf(false) }
    val bookmarkSession = remember(readingScreenUiState.bookId) { ReaderBookmarkSession() }
    val positionSession = LocalReaderPositionSession.current
    var showBookmarks by remember(readingScreenUiState.bookId) { mutableStateOf(false) }
    var bookmarkPosition by remember(readingScreenUiState.bookId) { mutableStateOf<ReaderBookmarkPosition?>(null) }
    var creatingBookmark by remember { mutableStateOf(false) }
    var showReadAloud by remember { mutableStateOf(false) }
    var selectedVolumeId by remember { mutableStateOf("") }
    var followSpeech by rememberSaveable(readingScreenUiState.bookId, speechState.request?.bookId) { mutableStateOf(true) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val speechPosition = speechState.position?.takeIf { it.bookId == readingScreenUiState.bookId }
    val speechFollow = ReaderSpeechFollow(speechPosition, followSpeech,
        onManualNavigation = {
            if (speechPosition != null || speechState.request?.let {
                    !it.isPreview && it.bookId == readingScreenUiState.bookId &&
                        (speechState.isActive || speechState.phase == SpeechPhase.Paused)
                } == true) followSpeech = false
        },
        active = lifecycle == Lifecycle.State.RESUMED &&
            !showSettingsBottomSheet && !showChapterSelectionBottomSheet && !showReadAloud && !showBookmarks)
    val previousChapter = { bookmarkSession.pending = null; speechFollow.onManualNavigation(); onClickPrevChapter() }
    val nextChapter = { bookmarkSession.pending = null; speechFollow.onManualNavigation(); onClickNextChapter() }
    LaunchedEffect(speechPosition?.chapterId, speechFollow.following, speechFollow.active, readingScreenUiState.contentUiState) {
        val mode = readingScreenUiState.contentUiState
        if (speechFollow.following && speechFollow.active && speechPosition != null && mode != null &&
            mode.readingChapterId != speechPosition.chapterId) onChangeChapter(speechPosition.chapterId)
    }

    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(bookmarkNotice, bookmarkSession.notice, showBookmarks) {
        if (showBookmarks) return@LaunchedEffect
        val notice = bookmarkSession.notice ?: bookmarkNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(notice))
        bookmarkSession.notice = null
        onBookmarkNoticeShown()
    }
    if (bookmarkSession.pending != null) {
        LaunchedEffect(readingScreenUiState.contentUiState?.readingChapterContent, bookmarkSession.pending) {
            val pending = bookmarkSession.pending
            val content = readingScreenUiState.contentUiState
            if (pending != null && content?.readingChapterId == pending.chapterId && content.readingChapterContent?.isErr == true) {
                bookmarkSession.pending = null
                bookmarkSession.notice = R.string.reader_bookmarks_load_failed
            }
        }
    }
    val settingsBottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val chaptersBottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    val claim = LocalClaimSnackbarHost.current

    DisposableEffect(Unit) {
        claim(true)
        onDispose { claim(false) }
    }

    BackHandler {
        when (backBlockMode) {
            MenuOptions.ReaderBackBlockMode.None -> {
                isImmersive = false
                onClickBackButton()
            }
            MenuOptions.ReaderBackBlockMode.DoublePress -> {
                val now = System.currentTimeMillis()
                if (!isImmersive || now - lastBackPressTime < 1500) {
                    onClickBackButton()
                } else {
                    lastBackPressTime = now
                    showSnackbar(
                        coroutineScope = coroutineScope,
                        hostState = snackbarHostState,
                        message = context.getString(R.string.reader_back_press_again),
                        duration = SnackbarDuration.Short
                    )
                }
            }

            MenuOptions.ReaderBackBlockMode.FullyBlocked -> {}
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            isImmersive = false
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = {
            SnackbarHost(LocalSnackbarHost.current) { data ->
                LnrSnackbar(
                    data,
                    modifier = Modifier
                        .padding(bottom = if (isImmersive) 56.dp else 12.dp)
                )
            }
        },
        containerColor = readerBackgroundColor(settingState),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
        if (settingState.usesBackgroundImage) {
            val bgPainter = rememberReaderBackgroundPainter(settingState)
            val bgState by remember(bgPainter) {
                (bgPainter as? AsyncImagePainter)?.state
            }?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

            key(bgState) {
                Image(
                    modifier = Modifier.fillMaxSize(),
                    painter = bgPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop
                )
            }
        }

        val activity = context as Activity
        val window = activity.window
        ReaderWindowEffects(
            window = window,
            immersive = isImmersive,
            enableHideStatusBar = settingState.enableHideStatusBar,
            batteryIndicatorDisplayMode = settingState.batteryIndicatorDisplayMode,
            keepScreenOn = settingState.keepScreenOn,
        )
        if (!sourcePanelVisible) ReaderReadingTimeEffects(
            currentBookId = { readingScreenUiState.bookId },
            updateTotalReadingTime = updateTotalReadingTime,
            accumulateReadTime = accumulateReadTime,
        )

        CompositionLocalProvider(LocalReaderSpeechFollow provides speechFollow, LocalReaderBookmarks provides bookmarkSession,
            LocalReaderLayoutResult provides layoutResult) {
            Content(
                isImmersive = isImmersive,
                volumeKeysEnabled = !sourcePanelVisible && !showSettingsBottomSheet && !showChapterSelectionBottomSheet && !showReadAloud && !showBookmarks,
                readingScreenUiState = readingScreenUiState,
                settingState = settingState,
                fontFamilySettings = fontFamilySettings,
                onClickPrevChapter = previousChapter,
                onClickNextChapter = nextChapter,
                onChangeIsImmersive = { isImmersive = !isImmersive }
            )
        }

        if (!isImmersive) {
            Box(Modifier.align(Alignment.TopCenter).readerProbeLayout("top-bar")) {
                ReaderTopBar(
                    onClickBackButton = onClickBackButton,
                    title = readingScreenUiState.chapterTitle(readingScreenUiState.contentUiState?.readingChapterId)
                        ?: stringResource(R.string.reader_chapter),
                    scrollBehavior = scrollBehavior,
                    onSourcePanel = onSourcePanel,
                    onReadAloud = {
                        onStartReadAloud()
                        showReadAloud = true
                    },
                )
            }
            Box(Modifier.align(Alignment.BottomCenter).readerProbeLayout("bottom-bar")) {
            ReaderBottomBar(
                hasNextChapter = readingScreenUiState.contentUiState?.readingChapterContent
                    ?.get()
                    ?.hasNextChapter() ?: false,
                hasPrevChapter = readingScreenUiState.contentUiState?.readingChapterContent
                    ?.get()
                    ?.hasPrevChapter() ?: false,
                onClickPrevChapter = previousChapter,
                onClickNextChapter = nextChapter,
                onClickSettings = { showSettingsBottomSheet = true },
                onClickChapterSelector = { showChapterSelectionBottomSheet = true },
                onClickBookmarks = {
                    bookmarkPosition = bookmarkSession.capture?.invoke()
                    showBookmarks = true
                },
            )
            }
        }
        }
    }
    if (showBookmarks) {
        ReaderBookmarksSheet(bookmarks.filter { it.bookId == readingScreenUiState.bookId },
            busy = bookmarksBusy || creatingBookmark,
            notice = bookmarkSession.notice ?: bookmarkNotice,
            onAdd = {
                val position = bookmarkPosition
                if (position == null || position.bookId != readingScreenUiState.bookId) {
                    bookmarkSession.notice = R.string.reader_bookmarks_not_ready
                } else if (!creatingBookmark) {
                    creatingBookmark = true
                    coroutineScope.launch {
                        try {
                            val bookmark = withContext(Dispatchers.Default) { position.bookmark() }
                            if (bookmark.bookId == readingScreenUiState.bookId) onAddBookmark(bookmark)
                        } catch (failure: CancellationException) { throw failure
                        } catch (failure: Exception) {
                            android.util.Log.e("ReadingBookmark", "Cannot capture bookmark", failure)
                            bookmarkSession.notice = R.string.reader_bookmarks_failed
                        } finally { creatingBookmark = false }
                    }
                }
            },
            onJump = { bookmark ->
                val volumes = readingScreenUiState.bookVolumes?.get()
                when {
                    volumes == null -> bookmarkSession.notice = R.string.reader_bookmarks_not_ready
                    bookmark.bookId != readingScreenUiState.bookId || volumes.volumes.none { volume -> volume.chapters.any { it.id == bookmark.chapterId } } ->
                        bookmarkSession.notice = R.string.reader_bookmarks_missing
                    else -> {
                        speechFollow.onManualNavigation()
                        bookmarkSession.pending = bookmark
                        showBookmarks = false
                        val content = readingScreenUiState.contentUiState
                        if (content != null) positionSession?.navigate(content, bookmark.bookId, bookmark.chapterId, false)
                        if (content?.readingChapterId != bookmark.chapterId || content.readingChapterContent?.isOk != true)
                            onChangeChapter(bookmark.chapterId)
                    }
                }
            }, onDelete = onDeleteBookmark, onDismiss = { showBookmarks = false })
    }
    if (showReadAloud) {
        ReadAloudSheet(
            state = speechState,
            onCommand = onSpeechCommand,
            onSettings = { showReadAloud = false; onSpeechSettings() },
            onDismiss = { showReadAloud = false },
            onSleepTimer = onSleepTimer,
        )
    }
    if (showSettingsBottomSheet) {
        CompositionLocalProvider(LocalReaderLayoutResult provides layoutResult) {
        SettingsBottomSheet(
            sheetState = settingsBottomSheetState,
            onDismissRequest = {
                coroutineScope.launch { settingsBottomSheetState.hide() }.invokeOnCompletion {
                    if (!settingsBottomSheetState.isVisible) {
                        showSettingsBottomSheet = false
                    }
                }
                showSettingsBottomSheet = false
            },
            settingState = settingState,
            onClickThemeSettings = {
                // Unmount the reader sheet before the destination transition starts.
                showSettingsBottomSheet = false
                coroutineScope.launch { settingsBottomSheetState.hide() }
                onClickThemeSettings()
            }
        )
        }
    }

    AnimatedVisibility(visible = showChapterSelectionBottomSheet,
        enter = if (settingState.reduceMotion) EnterTransition.None else fadeIn() + expandIn(),
        exit = if (settingState.reduceMotion) ExitTransition.None else shrinkOut() + fadeOut(),
    ) {
        readingScreenUiState.contentUiState?.let { contentUiState ->
            readingScreenUiState.bookVolumes?.availableVolumes()?.let { bookVolumes ->
                contentUiState.readingChapterId?.let { readingChapterId ->
                    ChapterSelectionBottomSheet(
                        sheetState = chaptersBottomSheetState,
                        selectedVolumeId = selectedVolumeId,
                        bookVolumes = bookVolumes,
                        directoryIncomplete = readingScreenUiState.bookVolumes?.isErr == true,
                        readingChapterId = readingChapterId,
                        onDismissRequest = {
                            coroutineScope.launch { chaptersBottomSheetState.hide() }
                                .invokeOnCompletion {
                                    if (!chaptersBottomSheetState.isVisible) {
                                        showChapterSelectionBottomSheet = false
                                    }
                                }
                            showChapterSelectionBottomSheet = false
                            selectedVolumeId =
                                bookVolumes.volumes.firstOrNull { volume ->
                                    volume.chapters.any {
                                        it.id == readingChapterId
                                    }
                                }?.volumeId ?: ""
                        },
                        onClickChapter = { bookmarkSession.pending = null; speechFollow.onManualNavigation(); onChangeChapter(it) },
                        onChangeSelectedVolumeId = {
                            selectedVolumeId = it
                        }
                    )
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Content(
    isImmersive: Boolean,
    readingScreenUiState: ReaderScreenUiState,
    settingState: ReaderSettings,
    fontFamilySettings: ReaderFontFamilySettings,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    onChangeIsImmersive: () -> Unit,
    volumeKeysEnabled: Boolean = true,
) {
    val textLayout = rememberReaderTextLayout(settingState)
    val speechFollow = LocalReaderSpeechFollow.current
    val bookmarks = LocalReaderBookmarks.current
    val positions = LocalReaderPositionSession.current
    Box(modifier = Modifier.fillMaxSize().readerProbeLayout("content-root")) {
        val isEnableIndicator =
            settingState.enableTimeIndicator ||
                    settingState.enableReadingChapterProgressIndicator ||
                    settingState.enableChapterTitleIndicator

        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                readingScreenUiState.contentUiState,
                // Navigation already animates entry. The first renderer must use its final size;
                // only an explicit switch between two existing modes needs a content transition.
                transitionSpec = { readerContentTransform(settingState.reduceMotion || initialState == null || targetState == null) },
                label = "ContentAnimate"
            ) { contentUiState ->
                // Controls cover the reading viewport; outgoing animated modes must release input.
                CompositionLocalProvider(indi.renakoni.nextvol.ui.book.reader.content.LocalReaderRendererActive provides
                    (contentUiState === readingScreenUiState.contentUiState),
                    LocalReaderTextLayout provides textLayout,
                    LocalReaderPositionSession provides if (contentUiState === readingScreenUiState.contentUiState)
                        positions else null,
                    LocalReaderBookmarks provides if (contentUiState === readingScreenUiState.contentUiState)
                        bookmarks else null,
                    LocalReaderSpeechFollow provides if (contentUiState === readingScreenUiState.contentUiState)
                        speechFollow else ReaderSpeechFollow(), LocalReaderVolumeKeysEnabled provides (
                    volumeKeysEnabled && isImmersive && contentUiState === readingScreenUiState.contentUiState
                )) {
                    ContentComponent(
                        uiState = contentUiState,
                        settingState = settingState,
                        fontFamilySettings = fontFamilySettings,
                        paddingValues = readerPadding(textLayout.settings, if (isEnableIndicator) 40.dp else 0.dp),
                        changeIsImmersive = onChangeIsImmersive,
                        onClickPrevChapter = onClickPrevChapter,
                        onClickNextChapter = onClickNextChapter,
                        chapterTitle = readingScreenUiState::chapterTitle,
                    )
                }
            }

            AnimatedVisibility(
                modifier = Modifier.align(Alignment.BottomCenter),
                visible = isEnableIndicator,
                enter = if (settingState.reduceMotion) EnterTransition.None else expandVertically(),
                exit = if (settingState.reduceMotion) ExitTransition.None else shrinkVertically()
            ) {
                Indicator(
                    Modifier
                        .padding(
                            if (settingState.autoPadding)
                                PaddingValues(
                                    bottom = 8.dp,
                                    start = 16.dp,
                                    end = 16.dp
                                )
                            else PaddingValues(
                                bottom = settingState.bottomPadding.dp,
                                start = settingState.leftPadding.dp,
                                end = settingState.rightPadding.dp
                            )
                        ),
                    enableBatteryIndicator = settingState.batteryIndicatorDisplayMode == "classic",
                    enableTimeIndicator = settingState.enableTimeIndicator,
                    enableChapterTitle = settingState.enableChapterTitleIndicator,
                    chapterTitle = readingScreenUiState.chapterTitle(readingScreenUiState.contentUiState?.readingChapterId)
                        ?: stringResource(R.string.reader_chapter),
                    enableReadingChapterProgressIndicator = settingState.enableReadingChapterProgressIndicator,
                    readingChapterProgress = readingScreenUiState.contentUiState?.readingProgress ?: 0f,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(
    onClickBackButton: () -> Unit,
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onReadAloud: () -> Unit,
    onSourcePanel: (() -> Unit)? = null,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onClickBackButton) {
                Icon(painterResource(id = R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
            }
        },
        title = {
            val reducedMotion = LocalReduceReaderMotion.current
            AnimatedContent(title, transitionSpec = { readerContentTransform(reducedMotion) }, label = "TitleAnimate") { text ->
                Text(
                    text = text,
                    style = typography.displayLarge,
                    fontWeight = FontWeight.W400,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.readerProbeLayout("top-title")
                )
            }
        },
        actions = {
            if (onSourcePanel != null) TextButton(onClick = onSourcePanel, modifier = Modifier.testTag("reader-source-panel")) {
                Text(stringResource(R.string.reader_source_panel))
            }
            IconButton(onClick = onReadAloud, modifier = Modifier.size(48.dp).testTag("reader-read-aloud")) {
                Icon(painterResource(R.drawable.headphones_24px), stringResource(R.string.tts_start))
            }
        },
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets.systemBarsIgnoringVisibility
            .union(WindowInsets.displayCutout)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderBottomBar(
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    onClickSettings: () -> Unit,
    onClickChapterSelector: () -> Unit,
    onClickBookmarks: () -> Unit = {},
) {
    BottomAppBar(
        windowInsets = WindowInsets.navigationBarsIgnoringVisibility
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                onClick = onClickPrevChapter,
                enabled = hasPrevChapter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.previous_chapter),
                        style = typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClickBookmarks) {
                    Icon(
                        painter = painterResource(R.drawable.outline_bookmark_24px),
                        contentDescription = stringResource(R.string.action_bookmark)
                    )
                }

                IconButton(onClick = onClickChapterSelector) {
                    Icon(
                        painter = painterResource(id = R.drawable.menu_24px),
                        contentDescription = stringResource(R.string.detail_contents)
                    )
                }

                IconButton(onClick = onClickSettings) {
                    Icon(
                        painter = painterResource(R.drawable.outline_settings_24px),
                        contentDescription = stringResource(R.string.settings)
                    )
                }
            }

            TextButton(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                onClick = onClickNextChapter,
                enabled = hasNextChapter
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward_24px),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.next_chapter),
                        style = typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun Indicator(
    modifier: Modifier = Modifier,
    enableBatteryIndicator: Boolean,
    enableTimeIndicator: Boolean,
    enableChapterTitle: Boolean,
    chapterTitle: String,
    enableReadingChapterProgressIndicator: Boolean,
    readingChapterProgress: Float
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableBatteryIndicator) {
                val batLevel = rememberReaderBatteryLevel().value
                ReaderIndicatorNumber(batLevel, typography.bodyLarge)
                Text(
                    text = "%",
                    style = typography.bodyLarge,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    modifier = Modifier.size(20.dp).testTag("reader-battery-icon"),
                    painter =
                        when {
                            batLevel == null -> painterResource(R.drawable.battery_android_question_24px)
                            (batLevel in 0..15) -> painterResource(R.drawable.battery_android_alert_24px)
                            (batLevel in 16..35) -> painterResource(R.drawable.battery_android_3_24px)
                            (batLevel in 36..65) -> painterResource(R.drawable.battery_android_4_24px)
                            (batLevel in 66..80) -> painterResource(R.drawable.battery_android_5_24px)
                            (batLevel in 81..95) -> painterResource(R.drawable.battery_android_6_24px)
                            (batLevel in 96..100) -> painterResource(R.drawable.battery_android_full_24px)
                            else -> painterResource(R.drawable.battery_android_question_24px)
                        },
                    tint = colorScheme.onSurfaceVariant,
                    contentDescription = null
                )
                Spacer(Modifier.width(14.dp))
            }
            if (enableTimeIndicator) {
                AnimatedText(
                    animationEnabled = !LocalReduceReaderMotion.current,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    text = String.format(Locale.US, "%d:%02d", LocalTime.now().hour, LocalTime.now().minute),
                    style = typography.bodyLarge.copy(
                        letterSpacing = 1.sp
                    ),
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (enableChapterTitle) {
                AnimatedTextLine(
                    animationEnabled = !LocalReduceReaderMotion.current,
                    modifier = Modifier.fillMaxWidth(),
                    text = chapterTitle,
                    textAlign = TextAlign.End,
                    style = typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableReadingChapterProgressIndicator) {
                ReaderIndicatorNumber(
                    number = (readingChapterProgress * 100).toInt(),
                    style = typography.bodyLarge.copy(fontWeight = FontWeight.W500),
                    modifier = Modifier.testTag("reader-progress-number"),
                )
                Text(
                    text = "%",
                    style = typography.bodyLarge,
                    fontWeight = FontWeight.W500,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Fixed digit width keeps the title still; the value itself follows progress immediately. */
@Composable
private fun ReaderIndicatorNumber(number: Int?, style: TextStyle, modifier: Modifier = Modifier) {
    val numberStyle = style.copy(fontFeatureSettings = "tnum")
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        // Two text nodes replace three rolling columns (sixty text nodes) per number.
        Text("000", Modifier.clearAndSetSemantics {}, style = numberStyle, color = Color.Transparent)
        Text(number?.toString() ?: "--", style = numberStyle, color = colorScheme.onSurfaceVariant)
    }
}
