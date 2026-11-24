package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.bookDescriptionForDisplay
import indi.renakoni.nextvol.data.book.get
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.utils.dateFormatter
import indi.renakoni.nextvol.utils.withHaptic
import io.nightfish.lightnovelreader.api.book.BookInformation
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
fun BookCardContent(
    modifier: Modifier,
    selected: Boolean,
    collected: Boolean,
    bookInformation: BookInformation,
    latestChapterTitle: String? = null,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    titleHeight: Dp? = null,
) {
    val tokens = NextVolDesign.tokens
    val cardRadius = 18.dp
    val containerColor = if (selected) tokens.activePillFill else tokens.cardSurface
    val borderColor = if (selected) colorScheme.primary else tokens.cardBorder

    Box(
        modifier = modifier
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
                onClick = onClick,
                onLongClick = withHaptic { onLongPress() },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp, 122.dp)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                val alpha by animateFloatAsState(
                    targetValue = if (selected) 0.72f else 1f,
                    label = "bookCoverSelectedAlpha",
                )

                Box(modifier = Modifier.graphicsLayer(alpha = alpha)) {
                    Cover(
                        bookId = bookInformation.id,
                        width = 84.dp,
                        height = 122.dp,
                        uri = bookInformation.coverUri,
                        title = bookInformation.title,
                        author = bookInformation.author,
                        rounded = 10.dp,
                    )

                    if (!latestChapterTitle.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(colorScheme.error)
                                .border(1.5.dp, colorScheme.surface, CircleShape),
                        )
                    }

                    if (collected) {
                        Box(
                            modifier = Modifier
                                .padding(5.dp)
                                .align(Alignment.TopStart)
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colorScheme.primaryContainer.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                modifier = Modifier.size(14.dp),
                                painter = painterResource(R.drawable.filled_bookmark_24px),
                                contentDescription = stringResource(R.string.activity_collections),
                                tint = colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.primary.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primary)
                                .border(2.dp, colorScheme.surface, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                modifier = Modifier.size(18.dp),
                                painter = painterResource(R.drawable.check_24px),
                                tint = colorScheme.onPrimary,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = bookInformation.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.onSurface,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = bookInformation.author,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                        style = typography.labelLarge.copy(
                            fontWeight = FontWeight.W600,
                            color = colorScheme.primary,
                        ),
                    )
                    if (bookInformation.lastUpdated.year > 1970 || bookInformation.isComplete) {
                        BookStatusIcon(bookInformation.isComplete)
                    }
                }

                val dateText = bookInformation.lastUpdated.format(dateFormatter())

                // Unknown reading metadata must not render as a 1970 update and zero-word book.
                if (bookInformation.subtitle.isNotBlank() && bookInformation.lastUpdated.year <= 1970 && bookInformation.wordCount.count == 0L) {
                    Text(
                        text = bookInformation.subtitle,
                        style = typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (bookInformation.lastUpdated.year > 1970) {
                            TagChip(painterResource(R.drawable.update_24px))
                            Text(
                                text = dateText,
                                style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }

                        if (bookInformation.wordCount.count > 0) {
                            Spacer(Modifier.width(2.dp))
                            TagChip(painterResource(R.drawable.article_24px))
                            Text(
                                text = bookInformation.wordCount.get(),
                                style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }

                if (latestChapterTitle.isNullOrBlank()) {
                    val description = remember(bookInformation.id, bookInformation.description) {
                        bookDescriptionForDisplay(bookInformation.id, bookInformation.description).trim()
                    }
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.book_updated_to_title),
                            maxLines = 1,
                            style = typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = latestChapterTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                            color = colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TagChip(
    painter: Painter,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Icon(
            modifier = Modifier.size(13.dp),
            painter = painter,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BookCardContentSkeleton(modifier: Modifier = Modifier) {
    val tokens = NextVolDesign.tokens
    val skeletonColor = colorScheme.surfaceContainerHigh
    val skeletonRoundedCorner = RoundedCornerShape(6.dp)
    val cardRadius = 18.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .squircleBackground(color = tokens.cardSurface, cornerRadius = cardRadius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = cardRadius)
            .squircleClip(cardRadius)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp, 122.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(skeletonColor),
        )

        Column(
            modifier = Modifier
                .height(122.dp)
                .weight(1f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .height(22.dp)
                    .clip(skeletonRoundedCorner)
                    .background(skeletonColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(16.dp)
                    .clip(skeletonRoundedCorner)
                    .background(skeletonColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(16.dp)
                    .clip(skeletonRoundedCorner)
                    .background(skeletonColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .height(30.dp)
                    .clip(skeletonRoundedCorner)
                    .background(skeletonColor),
            )
        }
    }
}

@Composable
fun BookStatusIcon(isComplete: Boolean) {
    val painter: Painter = if (isComplete) {
        painterResource(R.drawable.done_all_24px)
    } else {
        painterResource(R.drawable.hourglass_top_24px)
    }
    Icon(
        modifier = Modifier.size(15.dp),
        painter = painter,
        contentDescription = null,
        tint = colorScheme.onSurfaceVariant,
    )
}
