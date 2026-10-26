package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.valentinilk.shimmer.shimmer
import com.valentinilk.shimmer.Shimmer
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfBookItem
import indi.renakoni.nextvol.utils.withHaptic
import io.nightfish.lightnovelreader.api.error.WebRequestError

@Composable
internal fun BookshelfGridCard(
    id: String,
    result: Result<BookshelfBookItem, WebRequestError>?,
    selected: Boolean,
    selectMode: Boolean,
    shimmer: Shimmer?,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val item = result?.get()
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .semantics { this.selected = selected }
            .combinedClickable(
                enabled = result != null || selectMode,
                onClick = onClick,
                onLongClick = withHaptic { onLongPress() }
            )
            .padding(4.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverWidth = maxWidth
            val coverHeight = coverWidth * (144f / 94f)
            Box(Modifier.size(coverWidth, coverHeight).clip(RoundedCornerShape(8.dp))) {
                if (item != null) {
                    Cover(id, coverWidth, coverHeight, item.bookInformation.coverUri, item.bookInformation.title, author = item.bookInformation.author)
                } else {
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (result == null && shimmer != null) Modifier.shimmer(shimmer) else Modifier)
                            .background(colors.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.outline_bookmark_24px), contentDescription = null)
                    }
                }
                if (!item?.lastUpdatedChapterTitle.isNullOrBlank()) {
                    Badge(Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp), containerColor = colors.error)
                }
                if (selected) {
                    Box(Modifier.fillMaxSize().background(colors.secondaryContainer.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.check_24px), contentDescription = null,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(colors.primary).padding(6.dp),
                            tint = colors.onPrimary
                        )
                    }
                }
            }
        }
        Text(
            text = item?.bookInformation?.title ?: if (result == null) "" else stringResource(R.string.bookshelf_layout_load_error),
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item != null) {
            Text(
                text = item.lastUpdatedChapterTitle ?: item.bookInformation.author,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun BookshelfBookError(selected: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .heightIn(min = 146.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = withHaptic { onLongPress() })
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.bookshelf_layout_load_error))
    }
}
