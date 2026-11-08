package indi.renakoni.nextvol.ui.home.bookshelf.edit

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R

@Composable
fun DeleteBookshelfDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit) {
    AlertDialog(
        title = {
            Text(
                text = stringResource(R.string.dialog_delete_bookshelf),
                style = typography.displayMedium,
                color = colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.dialog_delete_bookshelf_text),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        },
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = onConfirmation
            ) {
                Text(
                    text = stringResource(android.R.string.ok)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(
                    text = stringResource(R.string.cancel)
                )
            }
        }
    )
}
