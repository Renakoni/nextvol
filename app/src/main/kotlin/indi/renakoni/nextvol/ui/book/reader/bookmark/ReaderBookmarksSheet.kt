package indi.renakoni.nextvol.ui.book.reader.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderBookmarksSheet(
    bookmarks: List<ReadingBookmark>,
    busy: Boolean,
    notice: Int? = null,
    onAdd: () -> Unit,
    onJump: (ReadingBookmark) -> Unit,
    onDelete: (ReadingBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    var pendingDelete by remember { mutableStateOf<ReadingBookmark?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = colors.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = colors.surface.luminance() > 0.5f,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.reader_bookmarks_title),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                        color = colors.onSurface,
                    )
                    FilledTonalButton(
                        onClick = onAdd,
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.reader_bookmarks_add), fontWeight = FontWeight.W600)
                    }
                }
                Text(
                    text = stringResource(R.string.reader_bookmarks_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (notice != null) {
                    Text(
                        text = stringResource(notice),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                    )
                }
            }
            EditorialHairlineDivider(Modifier.padding(top = 6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (bookmarks.isEmpty()) {
                    item {
                        EmptyPage(
                            icon = painterResource(R.drawable.bookmarks_90px),
                            title = stringResource(R.string.reader_bookmarks_empty),
                            description = "",
                        )
                    }
                }
                items(bookmarks, key = { it.id }) { bookmark ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        onClick = { onJump(bookmark) },
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = bookmark.chapterTitle,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                                        color = colors.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(tokens.activePillFill)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.reader_bookmarks_position,
                                                (bookmark.progress * 100).roundToInt(),
                                            ),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.W600,
                                                fontFeatureSettings = "tnum",
                                            ),
                                            color = colors.primary,
                                        )
                                    }
                                }
                                Text(
                                    text = bookmark.preview.ifBlank { stringResource(R.string.reader_bookmarks_illustration) },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { pendingDelete = bookmark },
                                enabled = !busy,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.delete_forever_24px),
                                    contentDescription = stringResource(R.string.reader_bookmarks_delete, bookmark.chapterTitle),
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        val radius = tokens.sheetRadius
        Dialog(onDismissRequest = { pendingDelete = null }) {
            Column(
                modifier = Modifier
                    .sizeIn(minWidth = 280.dp, maxWidth = 420.dp)
                    .squircleBackground(color = tokens.cardElevatedSurface, cornerRadius = radius)
                    .squircleBorder(width = 0.85.dp, color = tokens.cardBorder, cornerRadius = radius)
                    .squircleClip(radius)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.delete_forever_24px),
                    size = 48.dp,
                    iconSize = 24.dp,
                    cornerRadius = 15.dp,
                    containerColor = colors.errorContainer.copy(alpha = 0.72f),
                    contentColor = colors.error,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.reader_bookmarks_delete, target.chapterTitle),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = { pendingDelete = null },
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(
                        onClick = {
                            onDelete(target)
                            pendingDelete = null
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.confirm), fontWeight = FontWeight.W600)
                    }
                }
            }
        }
    }
}
