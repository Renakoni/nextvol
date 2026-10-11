package indi.renakoni.nextvol.ui.book.download

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.ui.components.downloadSubmissionText
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.isResumed
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.serialization.Serializable

@Serializable
data class BookDownloadRoute(val bookId: String)

fun NavGraphBuilder.bookDownload() {
    composable<BookDownloadRoute> {
        val viewModel = hiltViewModel<BookDownloadViewModel>()
        val navController = LocalNavController.current
        val snackbar = LocalSnackbarHost.current
        val context = LocalContext.current
        LaunchedEffect(viewModel) { viewModel.submissions.collect {
            snackbar.showSnackbar(context.downloadSubmissionText(it), withDismissAction = true)
        } }
        BookDownloadScreen(viewModel.state, navController::popBackStackIfResumed, viewModel::loadDirectory,
            viewModel::select, { viewModel.submit() }, { viewModel.submit(resume = true) }, viewModel::cancel,
            onOpenStorage = { navController.navigate(io.nightfish.lightnovelreader.api.Route.StorageManager) })
    }
}

fun NavController.navigateToBookDownload(bookId: String) {
    if (isResumed()) navigate(BookDownloadRoute(BookIdentity.bookKey(bookId)))
}
