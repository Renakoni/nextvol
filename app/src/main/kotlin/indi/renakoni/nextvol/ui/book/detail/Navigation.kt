package indi.renakoni.nextvol.ui.book.detail

import indi.renakoni.nextvol.data.book.availableVolumes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.ui.localbook.LocalBookImportDialog
import indi.renakoni.nextvol.ui.localbook.LocalBookImportState
import indi.renakoni.nextvol.ui.localbook.LocalBookRelinkViewModel
import android.annotation.SuppressLint
import android.widget.Toast
import indi.renakoni.nextvol.utils.textToast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.book.reader.navigateToBookReaderDestination
import indi.renakoni.nextvol.ui.book.reader.navigateToImageViewerDialog
import indi.renakoni.nextvol.ui.book.download.navigateToBookDownload
import indi.renakoni.nextvol.ui.dialog.navigateToAddBookToBookshelfDialog
import indi.renakoni.nextvol.ui.dialog.navigateToMarkAllChaptersAsReadDialog
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.launch

@SuppressLint("LocalContextGetResourceValueCall")
fun NavGraphBuilder.bookDetailDestination() {
    composable<Route.Book.Detail> { entry ->
        val navController = LocalNavController.current
        val bookId = BookIdentity.bookKey(entry.toRoute<Route.Book.Detail>().bookId)
        val viewModel = hiltViewModel<DetailViewModel>(entry)
        val blocking = hiltViewModel<PixivBlockingViewModel>(entry)
        PixivBlockingFeedback(blocking)
        PixivBlockConfirmation(blocking)
        androidx.lifecycle.compose.LifecycleStartEffect(viewModel) {
            viewModel.setActive(true)
            onStopOrDispose { viewModel.setActive(false, navController.currentBackStackEntry?.id == entry.id) }
        }
        androidx.compose.runtime.DisposableEffect(viewModel, entry) {
            onDispose { if (navController.currentBackStackEntry?.id != entry.id) viewModel.setActive(false) }
        }
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(bookId, viewModel.uiState.bookInformation, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.uiState.bookInformation?.onOk { blocking.loadBook(BookIdentity.book(it.id)) }
            }
        }
        val exportResult = viewModel.exportResult
        val submissionFailed = viewModel.exportSubmissionFailed
        LaunchedEffect(exportResult, submissionFailed, lifecycleOwner) {
            if (exportResult == null && !submissionFailed) return@LaunchedEffect
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.clearExportResult()
                if (exportResult?.state == WorkInfo.State.SUCCEEDED) {
                    context.startActivity(EpubShareActivity.intent(context, exportResult.id, automatic = true))
                } else {
                    textToast(context, exportResult?.outputData?.getString("message")
                        ?: context.getString(R.string.epub_export_notification_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
        val snackbarHostState = LocalSnackbarHost.current

        LaunchedEffect(bookId) {
            viewModel.init(bookId)
        }
        val relinkViewModel = hiltViewModel<LocalBookRelinkViewModel>(entry)
        val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { relinkViewModel.open(BookIdentity.book(bookId), it) }
        }
        LaunchedEffect(relinkViewModel) {
            relinkViewModel.completed.collect {
                viewModel.retryInformation()
                snackbarHostState.showSnackbar(context.getString(R.string.local_file_relink_success))
            }
        }
        val relinkState = relinkViewModel.state
        if (relinkState.visible) LocalBookImportDialog(
            state = LocalBookImportState(visible = true, busy = relinkState.busy, importing = relinkState.saving,
                fileName = relinkState.fileName, fileBytes = relinkState.fileBytes, format = relinkState.format, bookKey = bookId,
                title = relinkState.preview?.information?.title ?: relinkState.preview?.parsed?.title.orEmpty(),
                encoding = relinkState.encoding, rule = relinkState.rule, preview = relinkState.preview?.parsed,
                error = relinkState.error),
            onDismiss = relinkViewModel::dismiss, onTitleChange = {},
            onEncodingChange = relinkViewModel::changeEncoding, onRuleChange = relinkViewModel::changeRule,
            onImport = relinkViewModel::confirm, relinkState = relinkState,
            onConfirmLegacy = relinkViewModel::confirmLegacy,
        )
        DetailScreen(
            blockingMenu = if (blocking.state.book == null) null else { dismiss -> PixivBlockMenu(blocking, dismiss) },
            localFileMissing = LocalBookStore.isLocal(BookIdentity.book(bookId)) && !viewModel.uiState.readingAvailable,
            onRelink = { relinkPicker.launch(arrayOf("text/plain", "application/epub+zip")) },
            uiState = viewModel.uiState,
            onRetry = viewModel::retryInformation,
            onRetryVolumes = viewModel::retryVolumes,
            onMarkChaptersUnread = viewModel::markChaptersUnread,
            onClickExportToEpub = { settings ->
                viewModel.exportSettings = settings

                viewModel.uiState.bookInformation
                    ?.map { it.title }
                    ?.onOk { title ->
                        viewModel.startEpubExport(bookId, title)
                        textToast(context, context.getString(R.string.export_book_started, title), Toast.LENGTH_SHORT).show()
                    }?.onErr {
                        textToast(context, it.message, Toast.LENGTH_SHORT).show()
                    }
            },
            onClickBackButton = navController::popBackStackIfResumed,
            onClickChapter = {
                navController.navigateToBookReaderDestination(bookId, it, context)
            },
            onClickRead = {
                if (viewModel.uiState.userReadingData?.lastReadChapterId == null) {
                    val result = viewModel.uiState.bookVolumes
                    val firstChapter = result?.availableVolumes()?.volumes
                        ?.firstNotNullOfOrNull { volume -> volume.chapters.firstOrNull()?.id }
                    if (firstChapter != null) navController.navigateToBookReaderDestination(bookId, firstChapter, context)
                    else result?.onErr { textToast(context, it.message, Toast.LENGTH_SHORT).show() }
                }
                else {
                    navController.navigateToBookReaderDestination(bookId, viewModel.uiState.userReadingData!!.lastReadChapterId!!, context)
                }
            },
            cacheBook = { bookId ->
                navController.navigateToBookDownload(bookId)
            },
            requestAddBookToBookshelf = navController::navigateToAddBookToBookshelfDialog,
            onClickTag = { tag ->
                coroutineScope.launch {
                    viewModel.tagPage(tag)?.onOk { page ->
                        if (page != null && navController.isResumed()) navController.navigate(Route.Main.DiscoveryResults(
                            page.sourceId.namespace, page.sourceId.id, page.target, tag, java.util.UUID.randomUUID().toString()))
                    }?.onErr { error ->
                        snackbarHostState.showSnackbar(
                            if (error.kind == WebRequestErrorKind.SourceUnavailable) context.getString(R.string.sources_unavailable)
                            else error.title
                        )
                    }
                }
            },
            onClickCover = { uri -> navController.navigateToImageViewerDialog(uri, bookId, cover = true) },
            onClickMarkAsRead = {
                navController.navigateToMarkAllChaptersAsReadDialog(bookId)
            }
        )
    }
}

fun NavController.navigateToBookDetailDestination(bookId: String) {
    if (!this.isResumed()) return
    navigate(Route.Book.Detail(BookIdentity.bookKey(bookId)))
}

