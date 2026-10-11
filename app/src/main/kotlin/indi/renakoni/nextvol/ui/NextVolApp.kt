package indi.renakoni.nextvol.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.ui.dialog.UpdatesAvailableDialogViewModel
import indi.renakoni.nextvol.ui.dialog.navigateUpdatesAvailableDialog
import indi.renakoni.nextvol.ui.navigation.NextVolNavHost
import indi.renakoni.nextvol.tts.ReadAloudController
import indi.renakoni.nextvol.ui.tts.navigateToSpeechSettings
import indi.renakoni.nextvol.ui.tts.navigateToReadAloudBook
import indi.renakoni.nextvol.ui.tts.ReadAloudOverlayHost
import indi.renakoni.nextvol.ui.tts.ReadAloudOverlayViewModel
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.ImportUserDataDialog
import indi.renakoni.nextvol.ui.localbook.LocalBookImportDialog
import indi.renakoni.nextvol.ui.localbook.LocalBookImportViewModel
import indi.renakoni.nextvol.utils.textToast
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import kotlinx.coroutines.flow.Flow
import hnovel.imports.sourceImportUrl
import io.nightfish.lightnovelreader.api.Route

@Composable
fun NextVolApp(
    onReaderActiveChanged: (Boolean) -> Unit,
    readerStyle: ReaderStyle,
    intentFlow: Flow<Intent>,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val externalFiles = hiltViewModel<ExternalFileViewModel>()
    val bookImport = hiltViewModel<LocalBookImportViewModel>()
    val speech = hiltViewModel<ReadAloudOverlayViewModel>()
    val playback by speech.controller.state.collectAsStateWithLifecycle()
    val playingBook by speech.book.collectAsStateWithLifecycle()
    val updatesAvailableDialogViewModel = hiltViewModel<UpdatesAvailableDialogViewModel>()
    val available by updatesAvailableDialogViewModel.availableFlow.collectAsStateWithLifecycle(false)
    LaunchedEffect(available) {
        if (available) {
            updatesAvailableDialogViewModel.resetAvailable()
            navController.navigateUpdatesAvailableDialog()
        }
    }
    LaunchedEffect(Unit) {
        intentFlow.collect { intent ->
            if (intent.action == ReadAloudController.OPEN_PLAYER) {
                navController.navigateToSpeechSettings()
                return@collect
            }
            if (intent.action == Intent.ACTION_VIEW) {
                val uri = intent.data ?: return@collect
                if (uri.scheme == "legado") {
                    sourceImportUrl(uri.toString())?.let { navController.navigate(Route.Main.Settings.SourceImport(it)) }
                    return@collect
                }
            }
            externalFiles.open(intent, bookImport.state.visible)
        }
    }
    LaunchedEffect(externalFiles.book) {
        externalFiles.book?.let { file ->
            bookImport.open(file.uri, file.name, file.bookFormat)
            externalFiles.bookOpened()
        }
    }
    LaunchedEffect(externalFiles) {
        externalFiles.messageFlow.collect { textToast(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(bookImport) {
        bookImport.imported.collect { shelf ->
            navController.navigate(Route.Main.Bookshelf.Home) {
                popUpTo<Route.Main.Bookshelf.Home>()
                launchSingleTop = true
            }
            navController.currentBackStackEntry?.savedStateHandle?.set("externalShelf", shelf)
        }
    }
    ReadAloudOverlayHost(playback, speech.controller::command, navController::navigateToReadAloudBook,
        cover = {
            playback.request?.let { request ->
                val book = playingBook?.takeIf { it.id == request.bookId }
                Cover(request.bookId, 40.dp, 40.dp, book?.coverUri ?: Uri.EMPTY,
                    playback.bookTitle, rounded = 20.dp)
            }
        }) {
        NextVolNavHost(
            navController = navController,
            onReaderActiveChanged = onReaderActiveChanged,
            readerStyle = readerStyle
        )
    }
    if (bookImport.state.visible) LocalBookImportDialog(
        state = bookImport.state,
        onDismiss = bookImport::dismiss,
        onTitleChange = bookImport::changeTitle,
        onEncodingChange = bookImport::changeEncoding,
        onRuleChange = bookImport::changeRule,
        onImport = bookImport::confirm,
    )
    if (externalFiles.backupName != null) ImportUserDataDialog(
        isImporting = externalFiles.restoring,
        onDismissRequest = externalFiles::dismissBackup,
        onClickMerge = { externalFiles.restore(false) },
        onClickOverwrite = { externalFiles.restore(true) },
    )
}
