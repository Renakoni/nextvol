package indi.renakoni.nextvol.ui.home.reading.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.ui.book.reader.ChapterSelectionBottomSheet
import indi.renakoni.nextvol.ui.book.reader.navigateToBookReaderDestination
import indi.renakoni.nextvol.ui.bookmanager.navigateToDownloadManager
import indi.renakoni.nextvol.ui.home.reading.stats.navigateToReadingStatsDestination
import indi.renakoni.nextvol.ui.home.settings.navigateToSettingsDestination
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.readingHomeDestination() {
    composable<Route.Main.Reading.Home> { entry ->
        val navController = LocalNavController.current
        val context = LocalContext.current
        val parentEntry = remember(entry) { navController.getBackStackEntry(Route.Main) }
        val viewModel = hiltViewModel<ReadingHomeViewModel>(parentEntry)
        val chapterSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

        ReadingScreen(
            recentReadingBooks = viewModel.recentReadingBooks,
            onClickDownloadManager = navController::navigateToDownloadManager,
            onClickBook = navController::navigateToBookDetailDestination,
            onClickContinueReading = { bookId, chapterId ->
                navController.navigateToBookReaderDestination(bookId, chapterId, context, includeDetail = true)
            },
            onClickStats = navController::navigateToReadingStatsDestination,
            onRemoveBook = viewModel::removeFromReadingList,
            onClickOpenChapters = viewModel::openChapters,
            onAddBook = viewModel::addToReadingList,
            onSettings = navController::navigateToSettingsDestination
        )

        viewModel.chapterSheetUiState?.let { chapterSheetUi ->
            val result by chapterSheetUi.bookVolumeFlow.collectAsStateWithLifecycle(null)
            result?.onOk {
                ChapterSelectionBottomSheet(
                    sheetState = chapterSheetState,
                    selectedVolumeId = chapterSheetUi.selectedVolumeId,
                    bookVolumes = it,
                    readingChapterId = chapterSheetUi.readingChapterId,
                    onDismissRequest = viewModel::closeContents,
                    onClickChapter = { chapterId ->
                        navController.navigateToBookReaderDestination(
                            chapterSheetUi.bookId,
                            chapterId,
                            context,
                            includeDetail = true,
                        )
                        viewModel.closeContents()
                    },
                    onChangeSelectedVolumeId = viewModel::setVolume
                )
            }?.onErr {
                //TODO 错误显示
            } ?: {
                //TODO 加载显示
            }
        }
    }
}
