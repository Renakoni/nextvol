package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
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
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.utils.dateFormatter
import indi.renakoni.nextvol.utils.withHaptic
import io.nightfish.lightnovelreader.api.book.BookInformation

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
    val coverRadius = 7.dp
    val coverShape = RoundedCornerShape(coverRadius)
    val rowBg = if (selected) tokens.activePillFill else Color.Transparent

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBg)
                .semantics { this.selected = selected }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = withHaptic { onLongPress() },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp, 98.dp)
                    .border(
                        width = if (selected) 1.5.dp else 0.5.dp,
                        color = if (selected) colorScheme.primary else colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = coverShape,
                    )
                    .clip(coverShape),
            ) {
                val alpha by animateFloatAsState(
                    targetValue = if (selected) 0.72f else 1f,
                    label = "bookCoverSelectedAlpha",
                )

                Box(modifier = Modifier.graphicsLayer(alpha = alpha)) {
                    Cover(
                        bookId = bookInformation.id,
                        width = 68.dp,
                        height = 98.dp,
                        uri = bookInformation.coverUri,
                        title = bookInformation.title,
                        author = bookInformation.author,
                        rounded = coverRadius,
                    )

                    if (!latestChapterTitle.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(colorScheme.error)
                                .border(1.5.dp, colorScheme.surface, CircleShape),
                        )
                    }

                    if (collected) {
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .align(Alignment.TopStart)
                                .size(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colorScheme.primaryContainer.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                modifier = Modifier.size(13.dp),
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
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primary)
                                .border(1.5.dp, colorScheme.surface, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                modifier = Modifier.size(16.dp),
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = bookInformation.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.onSurface,
                )

                val metaParts = buildList {
                    if (bookInformation.author.isNotBlank()) add(bookInformation.author)
                    if (bookInformation.subtitle.isNotBlank() && bookInformation.lastUpdated.year <= 1970 && bookInformation.wordCount.count == 0L) {
                        add(bookInformation.subtitle)
                    } else {
                        if (bookInformation.lastUpdated.year > 1970) {
                            add(bookInformation.lastUpdated.format(dateFormatter()))
                        }
                        if (bookInformation.wordCount.count > 0) {
                            add(bookInformation.wordCount.get())
                        }
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                            style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = colorScheme.onSurfaceVariant,
                        )
                        if (bookInformation.lastUpdated.year > 1970 || bookInformation.isComplete) {
                            BookStatusIcon(bookInformation.isComplete)
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
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
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
        EditorialHairlineDivider(Modifier.padding(start = 98.dp, end = 16.dp))
    }
}

@Composable
fun TagChip(
    painter: Painter,
) {
    Icon(
        modifier = Modifier.size(14.dp),
        painter = painter,
        contentDescription = null,
        tint = colorScheme.onSurfaceVariant,
    )
}

@Composable
fun BookCardContentSkeleton(modifier: Modifier = Modifier) {
    val skeletonColor = colorScheme.surfaceContainerHigh
    val skeletonRoundedCorner = RoundedCornerShape(6.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp, 98.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(skeletonColor),
            )

            Column(
                modifier = Modifier
                    .height(98.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .height(18.dp)
                        .clip(skeletonRoundedCorner)
                        .background(skeletonColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.48f)
                        .height(14.dp)
                        .clip(skeletonRoundedCorner)
                        .background(skeletonColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(26.dp)
                        .clip(skeletonRoundedCorner)
                        .background(skeletonColor),
                )
            }
        }
        EditorialHairlineDivider(Modifier.padding(start = 98.dp, end = 16.dp))
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
        modifier = Modifier.size(14.dp),
        painter = painter,
        contentDescription = null,
        tint = colorScheme.onSurfaceVariant,
    )
}
