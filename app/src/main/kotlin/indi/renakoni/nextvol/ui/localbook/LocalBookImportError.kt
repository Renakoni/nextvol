package indi.renakoni.nextvol.ui.localbook

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.localbook.LocalBookImportFailure
import indi.renakoni.nextvol.data.localbook.LocalBookImportReason

@StringRes
internal fun LocalBookImportReason.messageResource(): Int = when (this) {
    LocalBookImportReason.EmptyFile -> R.string.local_import_error_empty
    LocalBookImportReason.UnsupportedFormat -> R.string.local_import_error_format
    LocalBookImportReason.FileAccess -> R.string.local_import_error_file_access
    LocalBookImportReason.Storage -> R.string.local_import_error_storage
    LocalBookImportReason.InvalidEncoding -> R.string.local_import_error_encoding
    LocalBookImportReason.UnsupportedEncoding -> R.string.local_import_error_encoding_unsupported
    LocalBookImportReason.InvalidRule -> R.string.local_import_error_rule
    LocalBookImportReason.RuleTooLong -> R.string.local_import_error_rule_length
    LocalBookImportReason.TooManyChapters -> R.string.local_import_error_chapters
    LocalBookImportReason.NoContent -> R.string.local_import_error_no_content
    LocalBookImportReason.CorruptEpub -> R.string.local_import_error_epub_damaged
    LocalBookImportReason.EncryptedEpub -> R.string.local_import_error_epub_encrypted
    LocalBookImportReason.UnsupportedEpub -> R.string.local_import_error_epub_unsupported
    LocalBookImportReason.EpubLimit -> R.string.local_import_error_epub_limit
    LocalBookImportReason.SessionExpired -> R.string.local_import_error_session
    LocalBookImportReason.InvalidTitle -> R.string.local_import_error_title
    LocalBookImportReason.ShelfChanged -> R.string.local_import_error_shelf
    LocalBookImportReason.Unknown -> R.string.local_import_error_unknown
}

@Composable
internal fun LocalBookImportError(failure: LocalBookImportFailure, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(failure) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.error_24px), null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.local_book_import_failed), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(failure.reason.messageResource()),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.local_import_hide_details else R.string.local_import_show_details))
            }
            if (expanded) SelectionContainer {
                Text(failure.details, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
