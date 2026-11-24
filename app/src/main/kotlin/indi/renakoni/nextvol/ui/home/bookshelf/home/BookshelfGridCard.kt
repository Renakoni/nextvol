package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.valentinilk.shimmer.Shimmer
import com.valentinilk.shimmer.shimmer
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfBookItem
import indi.renakoni.nextvol.utils.withHaptic
import io.nightfish.lightnovelreader.api.error.WebRequestError
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun BookshelfGridCard(
    id: String,
    result: Result<BookshelfBookItem, WebRequestError>?,
    selected: Boolean,
    selectMode: Boolean,
    shimmer: Shimmer?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val item = result?.get()
    val colors = MaterialTheme.colorScheme
    val tokens = NextVolDesign.tokens
    val cardRadius = 16.dp
    val containerColor = if (selected) tokens.activePillFill else tokens.cardSurface
    val borderColor = if (selected) colors.primary else tokens.cardBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleBackground(color = containerColor, cornerRadius = cardRadius)
            .squircleBorder(
                width = if (selected) 1.4.dp else 0.75.dp,
                color = borderColor,
                cornerRadius = cardRadius,
            )
            .squircleClip(cardRadius)
            .semantics { this.selected = selected }
            .combinedClickable(
                enabled = result != null || selectMode,
                onClick = onClick,
                onLongClick = withHaptic { onLongPress() },
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val coverWidth = maxWidth
            val coverHeight = coverWidth * (144f / 98f)
            val coverShape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .size(coverWidth, coverHeight)
                    .shadow(
                        elevation = 4.dp,
                        shape = coverShape,
                        ambientColor = tokens.glassShadow,
                        spotColor = tokens.glassShadow,
                        clip = false,
                    )
                    .clip(coverShape),
            ) {
                if (item != null) {
                    Cover(
                        bookId = id,
                        width = coverWidth,
                        height = coverHeight,
                        uri = item.bookInformation.coverUri,
                        title = item.bookInformation.title,
                        rounded = 10.dp,
                        author = item.bookInformation.author,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (result == null && shimmer != null) Modifier.shimmer(shimmer) else Modifier)
                            .background(colors.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (result == null) R.drawable.outline_bookmark_24px else R.drawable.error_24px,
                            ),
                            contentDescription = null,
                            tint = if (result == null) colors.onSurfaceVariant.copy(alpha = 0.6f) else colors.error,
                        )
                    }
                }

                if (!item?.lastUpdatedChapterTitle.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors.error)
                            .border(1.5.dp, colors.surface, CircleShape),
                    )
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colors.primary.copy(alpha = 0.24f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(colors.primary)
                                .border(2.dp, colors.surface, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.check_24px),
                                contentDescription = null,
                                tint = colors.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = item?.bookInformation?.title
                ?: if (result == null) "" else stringResource(R.string.bookshelf_layout_load_error),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
            color = colors.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val subtitle = item?.lastUpdatedChapterTitle?.takeIf { it.isNotBlank() } ?: item?.bookInformation?.author
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (!item?.lastUpdatedChapterTitle.isNullOrBlank()) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun BookshelfBookError(
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tokens = NextVolDesign.tokens
    val cardRadius = 18.dp
    val containerColor = if (selected) tokens.activePillFill else tokens.cardSurface
    val borderColor = if (selected) colors.primary else tokens.cardBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .squircleBackground(color = containerColor, cornerRadius = cardRadius)
            .squircleBorder(
                width = if (selected) 1.4.dp else 0.75.dp,
                color = borderColor,
                cornerRadius = cardRadius,
            )
            .squircleClip(cardRadius)
            .semantics { this.selected = selected }
            .combinedClickable(
                onClick = onClick,
                onLongClick = withHaptic { onLongPress() },
            )
            .padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorialIconBadge(
            painter = painterResource(R.drawable.error_24px),
            size = 40.dp,
            iconSize = 20.dp,
            cornerRadius = 12.dp,
            containerColor = colors.errorContainer.copy(alpha = 0.72f),
            contentColor = colors.error,
        )
        Text(
            text = stringResource(R.string.bookshelf_layout_load_error),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
            color = colors.onSurface,
        )
    }
}
