package indi.renakoni.nextvol.ui.bookmanager

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.GlassVariant
import indi.renakoni.nextvol.ui.components.design.LiquidGlassSurface
import indi.renakoni.nextvol.ui.home.bookshelf.home.TagChip
import indi.renakoni.nextvol.utils.FileSizeUnit
import indi.renakoni.nextvol.utils.fadeEnter
import indi.renakoni.nextvol.utils.fadeExit
import indi.renakoni.nextvol.utils.formTime
import indi.renakoni.nextvol.utils.formatSize
import indi.renakoni.nextvol.utils.navigationBarSpacer
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBookManagerContent(
    uiState: LocalBookManagerUiState,
) {
    val tokens = NextVolDesign.tokens
    var deleteDialogVisible by remember { mutableStateOf(false) }
    var infoItem by remember { mutableStateOf<LocalBookItem?>(null) }
    var clearItem by remember { mutableStateOf<LocalBookItem?>(null) }
    var clearTargets by remember { mutableStateOf(emptyList<LocalBookClearTarget>()) }
    var confirmClear by remember { mutableStateOf(false) }
    var indexOnlyCollapsed by rememberSaveable { mutableStateOf(true) }
    val localHazeState = rememberHazeState()

    val list = when (uiState.sort) {
        LocalBookSort.Size -> uiState.bookList.sortedBy { it.size }
        LocalBookSort.LastRead -> uiState.bookList.sortedBy { it.lastReadTime }
        LocalBookSort.ChapterCount -> uiState.bookList.sortedBy { it.chapterCount }
    }.let {
        if (uiState.sortReverse) it.reversed() else it
    }
    val contentList = remember(list) { list.filter(LocalBookItem::hasChapterContent) }
    val indexOnlyList = remember(list) { list.filterNot(LocalBookItem::hasChapterContent) }
    val shownInfoItem = remember(uiState.bookList, infoItem) {
        infoItem?.let { current ->
            uiState.bookList.firstOrNull { it.id == current.id } ?: current
        }
    }

    LaunchedEffect(uiState.isSelecting) {
        if (uiState.isSelecting) {
            infoItem = null
            clearItem = null
            clearTargets = emptyList()
            confirmClear = false
        }
    }

    if (uiState.isLoading && uiState.bookList.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(top = 48.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            CircularProgressIndicator()
        }
        return
    }
    if (uiState.bookList.isEmpty()) {
        EmptyPage(
            modifier = Modifier.navigationBarsPadding(),
            icon = painterResource(id = R.drawable.menu_book_24px),
            title = stringResource(R.string.book_manager_empty_title),
            description = stringResource(R.string.book_manager_empty_description),
        )
        return
    }

    if (deleteDialogVisible) {
        val deletesImportedFiles = uiState.selectedIds.any {
            LocalBookStore.isLocal(BookIdentity.book(it))
        }
        AlertDialog(
            onDismissRequest = { deleteDialogVisible = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            title = {
                Text(
                    stringResource(
                        if (deletesImportedFiles) R.string.local_book_delete_title
                        else R.string.book_manager_delete_cache_title,
                    ),
                )
            },
            text = {
                Text(
                    text = stringResource(
                        if (deletesImportedFiles) R.string.local_book_delete_originals
                        else R.string.book_manager_delete_cache_content,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteDialogVisible = false
                        uiState.deleteSelected()
                    },
                    enabled = uiState.selectedIds.isNotEmpty() && !uiState.isDeleting,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        if (uiState.isDeleting) stringResource(R.string.processing)
                        else stringResource(R.string.confirm),
                    )
                }
            },
        )
    }

    clearItem?.let { item ->
        val selectedBytes = clearTargets.sumOf { item.bytesOf(it) }
        val selectedContent = clearTargets.map {
            stringResource(it.label) to formatSize(item.bytesOf(it))
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                shape = RoundedCornerShape(tokens.sheetRadius),
                containerColor = tokens.cardElevatedSurface,
                title = { Text(stringResource(R.string.local_book_clear_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(
                                R.string.local_book_clear_confirm_content,
                                formatSize(selectedBytes),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        EditorialCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                selectedContent.forEach { (label, size) ->
                                    Text(
                                        text = "\u2022 $label ($size)",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFeatureSettings = "tnum",
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            uiState.clearBookData(item.id, clearTargets)
                            infoItem = null
                            clearItem = null
                            clearTargets = emptyList()
                            confirmClear = false
                        },
                        enabled = clearTargets.isNotEmpty(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.local_book_clear))
                    }
                },
            )
        }
    }

    shownInfoItem?.let { item ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                infoItem = null
                clearItem = null
                clearTargets = emptyList()
                confirmClear = false
            },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = tokens.cardBorder)
            },
        ) {
            LocalBookInfoSheetContent(
                item = item,
                isClearing = clearItem?.id == item.id,
                clearTargets = clearTargets,
                onToggleClearTarget = { target ->
                    clearTargets = clearTargets.toMutableList().apply {
                        if (contains(target)) remove(target) else add(target)
                    }
                },
                onEnterClear = {
                    clearItem = item
                    clearTargets = emptyList()
                    confirmClear = false
                },
                onCancelClear = {
                    clearItem = null
                    clearTargets = emptyList()
                    confirmClear = false
                },
                onConfirmClear = { confirmClear = true },
                onOpenDetail = {
                    infoItem = null
                    uiState.openBookDetailScreen(item.id)
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(localHazeState),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = if (uiState.isSelecting) 96.dp else 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                items = contentList,
                key = { "book_${it.id}" },
            ) { item ->
                LocalBookRow(
                    modifier = Modifier.animateItem(),
                    item = item,
                    uiState = uiState,
                    onToggleInfo = {
                        clearItem = null
                        clearTargets = emptyList()
                        confirmClear = false
                        infoItem = if (infoItem?.id == item.id) null else item
                    },
                )
            }
            if (indexOnlyList.isNotEmpty()) {
                stickyHeader(key = "index_only_header") {
                    CollapseHeader(
                        icon = painterResource(R.drawable.menu_book_24px),
                        title = stringResource(R.string.book_manager_group_index_only, indexOnlyList.size),
                        expanded = !indexOnlyCollapsed,
                        onToggleExpand = { indexOnlyCollapsed = !indexOnlyCollapsed },
                    )
                }
                if (!indexOnlyCollapsed) {
                    items(
                        items = indexOnlyList,
                        key = { "index_only_${it.id}" },
                    ) { item ->
                        LocalBookRow(
                            modifier = Modifier.animateItem(),
                            item = item,
                            uiState = uiState,
                            onToggleInfo = {
                                clearItem = null
                                clearTargets = emptyList()
                                confirmClear = false
                                infoItem = if (infoItem?.id == item.id) null else item
                            },
                        )
                    }
                }
            }
            navigationBarSpacer()
        }

        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            visible = uiState.isSelecting,
            enter = slideInVertically(
                initialOffsetY = { it / 3 },
                animationSpec = tween(240, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(180, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(
                targetOffsetY = { it / 3 },
                animationSpec = tween(180, easing = FastOutSlowInEasing),
            ) + fadeOut(tween(140, easing = FastOutSlowInEasing)),
        ) {
            val canDelete = uiState.selectedIds.isNotEmpty() && !uiState.isDeleting
            LiquidGlassSurface(
                shape = CircleShape,
                variant = GlassVariant.Liquid,
                hazeState = localHazeState,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SelectionCapsuleAction(
                        icon = painterResource(id = R.drawable.select_all_24px),
                        label = stringResource(R.string.select_all),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        enabled = !uiState.isDeleting,
                        onClick = uiState.selectAll,
                    )
                    SelectionCapsuleAction(
                        icon = painterResource(id = R.drawable.delete_forever_24px),
                        label = stringResource(R.string.local_book_delete_confirm),
                        containerColor = if (canDelete) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                        } else {
                            Color.Transparent
                        },
                        contentColor = if (canDelete) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                        enabled = canDelete,
                        onClick = { deleteDialogVisible = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionCapsuleAction(
    icon: Painter,
    label: String,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    containerColor: Color = Color.Transparent,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.W600,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollapseHeader(
    icon: Painter,
    title: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp),
    ) {
        EditorialCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            borderColor = tokens.cardBorder,
            onClick = onToggleExpand,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EditorialIconBadge(
                    painter = icon,
                    size = 32.dp,
                    iconSize = 17.dp,
                    cornerRadius = 10.dp,
                )
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val rotation by animateFloatAsState(if (expanded) 0f else 180f, label = "collapseRotation")
                Icon(
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                    painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LocalBookRow(
    modifier: Modifier = Modifier,
    item: LocalBookItem,
    uiState: LocalBookManagerUiState,
    onToggleInfo: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val isSelected = item.id in uiState.selectedIds
    val progress by animateFloatAsState(
        targetValue = if (uiState.isSelecting) 1f else 0f,
        animationSpec = tween(
            durationMillis = 220,
            easing = FastOutSlowInEasing,
        ),
        label = "selectionProgress",
    )

    val sideSlotWidth = 48.dp
    val density = LocalDensity.current
    val sideSlotPx = with(density) { sideSlotWidth.roundToPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .squircleBackground(
                color = if (isSelected && uiState.isSelecting) tokens.activePillFill else tokens.cardSurface,
                cornerRadius = tokens.cardRadius,
            )
            .squircleBorder(
                width = if (isSelected && uiState.isSelecting) 1.25.dp else 0.75.dp,
                color = if (isSelected && uiState.isSelecting) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                cornerRadius = tokens.cardRadius,
            )
            .squircleClip(cornerRadius = tokens.cardRadius)
            .combinedClickable(
                onClick = {
                    if (uiState.isSelecting) {
                        uiState.toggleSelect(item.id)
                    } else {
                        uiState.openBookDetailScreen(item.id)
                    }
                },
                onLongClick = {
                    uiState.enterSelection(item.id)
                },
            )
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = sideSlotWidth + 6.dp)
                .offset {
                    IntOffset(
                        x = (sideSlotPx * progress).roundToInt(),
                        y = 0,
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val result by item.bookInformationFlow.collectAsStateWithLifecycle(null)
            if (result == null) {
                LocalBookRowPlaceholder(size = item.size)
            } else {
                result?.onOk { info ->
                    Cover(
                        bookId = info.id,
                        width = 58.dp,
                        height = 84.dp,
                        uri = info.coverUri,
                        title = info.title,
                        author = info.author,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = info.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.W600,
                        )
                        Text(
                            text = formatSize(item.size),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.W500,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            TagChip(painterResource(R.drawable.update_24px))
                            Text(
                                text = if (item.lastReadTime != null) formTime(item.lastReadTime)
                                else stringResource(R.string.book_manager_no_local_reading_record),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }?.onErr {
                    LocalBookFallbackHeader(
                        item = item,
                        showCounts = false,
                    )
                }
            }
        }

        if (progress > 0.001f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .width(sideSlotWidth),
                contentAlignment = Alignment.Center,
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = {
                        uiState.toggleSelect(item.id)
                    },
                    modifier = Modifier.graphicsLayer {
                        alpha = progress
                        scaleX = 0.85f + 0.15f * progress
                        scaleY = 0.85f + 0.15f * progress
                        translationX = -sideSlotPx * 0.35f * (1f - progress)
                    },
                )
            }
        }

        if (progress < 0.999f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .width(sideSlotWidth),
                contentAlignment = Alignment.Center,
            ) {
                EditorialIconButton(
                    onClick = onToggleInfo,
                    painter = painterResource(R.drawable.info_24px),
                    contentDescription = stringResource(R.string.action_show_info),
                    modifier = Modifier.graphicsLayer {
                        val p = 1f - progress
                        alpha = p
                        scaleX = 0.85f + 0.15f * p
                        scaleY = 0.85f + 0.15f * p
                        translationX = sideSlotPx * 0.35f * progress
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalBookRowPlaceholder(size: Long) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 84.dp)
            .squircleBackground(color = placeholderColor, cornerRadius = 10.dp),
    )
    Spacer(Modifier.width(12.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .height(16.dp)
                .clip(CircleShape)
                .background(placeholderColor),
        )
        Text(
            text = formatSize(size),
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.45f)
                .height(12.dp)
                .clip(CircleShape)
                .background(placeholderColor.copy(alpha = 0.7f)),
        )
    }
}

@Composable
private fun LocalBookFallbackHeader(
    item: LocalBookItem,
    showCounts: Boolean,
) {
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 84.dp)
            .squircleBackground(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                cornerRadius = 10.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.menu_book_24px),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
    Spacer(Modifier.width(12.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = stringResource(
                R.string.download_task_unknown,
                BookIdentity.book(item.id).fileKey.take(8),
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W600,
        )
        Text(
            text = if (showCounts) {
                stringResource(
                    R.string.detail_info_stats_count_content,
                    item.volumeCount,
                    item.chapterCount,
                )
            } else {
                formatSize(item.size)
            },
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalBookInfoSheetContent(
    item: LocalBookItem,
    isClearing: Boolean,
    clearTargets: List<LocalBookClearTarget>,
    onToggleClearTarget: (LocalBookClearTarget) -> Unit,
    onEnterClear: () -> Unit,
    onCancelClear: () -> Unit,
    onConfirmClear: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.local_book_info_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.W600,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val result by item.bookInformationFlow.collectAsStateWithLifecycle(null)
            if (result == null) {
                LocalBookRowPlaceholder(size = item.size)
            } else {
                result?.onOk { info ->
                    Cover(
                        bookId = info.id,
                        width = 62.dp,
                        height = 90.dp,
                        uri = info.coverUri,
                        title = info.title,
                        author = info.author,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = info.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.W600,
                        )
                        if (info.author.isNotBlank()) {
                            Text(
                                text = info.author,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.W500,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.detail_info_stats_count_content,
                                item.volumeCount,
                                item.chapterCount,
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }?.onErr {
                    LocalBookFallbackHeader(
                        item = item,
                        showCounts = true,
                    )
                }
            }
        }

        AnimatedContent(
            targetState = isClearing,
            transitionSpec = {
                fadeEnter() togetherWith fadeExit()
            },
            label = "LocalBookInfoContent",
        ) { clearing ->
            if (!clearing) {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.importedFileBytes > 0L) {
                            LocalBookInfoRow(
                                label = stringResource(R.string.local_book_stored_files),
                                value = formatSize(item.importedFileBytes),
                            )
                        }
                        LocalBookInfoRow(
                            label = stringResource(R.string.local_book_info_book_information),
                            value = formatSize(size = item.bookInformationBytes, minUnit = FileSizeUnit.KB),
                        )
                        LocalBookInfoRow(
                            label = stringResource(R.string.local_book_info_volume_index),
                            value = formatSize(size = item.volumeBytes, minUnit = FileSizeUnit.KB),
                        )
                        LocalBookInfoRow(
                            label = stringResource(R.string.local_book_info_chapter_information),
                            value = formatSize(size = item.chapterInformationBytes, minUnit = FileSizeUnit.KB),
                        )
                        LocalBookInfoRow(
                            label = stringResource(R.string.local_book_info_chapter_content),
                            value = formatSize(size = item.chapterContentBytes, minUnit = FileSizeUnit.KB),
                        )
                        if (item.downloadImageBytes > 0L) {
                            LocalBookInfoRow(
                                label = stringResource(R.string.storage_download_images),
                                value = formatSize(size = item.downloadImageBytes, minUnit = FileSizeUnit.KB),
                            )
                        }
                        if (item.preparationBytes > 0L) {
                            LocalBookInfoRow(
                                label = stringResource(R.string.download_task_unfinished),
                                value = formatSize(size = item.preparationBytes, minUnit = FileSizeUnit.KB),
                            )
                        }
                        LocalBookInfoRow(
                            label = stringResource(R.string.local_book_info_reading_record),
                            value = formatSize(size = item.readingRecordBytes, minUnit = FileSizeUnit.KB),
                        )
                        EditorialHairlineDivider(modifier = Modifier.padding(vertical = 4.dp))
                        LocalBookInfoRow(
                            label = stringResource(R.string.local_book_info_total),
                            value = formatSize(item.size),
                            emphasized = true,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        modifier = Modifier.padding(start = 4.dp),
                        text = stringResource(R.string.local_book_clear_options),
                    )
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 18.dp,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        LocalBookClearTarget.entries.forEachIndexed { index, target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleClearTarget(target) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = stringResource(target.label),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.W500,
                                    )
                                    Text(
                                        text = formatSize(item.bytesOf(target)),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFeatureSettings = "tnum",
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Checkbox(
                                    checked = target in clearTargets,
                                    onCheckedChange = { onToggleClearTarget(target) },
                                )
                            }
                            if (index != LocalBookClearTarget.entries.lastIndex) {
                                EditorialHairlineDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isClearing) {
                OutlinedButton(
                    onClick = onCancelClear,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onConfirmClear,
                    enabled = clearTargets.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.local_book_clear))
                }
            } else {
                OutlinedButton(
                    onClick = onEnterClear,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.local_book_clear))
                }
                Button(
                    onClick = onOpenDetail,
                    modifier = Modifier.weight(1f),
                    shape = CircleShape,
                ) {
                    Text(stringResource(R.string.detail_title))
                }
            }
        }
    }
}

@Composable
private fun LocalBookInfoRow(
    label: String,
    value: String,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.W600 else FontWeight.W400,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = (if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                .copy(fontFeatureSettings = "tnum"),
            fontWeight = if (emphasized) FontWeight.W600 else FontWeight.W500,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
