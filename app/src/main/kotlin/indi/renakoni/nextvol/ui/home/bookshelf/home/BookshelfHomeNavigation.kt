package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.animation.SharedTransitionScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.ExternalFileViewModel
import indi.renakoni.nextvol.ui.book.detail.navigateToBookDetailDestination
import indi.renakoni.nextvol.ui.dialog.AddBookToBookshelfDialog
import indi.renakoni.nextvol.ui.home.bookshelf.edit.navigateToBookshelfEditDestination
import indi.renakoni.nextvol.ui.home.settings.navigateToSettingsDestination
import indi.renakoni.nextvol.ui.localbook.LocalBookImportViewModel
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.bookshelfHomeDestination(sharedTransitionScope: SharedTransitionScope) {
    composable<Route.Main.Bookshelf.Home> {
        val navController = LocalNavController.current
        val parentEntry = remember(it) { navController.getBackStackEntry(Route.Main) }
        val bookshelfHomeViewModel = hiltViewModel<BookshelfHomeViewModel>(parentEntry)
        val entry = it
        LaunchedEffect(entry) {
            entry.savedStateHandle.getStateFlow<Int?>("externalShelf", null).collect { shelf ->
                if (shelf != null) {
                    snapshotFlow { bookshelfHomeViewModel.uiState.bookshelfList.any { it.id == shelf } }.first { it }
                    bookshelfHomeViewModel.changePage(shelf)
                    entry.savedStateHandle["externalShelf"] = null
                }
            }
        }
        // Share the activity's import session and dialog with external file intents.
        val activity = LocalView.current.context as ComponentActivity
        val importViewModel = hiltViewModel<LocalBookImportViewModel>(activity)
        val externalFiles = hiltViewModel<ExternalFileViewModel>(activity)
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && externalFiles.checkAvailable(importViewModel.state.visible)) {
                importViewModel.open(uri)
            }
        }
        val bookshelfNewTitle = stringResource(R.string.bookshelf_new_title)
        val bookshelfEditTitle = stringResource(R.string.bookshelf_edit_title)
        val uiState = remember(navController, bookshelfHomeViewModel, bookshelfNewTitle, bookshelfEditTitle) {
            object : BookshelfHomeUiState by bookshelfHomeViewModel.uiState {
                override val enableReorderMode: () -> Unit = {
                    navController.navigate(Route.Main.Bookshelf.ReorderBooks(bookshelfHomeViewModel.uiState.selectedBookshelfId))
                }
                override val enableBookshelfReorderMode: () -> Unit = {
                    navController.navigate(Route.Main.Bookshelf.ReorderBookshelves)
                }
                override val onCreate: () -> Unit = {
                    navController.navigateToBookshelfEditDestination(-1, bookshelfNewTitle)
                }
                override val onEdit: (Int) -> Unit = { bookshelfId ->
                    navController.navigateToBookshelfEditDestination(bookshelfId, bookshelfEditTitle)
                }
                override val onBookClick: (String) -> Unit = navController::navigateToBookDetailDestination
                override val onRemove: () -> Unit = {
                    bookshelfHomeViewModel.removeSelectedBooks()
                    if (bookshelfHomeViewModel.uiState.selectedBookshelf?.allBookFlows?.isEmpty() == true) {
                        bookshelfHomeViewModel.disableSelectMode()
                    }
                }
                override val onMarkSelectedBooks: () -> Unit = {
                    navController.navigateToAddBookToBookshelfDialog(bookshelfHomeViewModel.uiState.selectedBookIds)
                    bookshelfHomeViewModel.disableSelectMode()
                }
            }
        }
        BookshelfHomeScreen(
            init = bookshelfHomeViewModel::load,
            uiState = uiState,
            onSettings = navController::navigateToSettingsDestination,
            onImportLocalBook = {
                if (externalFiles.checkAvailable(importViewModel.state.visible)) importLauncher.launch(arrayOf("*/*"))
            },
        )
    }

    addBookToBookshelfDialog()
}

@Suppress("unused")
fun NavController.navigateToBookshelfHomeDestination() {
    navigate(Route.Main.Bookshelf.Home)
}

private fun NavGraphBuilder.addBookToBookshelfDialog() {
    dialog<Route.Main.Bookshelf.AddBookToBookshelfDialog> { entry ->
        val navController = LocalNavController.current
        val viewModel = hiltViewModel<AddBookToBookshelfDialogViewModel>()
        val dialogSelectedBookshelves = remember { mutableStateListOf<Int>() }
        val route = entry.toRoute<Route.Main.Bookshelf.AddBookToBookshelfDialog>()
        val allBookshelves by viewModel.allBookshelfFlow.collectAsStateWithLifecycle(emptyList<Bookshelf>())
        AddBookToBookshelfDialog(
            onDismissRequest = { navController.popBackStack() },
            onConfirmation = {
                CoroutineScope(Dispatchers.Main).launch {
                    viewModel.markSelectedBooks(route.selectedBookIds, dialogSelectedBookshelves)
                }
                navController.popBackStack()
            },
            onSelectBookshelf = { dialogSelectedBookshelves.add(it) },
            onDeselectBookshelf = dialogSelectedBookshelves::remove,
            allBookshelf = allBookshelves,
            selectedBookshelfIds = dialogSelectedBookshelves
        )
    }
}

private fun NavController.navigateToAddBookToBookshelfDialog(selectedBookIds: List<String>) {
    navigate(Route.Main.Bookshelf.AddBookToBookshelfDialog(selectedBookIds))
}
