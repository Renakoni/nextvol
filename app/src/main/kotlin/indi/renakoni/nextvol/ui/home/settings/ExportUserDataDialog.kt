package indi.renakoni.nextvol.ui.home.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.BaseDialog
import indi.renakoni.nextvol.ui.components.CheckBoxListItem
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider

@Composable
fun ExportUserDataDialog(
    onDismissRequest: () -> Unit,
    onClickSaveAndSend: (ExportContext) -> Unit,
    onClickSaveToFile: (ExportContext) -> Unit,
) {
    val mutableExportContext = remember { MutableExportContext() }
    val listItemModifier = Modifier
        .sizeIn(minWidth = 260.dp, maxWidth = 500.dp)
        .fillMaxWidth()
        .padding(horizontal = 6.dp)

    BaseDialog(
        icon = painterResource(R.drawable.output_24px),
        title = stringResource(R.string.settings_snap_data),
        description = stringResource(R.string.dialog_snap_user_data_text),
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(maxHeight = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EditorialCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
            ) {
                CheckBoxListItem(
                    modifier = listItemModifier,
                    title = stringResource(R.string.dialog_snap_local_book_cache),
                    supportingText = stringResource(R.string.dialog_snap_local_book_cache_text),
                    checked = mutableExportContext.localBookCache,
                    onCheckedChange = { mutableExportContext.localBookCache = it },
                )
                EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                CheckBoxListItem(
                    modifier = listItemModifier,
                    title = stringResource(R.string.dialog_snap_bookshelf),
                    supportingText = stringResource(R.string.dialog_snap_bookshelf_text),
                    checked = mutableExportContext.bookshelf,
                    onCheckedChange = { mutableExportContext.bookshelf = it },
                )
                EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                CheckBoxListItem(
                    modifier = listItemModifier,
                    title = stringResource(R.string.dialog_snap_reading_data),
                    supportingText = stringResource(R.string.dialog_snap_reading_data_text),
                    checked = mutableExportContext.readingData,
                    onCheckedChange = { mutableExportContext.readingData = it },
                )
                EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                CheckBoxListItem(
                    modifier = listItemModifier,
                    title = stringResource(R.string.dialog_snap_settings),
                    supportingText = stringResource(R.string.dialog_snap_settings_text),
                    checked = mutableExportContext.settings,
                    onCheckedChange = { mutableExportContext.settings = it },
                )
                EditorialHairlineDivider(Modifier.padding(horizontal = 14.dp))
                CheckBoxListItem(
                    modifier = listItemModifier,
                    title = stringResource(R.string.reader_bookmarks_title),
                    supportingText = stringResource(R.string.reader_bookmarks_export_description),
                    checked = mutableExportContext.bookmark,
                    onCheckedChange = { mutableExportContext.bookmark = it },
                )
            }
            EditorialCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 14.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.local_file_backup_scope),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onDismissRequest,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = stringResource(R.string.cancel))
            }
            FilledTonalButton(
                onClick = { onClickSaveAndSend(mutableExportContext) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.export_and_share),
                    fontWeight = FontWeight.W600,
                )
            }
            Button(
                onClick = { onClickSaveToFile(mutableExportContext) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.export_to_file),
                    fontWeight = FontWeight.W600,
                )
            }
        }
    }
}
