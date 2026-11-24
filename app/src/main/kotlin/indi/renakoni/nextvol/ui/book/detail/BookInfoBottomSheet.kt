package indi.renakoni.nextvol.ui.book.detail

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.get
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.utils.dateFormatter
import indi.renakoni.nextvol.utils.textToast
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import kotlinx.coroutines.launch

private data class BookInfoEntry(
    val title: String,
    val content: String,
    val iconRes: Int,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookInfoBottomSheet(
    bookInformation: BookInformation,
    bookVolumes: BookVolumes?,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val copiedText = stringResource(R.string.copied_to_clipboard)

    val titleValue = listOf(bookInformation.title, bookInformation.subtitle)
        .filter { it.isNotBlank() }
        .joinToString("\n")
    // Only lnr1.* keys identify their source unambiguously. Under the no-migration policy,
    // older bare keys use the fixed Wenku8 bridge; this display cannot recover the actual
    // owner of an ambiguous historical key.
    val remoteId = bookInformation.id.let { if (it.isEmpty()) "" else BookIdentity.book(it).remoteId }
    val updatedText = if (bookVolumes != null && bookInformation.lastUpdated.year > 1970) {
        bookInformation.lastUpdated.format(dateFormatter()) + " · " +
            if (bookInformation.isComplete) stringResource(R.string.book_completed)
            else stringResource(R.string.book_ongoing)
    } else {
        ""
    }
    val tagsText = bookInformation.tags.filter { it.isNotBlank() }.joinToString(separator = "，")
    val statsText = if (bookVolumes != null) {
        listOfNotNull(
            if (bookInformation.wordCount.count > 0) bookInformation.wordCount.get() else null,
            stringResource(
                R.string.detail_info_stats_count_content,
                bookVolumes.volumes.count(),
                bookVolumes.volumes.sumOf { it.chapters.size },
            ),
        ).joinToString("\n")
    } else {
        ""
    }

    val entries = listOf(
        BookInfoEntry(stringResource(R.string.detail_info_title), titleValue, R.drawable.title_24px),
        BookInfoEntry(stringResource(R.string.detail_info_id), remoteId, R.drawable.info_24px),
        BookInfoEntry(stringResource(R.string.detail_info_author), bookInformation.author, R.drawable.person_edit_24px),
        BookInfoEntry(stringResource(R.string.detail_info_publishing_house), bookInformation.publishingHouse, R.drawable.text_snippet_24px),
        BookInfoEntry(stringResource(R.string.detail_info_updated_on), updatedText, R.drawable.autorenew_24px),
        BookInfoEntry(stringResource(R.string.detail_info_tags), tagsText, R.drawable.tag_24px),
        BookInfoEntry(stringResource(R.string.detail_info_stats), statsText, R.drawable.text_fields_24px),
    ).filter { it.content.isNotBlank() }

    val copyValue: (String, String) -> Unit = { label, value ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        coroutineScope.launch {
            val clipData = ClipData.newPlainText(label, value)
            clipboard.setClipEntry(ClipEntry(clipData = clipData))
            textToast(context, copiedText, Toast.LENGTH_SHORT).show()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.action_show_info),
                style = typography.displayMedium.copy(fontWeight = FontWeight.W600),
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            EditorialHairlineDivider(Modifier.padding(top = 6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    entries.forEachIndexed { index, entry ->
                        BookInfoRow(
                            title = entry.title,
                            content = entry.content,
                            icon = painterResource(entry.iconRes),
                            onCopy = { copyValue(entry.title, entry.content) },
                        )
                        if (index != entries.lastIndex) {
                            EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoRow(
    title: String,
    content: String,
    icon: Painter,
    onCopy: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onCopy,
                onLongClick = onCopy,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EditorialIconBadge(
            painter = icon,
            size = 34.dp,
            iconSize = 18.dp,
            cornerRadius = 10.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                color = colors.onSurfaceVariant,
            )
            Text(
                text = content,
                style = typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = colors.onSurface,
            )
        }
    }
}
