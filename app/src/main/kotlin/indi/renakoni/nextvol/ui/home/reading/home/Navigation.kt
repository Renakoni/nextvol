package indi.renakoni.nextvol.ui.home.reading.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.ui.book.reader.ChapterSelectionBottomSheet
import indi.renakoni.nextvol.ui.book.reader.navigateToBookReaderDestination
import indi.renakoni.nextvol.ui.bookmanager.navigateToDownloadManager
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
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
            readingListLoading = viewModel.readingListLoading,
            onClickDownloadManager = navController::navigateToDownloadManager,
            onClickBook = navController::navigateToBookDetailDestination,
            onClickContinueReading = { bookId, chapterId ->
                navController.navigateToBookReaderDestination(bookId, chapterId, context, includeDetail = true)
            },
            onClickStats = navController::navigateToReadingStatsDestination,
            onRemoveBook = viewModel::removeFromReadingList,
            onClickOpenChapters = viewModel::openChapters,
            onAddBook = viewModel::addToReadingList,
            onSettings = navController::navigateToSettingsDestination,
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
                    onChangeSelectedVolumeId = viewModel::setVolume,
                )
            }?.onErr {
                val tokens = NextVolDesign.tokens
                ModalBottomSheet(
                    onDismissRequest = viewModel::closeContents,
                    shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
                    containerColor = tokens.cardElevatedSurface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EditorialIconBadge(
                            painter = painterResource(R.drawable.error_24px),
                            size = 48.dp,
                            iconSize = 24.dp,
                            cornerRadius = 15.dp,
                            containerColor = colorScheme.errorContainer.copy(alpha = 0.72f),
                            contentColor = colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.bookshelf_layout_load_error),
                            style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                            color = colorScheme.onSurface,
                        )
                    }
                }
            } ?: run {
                val tokens = NextVolDesign.tokens
                ModalBottomSheet(
                    onDismissRequest = viewModel::closeContents,
                    shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
                    containerColor = tokens.cardElevatedSurface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.detail_contents),
                            style = typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
