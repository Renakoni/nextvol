package indi.renakoni.nextvol.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.BaseDialog
import indi.renakoni.nextvol.ui.components.CheckBoxListItem
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.ui.LocalNavController

fun NavGraphBuilder.addBookToBookshelfDialog() {
    dialog<Route.AddBookToBookshelfDialog> {
        val navController = LocalNavController.current
        val addToBookshelfDialogViewModel = hiltViewModel<AddToBookshelfDialogViewModel>()
        val route = it.toRoute<Route.AddBookToBookshelfDialog>()
        LaunchedEffect(route.bookId) { addToBookshelfDialogViewModel.bookId = route.bookId }
        addToBookshelfDialogViewModel.navController = navController
        val uiState = addToBookshelfDialogViewModel.addToBookshelfDialogUiState
        AddBookToBookshelfDialog(
            onDismissRequest = addToBookshelfDialogViewModel::onDismissAddToBookshelfRequest,
            onConfirmation = addToBookshelfDialogViewModel::processAddToBookshelfRequest,
            onSelectBookshelf = addToBookshelfDialogViewModel::onSelectBookshelf,
            onDeselectBookshelf = addToBookshelfDialogViewModel::onDeselectBookshelf,
            allBookshelf = uiState.allBookShelf,
            selectedBookshelfIds = uiState.selectedBookshelfIds,
            isLoading = uiState.isLoading,
            isSaving = uiState.isSaving,
            errorMessage = uiState.errorMessage,
        )
    }
}

fun NavController.navigateToAddBookToBookshelfDialog(bookId: String) {
    navigate(Route.AddBookToBookshelfDialog(bookId))
}

@Composable
fun AddBookToBookshelfDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    onSelectBookshelf: (Int) -> Unit,
    onDeselectBookshelf: (Int) -> Unit,
    allBookshelf: List<Bookshelf>,
    selectedBookshelfIds: List<Int>,
    isLoading: Boolean = false,
    isSaving: Boolean = false,
    errorMessage: Int? = null,
) {
    val scrollState = rememberScrollState()
    val enabled = !isLoading && !isSaving
    BaseDialog(
        icon = painterResource(R.drawable.filled_bookmark_24px),
        title = stringResource(R.string.add_to_bookshelf),
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        onConfirmation = onConfirmation,
        dismissText = stringResource(R.string.cancel),
        confirmationText = stringResource(R.string.add_to_bookshelf),
        confirmationEnabled = enabled && allBookshelf.isNotEmpty(),
        dismissEnabled = !isSaving,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 360.dp)
                .verticalScroll(scrollState),
        ) {
            if (isLoading || isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 24.dp),
                )
            } else if (allBookshelf.isEmpty() && errorMessage == null) {
                Text(
                    text = stringResource(R.string.nothing_here),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (allBookshelf.isNotEmpty()) {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                ) {
                    allBookshelf.forEachIndexed { index, bookshelf ->
                        CheckBoxListItem(
                            modifier = Modifier.fillMaxWidth(),
                            title = bookshelf.name,
                            supportingText = stringResource(R.string.bookshelf_book_count, bookshelf.allBookIds.size),
                            checked = selectedBookshelfIds.contains(bookshelf.id),
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                if (checked) onSelectBookshelf(bookshelf.id)
                                else onDeselectBookshelf(bookshelf.id)
                            },
                        )
                        if (index != allBookshelf.lastIndex) {
                            EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }
            errorMessage?.let {
                Text(
                    text = stringResource(it),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
