package indi.renakoni.nextvol.ui.bookmanager

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.downloadSubmissionText
import indi.renakoni.nextvol.ui.book.download.navigateToBookDownload
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

@Serializable
internal object StoredBooksRoute

fun NavGraphBuilder.bookManager() {
    composable<Route.BookManager> { BookManagerDestination(false) }
    composable<StoredBooksRoute> { BookManagerDestination(true) }
}

@Composable
private fun BookManagerDestination(localContent: Boolean) {
    val navController = LocalNavController.current
    val snackbarHostState = LocalSnackbarHost.current
    val context = LocalContext.current
    val viewModel = hiltViewModel<BookManagerViewModel>()
    val uiState = viewModel.localBookManagerUiState
    val clearedItemsText = stringResource(R.string.book_manager_cleared_items)
    LaunchedEffect(viewModel.clearedItemsFlow) {
        viewModel.clearedItemsFlow.collect { count ->
            snackbarHostState.showSnackbar(
                clearedItemsText.format(count),
                withDismissAction = true
            )
        }
    }
    LaunchedEffect(viewModel.downloadSubmissions) {
        viewModel.downloadSubmissions.collect { result ->
            snackbarHostState.showSnackbar(context.downloadSubmissionText(result), withDismissAction = true)
        }
    }
    uiState.openStorageOverview = {
        navController.navigate(Route.StorageManager)
    }
    uiState.openBookDetailScreen = { id ->
        navController.navigate(Route.Book.Detail(id))
    }
    BookManagerScreen(
        onClickBack = navController::popBackStackIfResumed,
        initialLocalTab = localContent,
        downloadItemIdList = viewModel.downloadItemIdList,
        uiState = uiState,
        onClickCancel = viewModel::onClickCancel,
        onClickRetry = viewModel::onClickRetry,
        onOpenDownload = { navController.navigateToBookDownload(it) },
        onClickClearCompleted = viewModel::onClickClearCompleted
    )
}

fun NavController.navigateToDownloadManager() {
    if (!this.isResumed()) return
    navigate(Route.BookManager)
}
