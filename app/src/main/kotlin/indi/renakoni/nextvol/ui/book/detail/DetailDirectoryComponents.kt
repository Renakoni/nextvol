package indi.renakoni.nextvol.ui.book.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun DirectoryVolumeHeader(
    volumeId: String,
    title: String,
    readCount: Int,
    chapterCount: Int,
    pageRange: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "volumeArrowRotation",
    )
    val fullyRead = readCount >= chapterCount && chapterCount > 0
    val progress = if (fullyRead) {
        stringResource(R.string.info_reading_finished)
    } else {
        stringResource(R.string.info_reading_progress, readCount, chapterCount)
    }
    val radius = 16.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("directory-volume:$volumeId")
            .squircleBackground(color = tokens.cardSurface, cornerRadius = radius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = radius)
            .squircleClip(radius)
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand),
                role = Role.Button,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = typography.titleMedium.copy(fontWeight = FontWeight.W600),
                color = if (fullyRead) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (pageRange == null) progress else "$progress · $pageRange",
                style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = if (fullyRead) colorScheme.primary else colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward_ios_24px),
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
        )
    }
}

@Composable
internal fun DirectoryToolbar(
    chapterCount: Int,
    page: Int,
    descending: Boolean,
    hideRead: Boolean,
    selecting: Boolean,
    canLocate: Boolean,
    searchExpanded: Boolean,
    query: String,
    matchCount: Int?,
    onPageChange: (Int) -> Unit,
    onToggleOrder: () -> Unit,
    onToggleHideRead: () -> Unit,
    onLocate: () -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val showPages = query.isBlank() && directoryPageCount(chapterCount) > 1
    val matchesText = if (matchCount != null) {
        pluralStringResource(R.plurals.reader_directory_matches, matchCount, matchCount)
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.detail_contents),
                    style = typography.displayMedium.copy(fontWeight = FontWeight.W700),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.cardSurface)
                        .border(0.75.dp, tokens.cardBorder, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.info_volume_chapters_count, chapterCount),
                        style = typography.labelSmall.copy(
                            fontWeight = FontWeight.W600,
                            fontFeatureSettings = "tnum",
                        ),
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            EditorialIconButton(
                onClick = onToggleOrder,
                enabled = chapterCount > 0,
                painter = painterResource(R.drawable.sort_24px),
                contentDescription = stringResource(
                    if (descending) R.string.detail_directory_descending else R.string.detail_directory_ascending,
                ),
                containerColor = if (descending) tokens.activePillFill else tokens.cardSurface,
                contentColor = if (descending) colorScheme.primary else colorScheme.onSurface,
                modifier = Modifier.rotate(if (descending) 0f else 180f),
            )
            EditorialIconButton(
                onClick = onToggleSearch,
                enabled = chapterCount > 0,
                painter = painterResource(if (searchExpanded) R.drawable.close_24px else R.drawable.search_24px),
                contentDescription = stringResource(
                    if (searchExpanded) R.string.reader_directory_clear_search else R.string.detail_directory_search,
                ),
                containerColor = if (searchExpanded) tokens.activePillFill else tokens.cardSurface,
                contentColor = if (searchExpanded) colorScheme.primary else colorScheme.onSurface,
            )
        }

        val hasDirectControls = chapterCount > 0 && (canLocate || !selecting || showPages || matchesText != null)
        if (hasDirectControls) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (canLocate) {
                        DirectoryActionPill(
                            text = stringResource(R.string.detail_directory_locate),
                            iconRes = R.drawable.target_24px,
                            selected = false,
                            onClick = onLocate,
                        )
                    }
                    if (!selecting) {
                        DirectoryActionPill(
                            text = stringResource(R.string.hide_read),
                            iconRes = if (hideRead) R.drawable.check_24px else null,
                            selected = hideRead,
                            onClick = onToggleHideRead,
                        )
                    }
                    if (matchesText != null) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .clip(CircleShape)
                                .background(tokens.cardElevatedSurface)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = matchesText,
                                style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (showPages) {
                    DirectoryRangeMenu(
                        chapterCount = chapterCount,
                        page = page,
                        descending = descending,
                        onPageChange = onPageChange,
                    )
                }
            }
        }

        if (searchExpanded) {
            val focus = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            LaunchedEffect(Unit) { focus.requestFocus() }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.75.dp, tokens.cardBorder, CircleShape)
                    .focusRequester(focus),
                placeholder = {
                    Text(
                        text = stringResource(R.string.reader_directory_search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                painter = painterResource(R.drawable.close_24px),
                                contentDescription = stringResource(R.string.reader_directory_clear_search),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                shape = CircleShape,
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = tokens.cardSurface,
                    unfocusedContainerColor = tokens.cardSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
        }
    }
}

@Composable
private fun DirectoryActionPill(
    text: String,
    iconRes: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val bg by animateColorAsState(
        targetValue = if (selected) tokens.activePillFill else tokens.cardSurface,
        animationSpec = tween(180),
        label = "dirPillBg",
    )
    val border by animateColorAsState(
        targetValue = if (selected) tokens.activePillBorder else tokens.cardBorder,
        animationSpec = tween(180),
        label = "dirPillBorder",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "dirPillFg",
    )

    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(CircleShape)
            .background(bg)
            .border(if (selected) 1.dp else 0.75.dp, border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = text,
            style = typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
            ),
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
internal fun DirectoryPageNavigation(
    chapterCount: Int,
    page: Int,
    descending: Boolean,
    onPageChange: (Int) -> Unit,
) {
    val pageCount = directoryPageCount(chapterCount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .testTag("directory-pagination"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        EditorialIconButton(
            onClick = { onPageChange(page - 1) },
            enabled = page > 0,
            painter = painterResource(R.drawable.arrow_back_24px),
            contentDescription = stringResource(R.string.detail_directory_previous),
        )
        DirectoryRangeMenu(
            chapterCount = chapterCount,
            page = page,
            descending = descending,
            onPageChange = onPageChange,
        )
        EditorialIconButton(
            onClick = { onPageChange(page + 1) },
            enabled = page < pageCount - 1,
            painter = painterResource(R.drawable.arrow_forward_24px),
            contentDescription = stringResource(R.string.detail_directory_next),
        )
    }
}

@Composable
private fun DirectoryRangeMenu(
    chapterCount: Int,
    page: Int,
    descending: Boolean,
    onPageChange: (Int) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var expanded by remember { mutableStateOf(false) }
    val range = directoryPageRange(chapterCount, page, descending)
    val pageCount = directoryPageCount(chapterCount)
    Box {
        val rangeDescription = stringResource(
            R.string.detail_directory_range_summary,
            range.first + 1,
            range.last + 1,
            chapterCount,
        )
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(CircleShape)
                .background(tokens.cardSurface)
                .border(0.75.dp, tokens.cardBorder, CircleShape)
                .clickable(onClick = { expanded = true })
                .semantics {
                    role = Role.Button
                    contentDescription = rangeDescription
                }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_directory_range, range.first + 1, range.last + 1),
                style = typography.labelMedium.copy(
                    fontWeight = FontWeight.W600,
                    fontFeatureSettings = "tnum",
                ),
                color = colorScheme.onSurface,
                maxLines = 1,
            )
            Icon(
                painter = painterResource(R.drawable.search_expand_24px),
                contentDescription = stringResource(R.string.detail_directory_ranges),
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        EditorialPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            for (index in 0 until pageCount) {
                val entryRange = directoryPageRange(chapterCount, index, descending)
                EditorialPopupMenuItem(
                    text = stringResource(R.string.detail_directory_range, entryRange.first + 1, entryRange.last + 1),
                    selected = index == page,
                    modifier = Modifier.semantics { selected = index == page },
                    onClick = {
                        expanded = false
                        onPageChange(index)
                    },
                )
            }
        }
    }
}
