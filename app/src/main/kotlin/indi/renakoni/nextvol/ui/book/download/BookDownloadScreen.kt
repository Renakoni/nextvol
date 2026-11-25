package indi.renakoni.nextvol.ui.book.download

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.download.isStorageFailure
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.DownloadTaskProgress
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.components.downloadFailureResource
import indi.renakoni.nextvol.utils.navigationBarSpacer
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDownloadScreen(
    state: BookDownloadUiState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onSelect: (Set<String>) -> Unit,
    onSubmit: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpenStorage: () -> Unit = {},
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val chapters = remember(state.volumes) { state.allChapters }
    // Downloaded chapters are final: they show a mark instead of a checkbox and are never selected.
    val downloadable = remember(state.volumes, state.chapters) { state.downloadableIds }
    val allSelected = downloadable.isNotEmpty() && state.selected.containsAll(downloadable)
    var expandedVolumes by rememberSaveable(state.bookId) { mutableStateOf(emptyList<Int>()) }
    val editable = state.ready && !state.locked
    val showBottomBar = !state.ready || (editable && downloadable.isNotEmpty())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.download_page_title),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.download_back),
                    )
                },
            )
        },
        // Only offer the action while there is something left to choose; the summary covers the rest.
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.cardElevatedSurface)
                        .navigationBarsPadding(),
                ) {
                    EditorialHairlineDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Button(
                            onClick = onSubmit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp),
                            enabled = editable && state.selected.isNotEmpty(),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(14.dp),
                        ) {
                            Text(
                                text = if (state.selected.isEmpty()) {
                                    stringResource(R.string.download_choose_chapters)
                                } else {
                                    stringResource(R.string.download_start_selected, state.selected.size)
                                },
                                fontWeight = FontWeight.W600,
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "summary") {
                val saved = if (state.ready) {
                    chapters.count { state.chapters.chapters[it.id]?.downloaded == true }
                } else {
                    state.status.content.savedChapters
                }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    DownloadSummary(
                        state = state,
                        saved = saved,
                        total = if (state.ready) chapters.size else state.status.content.totalChapters,
                        onResume = onResume,
                        onCancel = onCancel,
                        onOpenStorage = onOpenStorage,
                    )
                }
            }

            when {
                state.loading -> item(key = "directory-loading") {
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                text = stringResource(R.string.download_directory_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }

                state.directoryFailure != null -> item(key = "directory-error") {
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        cornerRadius = 18.dp,
                        containerColor = colors.errorContainer.copy(alpha = if (tokens.isDark) 0.28f else 0.36f),
                        borderColor = colors.error.copy(alpha = 0.36f),
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = stringResource(R.string.download_directory_failed),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
                                color = colors.onSurface,
                            )
                            Text(
                                text = stringResource(downloadFailureResource(state.directoryFailure)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = onReload,
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(stringResource(R.string.download_directory_retry))
                                }
                                if (state.directoryFailure.isStorageFailure) {
                                    TextButton(
                                        onClick = onOpenStorage,
                                        modifier = Modifier.testTag("download-open-storage"),
                                    ) {
                                        Text(stringResource(R.string.storage_manager_title))
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    item(key = "selection-tools") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .padding(start = 20.dp, end = 12.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.download_directory_ready, chapters.size),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.W600,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = colors.onSurfaceVariant,
                            )
                            if (downloadable.isNotEmpty()) {
                                TextButton(
                                    onClick = { onSelect(if (allSelected) emptySet() else downloadable) },
                                    enabled = editable,
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (allSelected) R.string.download_select_none else R.string.download_select_all,
                                        ),
                                        fontWeight = FontWeight.W600,
                                    )
                                }
                            }
                        }
                    }

                    state.volumes?.volumes?.forEachIndexed { volumeIndex, volume ->
                        val expanded = volumeIndex in expandedVolumes
                        item(key = "volume:$volumeIndex") {
                            val ids = volume.chapters.map { it.id }
                            val open = ids.filter { it in downloadable }.toSet()
                            val selected = open.count { it in state.selected }
                            val title = volume.volumeTitle.ifBlank {
                                stringResource(R.string.download_volume_number, volumeIndex + 1)
                            }
                            val savedInVolume = ids.size - open.size
                            val arrowRotation by animateFloatAsState(
                                targetValue = if (expanded) 90f else 0f,
                                animationSpec = tween(200),
                                label = "downloadVolumeArrow",
                            )
                            val radius = 16.dp

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp)
                                    .squircleBackground(color = tokens.cardSurface, cornerRadius = radius)
                                    .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = radius)
                                    .squircleClip(radius),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("download-volume-toggle-$volumeIndex")
                                        .clickable(
                                            role = Role.Button,
                                            onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand),
                                            onClick = {
                                                expandedVolumes = if (expanded) {
                                                    expandedVolumes - volumeIndex
                                                } else {
                                                    expandedVolumes + volumeIndex
                                                }
                                            },
                                        )
                                        .heightIn(min = 60.dp)
                                        .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.arrow_forward_ios_24px),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .rotate(arrowRotation),
                                        tint = colors.onSurfaceVariant,
                                    )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                                            color = colors.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (savedInVolume == 0) {
                                                stringResource(R.string.info_volume_chapters_count, ids.size)
                                            } else {
                                                stringResource(R.string.download_book_coverage, savedInVolume, ids.size)
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                            color = if (open.isEmpty()) colors.primary else colors.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (open.isEmpty()) {
                                    DownloadedMark(Modifier.padding(end = 4.dp))
                                } else {
                                    TriStateCheckbox(
                                        state = when (selected) {
                                            0 -> ToggleableState.Off
                                            open.size -> ToggleableState.On
                                            else -> ToggleableState.Indeterminate
                                        },
                                        onClick = {
                                            onSelect(if (selected == open.size) state.selected - open else state.selected + open)
                                        },
                                        enabled = editable,
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .testTag("download-volume-$volumeIndex")
                                            .semantics { contentDescription = title },
                                    )
                                }
                            }
                        }

                        if (expanded) {
                            items(volume.chapters, key = { "chapter:${it.id}" }) { chapter ->
                                val saved = state.chapters.chapters[chapter.id]
                                val downloaded = saved?.downloaded == true
                                val isSelected = chapter.id in state.selected
                                val active = state.status.task.active && state.status.task.chapterId == chapter.id
                                val rowInteraction = if (downloaded) {
                                    Modifier.semantics(mergeDescendants = true) {}
                                } else {
                                    Modifier.toggleable(isSelected, enabled = editable, role = Role.Checkbox) {
                                        onSelect(if (it) state.selected + chapter.id else state.selected - chapter.id)
                                    }
                                }
                                val radius = 14.dp
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 26.dp, end = 16.dp)
                                        .squircleBackground(
                                            color = if (!downloaded && isSelected) tokens.activePillFill else Color.Transparent,
                                            cornerRadius = radius,
                                        )
                                        .squircleClip(radius)
                                        .then(rowInteraction)
                                        .heightIn(min = 52.dp)
                                        .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = chapter.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (downloaded) colors.onSurfaceVariant else colors.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (active) {
                                            Text(
                                                text = stringResource(R.string.download_chapter_downloading),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.primary,
                                            )
                                        } else if (!downloaded) {
                                            saved?.failure?.let {
                                                Text(
                                                    text = stringResource(downloadFailureResource(it)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.error,
                                                )
                                            }
                                        }
                                    }
                                    if (downloaded) {
                                        DownloadedMark()
                                    } else {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null,
                                            enabled = editable,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!showBottomBar) {
                navigationBarSpacer()
            }
        }
    }
}

/** Book progress and, while one exists, the task that changes it. One card instead of two status blocks. */
@Composable
private fun DownloadSummary(
    state: BookDownloadUiState,
    saved: Int,
    total: Int,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val information = state.information
    val title = information?.title ?: stringResource(R.string.download_page_title)
    val progress by animateFloatAsState(
        targetValue = if (total > 0) (saved.toFloat() / total).coerceIn(0f, 1f) else 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "DownloadBookProgress",
    )

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(10.dp),
                    ambientColor = tokens.glassShadow,
                    spotColor = tokens.glassShadow,
                    clip = false,
                ),
            ) {
                Cover(
                    bookId = state.bookId,
                    width = 66.dp,
                    height = 92.dp,
                    uri = information?.coverUri ?: Uri.EMPTY,
                    title = title,
                    rounded = 10.dp,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W700),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                information?.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                        color = colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(5.dp)
                            .clip(CircleShape),
                        trackColor = colors.surfaceContainerHighest,
                    )
                    Text(
                        text = stringResource(R.string.download_book_coverage, saved, total),
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (saved >= total) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
        }
        // The leaving section keeps the state it was shown with while it fades and collapses.
        AnimatedContent(
            targetState = state.takeIf { it.status.task.status !in setOf(DownloadTaskStatus.None, DownloadTaskStatus.Complete) },
            contentKey = { it != null },
            transitionSpec = {
                fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = true)
            },
            label = "DownloadTask",
        ) { shown ->
            if (shown != null) {
                DownloadTaskProgress(shown.status, shown.submitting, onResume, onCancel, onOpenStorage)
            }
        }
    }
}

@Composable
private fun DownloadedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.check_24px),
            contentDescription = stringResource(R.string.download_chapter_saved),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
