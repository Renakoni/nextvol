package indi.renakoni.nextvol.ui.home.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.BaseDialog
import indi.renakoni.nextvol.ui.components.CheckBoxListItem

@Composable
fun ExportUserDataDialog(
    onDismissRequest: () -> Unit,
    onClickSaveAndSend: (ExportContext) -> Unit,
    onClickSaveToFile: (ExportContext) -> Unit
) {
    val mutableExportContext = remember { MutableExportContext() }
    val listItemModifier = Modifier
        .sizeIn(minWidth = 280.dp, maxWidth = 500.dp)
        .fillMaxWidth()
        .padding(horizontal = 14.dp)
    BaseDialog(
        icon = painterResource(R.drawable.output_24px),
        title = stringResource(R.string.settings_snap_data),
        description = stringResource(R.string.dialog_snap_user_data_text),
        onDismissRequest = onDismissRequest,
    ) {
        Column(Modifier.width(IntrinsicSize.Max).sizeIn(maxHeight = 350.dp).verticalScroll(rememberScrollState())) {
            CheckBoxListItem(
                modifier = listItemModifier,
                title = stringResource(R.string.dialog_snap_local_book_cache),
                supportingText = stringResource(R.string.dialog_snap_local_book_cache_text),
                checked = mutableExportContext.localBookCache,
                onCheckedChange = { mutableExportContext.localBookCache = it }
            )
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            CheckBoxListItem(
                modifier = listItemModifier,
                title = stringResource(R.string.dialog_snap_bookshelf),
                supportingText = stringResource(R.string.dialog_snap_bookshelf_text),
                checked = mutableExportContext.bookshelf,
                onCheckedChange = { mutableExportContext.bookshelf = it }
            )
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            CheckBoxListItem(
                modifier = listItemModifier,
                title = stringResource(R.string.dialog_snap_reading_data),
                supportingText = stringResource(R.string.dialog_snap_reading_data_text),
                checked = mutableExportContext.readingData,
                onCheckedChange = { mutableExportContext.readingData = it }
            )
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            CheckBoxListItem(
                modifier = listItemModifier,
                title = stringResource(R.string.dialog_snap_settings),
                supportingText = stringResource(R.string.dialog_snap_settings_text),
                checked = mutableExportContext.settings,
                onCheckedChange = { mutableExportContext.settings = it }
            )
            Text(stringResource(R.string.local_file_backup_scope), Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                style = typography.bodyMedium, color = colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
            CheckBoxListItem(
                modifier = listItemModifier,
                title = stringResource(R.string.reader_bookmarks_title),
                supportingText = stringResource(R.string.reader_bookmarks_export_description),
                checked = mutableExportContext.bookmark,
                onCheckedChange = { mutableExportContext.bookmark = it }
            )
            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
        }
        Row(
            modifier = Modifier
                .padding(8.dp, 24.dp, 24.dp, 24.dp)
                .align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = typography.labelMedium,
                    color = colorScheme.primary,
                )
            }
            TextButton(
                onClick = { onClickSaveAndSend(mutableExportContext) }
            ) {
                Text(
                    text = stringResource(R.string.export_and_share),
                    style = typography.labelMedium,
                    color = colorScheme.primary,
                )
            }
            TextButton(
                onClick = { onClickSaveToFile(mutableExportContext) }
            ) {
                Text(
                    text = stringResource(R.string.export_to_file),
                    style = typography.labelMedium,
                    color = colorScheme.primary,
                )
            }
        }
    }
}
