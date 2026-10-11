package indi.renakoni.nextvol.ui.book.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import indi.renakoni.nextvol.utils.textToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import indi.renakoni.nextvol.ui.book.reader.content.LocalReaderPositionSession
import indi.renakoni.nextvol.ui.LocalReaderBookId
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.imageview.ImageViewerScreen
import indi.renakoni.nextvol.ui.components.ColorPickerDialog
import indi.renakoni.nextvol.ui.home.settings.theme.navigateToSettingsThemeDestination
import indi.renakoni.nextvol.ui.tts.navigateToSpeechSettings
import indi.renakoni.nextvol.utils.ImageUtils.saveBitmapAsPng
import indi.renakoni.nextvol.utils.ImageUtils.uriToBitmap
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun NavGraphBuilder.bookReaderDestination(onReaderActiveChanged: (Boolean) -> Unit) {
    composable<Route.Book.Reader> { navBackStackEntry ->
        val navController = LocalNavController.current
        DisposableEffect(Unit) {
            onReaderActiveChanged(true)
            onDispose {
                // Replacing a reader from the floating player overlaps both entries during the transition.
                if (navController.currentDestination?.hasRoute<Route.Book.Reader>() != true) onReaderActiveChanged(false)
            }
        }
        val parentEntry = remember(navBackStackEntry) { navController.getBackStackEntry<Route.Book>() }
        // Keep the existing Book-graph lifetime for queued recording writes, while each
        // restored reader entry owns a separate session during navigation transitions.
        val viewModel = hiltViewModel<ReaderViewModel>(parentEntry, key = navBackStackEntry.id)
        val bookmarkModel = hiltViewModel<indi.renakoni.nextvol.ui.book.reader.bookmark.ReaderBookmarksViewModel>(navBackStackEntry)
        val panelModel = hiltViewModel<ReaderSourcePanelViewModel>(navBackStackEntry)
        val speechState by viewModel.readAloud.state.collectAsStateWithLifecycle()
        androidx.lifecycle.compose.LifecycleStartEffect(viewModel) {
            viewModel.setActive(true)
            panelModel.setActive(true)
            onStopOrDispose {
                val retainBrowser = navController.currentBackStackEntry?.id == navBackStackEntry.id
                viewModel.setActive(false, retainBrowser)
                panelModel.setActive(false, retainBrowser)
            }
        }
        DisposableEffect(viewModel, navBackStackEntry) {
            onDispose {
                panelModel.dismiss(null)
                if (navController.currentBackStackEntry?.id != navBackStackEntry.id) viewModel.setActive(false)
            }
        }
        val route = navBackStackEntry.toRoute<Route.Book.Reader>()
        LaunchedEffect(route.bookId, viewModel, panelModel) {
            // Binding follows chapter changes without invalidating the navigation content.
            androidx.compose.runtime.snapshotFlow { viewModel.uiState.contentUiState?.readingChapterId }
                .collect { panelModel.bind(route.bookId, it) }
        }
        val notice = panelModel.notice?.let { stringResource(it) }
        LaunchedEffect(notice) {
            if (notice != null) {
                textToast(navController.context, notice, Toast.LENGTH_LONG).show()
                panelModel.clearNotice()
            }
        }
        if (panelModel.visible) {
            val form = panelModel.form
            if (form != null) indi.renakoni.nextvol.ui.home.settings.sources.SourceLoginDialog(
                form, panelModel.busy,
                onSubmit = { values, action, formId -> panelModel.submit(values, action, formId, viewModel::applySourcePanelRefresh) },
                onCancel = { panelModel.dismiss() }, title = stringResource(R.string.reader_source_panel),
                message = stringResource(R.string.reader_source_panel_boundary),
                showLoginAction = false,
            ) else androidx.compose.material3.AlertDialog(
                onDismissRequest = { panelModel.dismiss() },
                title = { androidx.compose.material3.Text(stringResource(R.string.reader_source_panel)) },
                text = { androidx.compose.material3.CircularProgressIndicator() },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { panelModel.dismiss() }) {
                    androidx.compose.material3.Text(stringResource(android.R.string.cancel))
                } },
            )
        }
        LaunchedEffect(navBackStackEntry) {
            bookmarkModel.open(route.bookId)
            viewModel.openBook(route.bookId, route.chapterId)
        }
        if (viewModel.uiState.bookId == route.bookId) {
            CompositionLocalProvider(LocalReaderBookId provides route.bookId,
                LocalReaderPositionSession provides viewModel.positions) {
                ReaderScreen(
                    readingScreenUiState = viewModel.uiState,
                    onSourcePanel = if (panelModel.available) ({
                        panelModel.bind(route.bookId, viewModel.uiState.contentUiState?.readingChapterId)
                        panelModel.open(viewModel.uiState.contentUiState?.readingProgress ?: 0f)
                    }) else null,
                    sourcePanelVisible = panelModel.visible,
                    settingState = viewModel.readerSettings,
                    fontFamilySettings = viewModel.fontFamilySettings,
                    onClickBackButton = navController::popBackStackIfResumed,
                    updateTotalReadingTime = viewModel::updateTotalReadingTime,
                    accumulateReadTime = viewModel::accumulateReadingTime,
                    onClickPrevChapter = viewModel::prevChapter,
                    onClickNextChapter = viewModel::nextChapter,
                    onChangeChapter = viewModel::changeChapter,
                    onClickThemeSettings = navController::navigateToSettingsThemeDestination,
                    bookmarks = bookmarkModel.bookmarks,
                    bookmarksBusy = bookmarkModel.busy,
                    bookmarkNotice = bookmarkModel.notice,
                    onBookmarkNoticeShown = bookmarkModel::clearNotice,
                    onAddBookmark = bookmarkModel::add,
                    onDeleteBookmark = bookmarkModel::delete,
                    speechState = speechState,
                    onStartReadAloud = viewModel::startReadAloud,
                    onSpeechCommand = viewModel.readAloud::command,
                    onSleepTimer = viewModel.readAloud::setSleepTimer,
                    onSpeechSettings = navController::navigateToSpeechSettings,
                )
            }
        }
    }
    colorPickerDialog()
    imageViewerDialog()
}

fun NavController.navigateToBookReaderDestination(
    bookId: String,
    chapterId: String,
    context: Context,
    includeDetail: Boolean = false,
) {
    if (!isResumed() || currentDestination?.hasRoute<Route.Book.Reader>() == true) return
    val book = BookIdentity.book(bookId)
    val reader = Route.Book.Reader(book.storageKey, BookIdentity.chapter(chapterId, book).storageKey)
    // Check once before both hops: the intermediate detail entry is not resumed yet.
    if (includeDetail) navigate(Route.Book.Detail(book.storageKey))
    navigate(reader)
}

private fun NavGraphBuilder.colorPickerDialog() {
    dialog<Route.Book.ColorPickerDialog> { entry ->
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<ColorPickerDialogViewModel>()
        val route = entry.toRoute<Route.Book.ColorPickerDialog>()
        val selectedColor by viewModel.init(route.colorUserDataPath).collectAsStateWithLifecycle(Color.Unspecified)
        ColorPickerDialog(
            onDismissRequest = { navController.popBackStack() },
            onConfirmation = {
                viewModel.changeBackgroundColor(it)
                navController.popBackStack()
            },
            selectedColor = selectedColor ?: Color.Unspecified,
            colors = route.colors.map { Color(if (it < 0) return@map Color.Unspecified else it) },
            description = stringResource(
                when (route.colorUserDataPath) {
                    UserDataPath.Reader.TextColor.path,
                    UserDataPath.Reader.TextDarkColor.path -> R.string.dialog_color_picker_text_desc
                    else -> R.string.dialog_color_picker_background_desc
                }
            )
        )
    }
}

fun NavController.navigateToColorPickerDialog(colorUserDataPath: String, colors: List<Long>) {
    if (!this.isResumed()) return
    navigate(Route.Book.ColorPickerDialog(colorUserDataPath, colors.toLongArray()))
}
@SuppressLint("LocalContextGetResourceValueCall")
private fun NavGraphBuilder.imageViewerDialog() {
    dialog<Route.Book.ImageViewerDialog>(
        dialogProperties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) { entry ->
        val navController = LocalNavController.current
        val route = entry.toRoute<Route.Book.ImageViewerDialog>()

        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        val savedToPicturesDir = stringResource(R.string.saved_to_pictures_dir, "")
        val saveFailed = stringResource(R.string.save_failed, "")

        val createDocumentLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("image/png")
            ) { targetUri ->
                if (targetUri == null) return@rememberLauncherForActivityResult

                coroutineScope.launch(Dispatchers.IO) {
                    uriToBitmap(
                        imageUri = route.imageUri.toUri(),
                        context = context,
                        bookId = route.bookId,
                        cover = route.cover,
                        chapterId = route.chapterId
                    ).onOk { bitmap ->
                        val result = runCatching {
                            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            } ?: error("Cannot open output stream")
                        }
                        result.onSuccess {
                            withContext(Dispatchers.Main) {
                                textToast(
                                    context,
                                    savedToPicturesDir,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.onFailure {
                            withContext(Dispatchers.Main) {
                                textToast(
                                    context,
                                    saveFailed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }.onErr {
                        withContext(Dispatchers.Main) {
                            textToast(
                                context,
                                saveFailed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

        ImageViewerScreen(
            imageUri = route.imageUri.toUri(),
            onDismissRequest = { navController.popBackStack() },
            onClickSave = {
                coroutineScope.launch(Dispatchers.IO) {
                    uriToBitmap(
                        imageUri = route.imageUri.toUri(),
                        context = context,
                        bookId = route.bookId,
                        cover = route.cover,
                        chapterId = route.chapterId
                    ).onOk {
                        coroutineScope.launch {
                            saveBitmapAsPng(context, it)
                                .onOk { path ->
                                    textToast(
                                        context,
                                        context.getString(R.string.saved_to_pictures_dir, path),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                .onErr {
                                    textToast(
                                        context,
                                        saveFailed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        }
                    }.onErr {
                        Log.d("ImageViewer", "Failed to save image: ${it.message}")
                        textToast(
                            context,
                            context.getString(R.string.save_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onLongClickSave = {
                val defaultName = "lnr_${System.currentTimeMillis()}.png"
                createDocumentLauncher.launch(defaultName)
            },
            bookId = route.bookId,
            cover = route.cover,
            chapterId = route.chapterId
        )
    }
}

fun NavController.navigateToImageViewerDialog(
    imageUri: Uri,
    bookId: String,
    cover: Boolean = false,
    chapterId: String? = null
) {
    navigate(
        Route.Book.ImageViewerDialog(
            imageUri = imageUri.toString(),
            bookId = BookIdentity.bookKey(bookId),
            cover = cover,
            chapterId = chapterId
        )
    )
}
