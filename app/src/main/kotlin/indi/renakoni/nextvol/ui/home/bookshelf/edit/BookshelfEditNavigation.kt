package indi.renakoni.nextvol.ui.home.bookshelf.edit

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import indi.renakoni.nextvol.utils.popBackStackIfResumed
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.bookshelfEditDestination() {
    composable<Route.Main.Bookshelf.Edit> {
        val navController = LocalNavController.current
        val editBookshelfViewModel = hiltViewModel<EditBookshelfViewModel>()
        val edit = it.toRoute<Route.Main.Bookshelf.Edit>()
        EditBookshelfScreen(
            title = edit.title,
            bookshelfId = edit.id,
            bookshelf = editBookshelfViewModel.bookshelf,
            init = editBookshelfViewModel::init,
            onClickBack = navController::popBackStackIfResumed,
            onClickSave = { editBookshelfViewModel.save(edit.id) },
            onClickDelete = editBookshelfViewModel::delete,
            onNameChange = editBookshelfViewModel::onNameChange,
            onSortTypeChange = editBookshelfViewModel::onSortTypeChange,
            onAutoCacheChange = editBookshelfViewModel::onAutoCacheChange,
            onSystemUpdateReminderChange = editBookshelfViewModel::onSystemUpdateReminderChange,
        )
    }
}

fun NavController.navigateToBookshelfEditDestination(id: Int, title: String) {
    navigate(Route.Main.Bookshelf.Edit(id, title))
}
