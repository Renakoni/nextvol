package indi.renakoni.nextvol.ui.home.reading.home

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.ui.book.reader.navigateToBookReaderDestination
import indi.renakoni.nextvol.ui.bookmanager.navigateToDownloadManager
import indi.renakoni.nextvol.ui.home.reading.stats.navigateToReadingStatsDestination
import indi.renakoni.nextvol.ui.home.settings.navigateToSettingsDestination
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.readingHomeDestination() {
    composable<Route.Main.Reading.Home> { entry ->
        val navController = LocalNavController.current
        val context = LocalContext.current
        val parentEntry = remember(entry) { navController.getBackStackEntry(Route.Main) }
        val viewModel = hiltViewModel<ReadingHomeViewModel>(parentEntry)

        ReadingScreen(
            recentReadingBooks = viewModel.recentReadingBooks,
            readingListLoading = viewModel.readingListLoading,
            onClickDownloadManager = navController::navigateToDownloadManager,
            onClickBook = navController::navigateToBookDetailDestination,
            onClickContinueReading = { bookId, chapterId ->
                navController.navigateToBookReaderDestination(bookId, chapterId, context, includeDetail = true)
            },
            onClickStats = navController::navigateToReadingStatsDestination,
            onRemoveBook = viewModel::removeFromReadingList,
            onAddBook = viewModel::addToReadingList,
            onSettings = navController::navigateToSettingsDestination,
        )
    }
}
