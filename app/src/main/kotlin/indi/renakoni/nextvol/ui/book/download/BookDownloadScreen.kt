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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import indi.renakoni.nextvol.data.download.isStorageFailure
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.ui.components.Cover
import indi.renakoni.nextvol.ui.components.downloadFailureResource
import indi.renakoni.nextvol.ui.components.downloadRetryTimeText
import indi.renakoni.nextvol.ui.components.downloadStatusLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDownloadScreen(
    state: BookDownloadUiState, onBack: () -> Unit, onReload: () -> Unit,
    onSelect: (Set<String>) -> Unit,
    onSubmit: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit,
    onOpenStorage: () -> Unit = {},
) {
    val chapters = remember(state.volumes) { state.allChapters }
    // Downloaded chapters are final: they show a mark instead of a checkbox and are never selected.
    val downloadable = remember(state.volumes, state.chapters) { state.downloadableIds }
    val allSelected = downloadable.isNotEmpty() && state.selected.containsAll(downloadable)
    var expandedVolume by rememberSaveable(state.bookId) { mutableStateOf<Int?>(null) }
    val editable = state.ready && !state.locked
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(stringResource(R.string.download_page_title)) },
            navigationIcon = { IconButton(onBack) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.download_back))
            } },
        ) },
        // Only offer the action while there is something left to choose; the summary covers the rest.
        bottomBar = { AnimatedVisibility(!state.ready || editable && downloadable.isNotEmpty(),
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) { Surface(tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(onSubmit, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = editable && state.selected.isNotEmpty(),
                    contentPadding = PaddingValues(16.dp)) {
                    Text(if (state.selected.isEmpty()) stringResource(R.string.download_choose_chapters) else
                        stringResource(R.string.download_start_selected, state.selected.size))
                }
            }
        } } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp)) {
            item(key = "summary") {
                val saved = if (state.ready) chapters.count { state.chapters.chapters[it.id]?.downloaded == true }
                    else state.status.content.savedChapters
                DownloadSummary(state, saved, if (state.ready) chapters.size else state.status.content.totalChapters,
                    onResume, onCancel, onOpenStorage)
            }
            when {
                state.loading -> item(key = "directory-loading") {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.download_directory_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.directoryFailure != null -> item(key = "directory-error") {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.download_directory_failed), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(downloadFailureResource(state.directoryFailure)), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onReload) { Text(stringResource(R.string.download_directory_retry)) }
                            if (state.directoryFailure.isStorageFailure) TextButton(onOpenStorage, Modifier.testTag("download-open-storage")) {
                                Text(stringResource(R.string.storage_manager_title))
                            }
                        }
                    }
                }
                else -> {
                    item(key = "selection-tools") {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.download_directory_ready, chapters.size), Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (downloadable.isNotEmpty()) TextButton({ onSelect(if (allSelected) emptySet() else downloadable) }, enabled = editable) {
                                Text(stringResource(if (allSelected) R.string.download_select_none else R.string.download_select_all))
                            }
                        }
                    }
                    state.volumes?.volumes?.forEachIndexed { volumeIndex, volume ->
                        val expanded = expandedVolume == volumeIndex
                        item(key = "volume:$volumeIndex") {
                            val ids = volume.chapters.map { it.id }
                            val open = ids.filter { it in downloadable }.toSet()
                            val selected = open.count { it in state.selected }
                            val title = volume.volumeTitle.ifBlank { stringResource(R.string.download_volume_number, volumeIndex + 1) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).testTag("download-volume-toggle-$volumeIndex").clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand),
                                    onClick = { expandedVolume = if (expanded) null else volumeIndex },
                                ).heightIn(min = 64.dp).padding(vertical = 12.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(painterResource(R.drawable.arrow_forward_ios_24px), null,
                                        Modifier.size(14.dp).rotate(if (expanded) -90f else 90f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        val savedInVolume = ids.size - open.size
                                        Text(if (savedInVolume == 0) stringResource(R.string.info_volume_chapters_count, ids.size)
                                            else stringResource(R.string.download_book_coverage, savedInVolume, ids.size),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (open.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (open.isEmpty()) DownloadedMark()
                                else TriStateCheckbox(when (selected) { 0 -> ToggleableState.Off; open.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                                    onClick = { onSelect(if (selected == open.size) state.selected - open else state.selected + open) }, enabled = editable,
                                    modifier = Modifier.testTag("download-volume-$volumeIndex").semantics { contentDescription = title })
                            }
                        }
                        if (expanded) items(volume.chapters, key = { "chapter:${it.id}" }) { chapter ->
                            val saved = state.chapters.chapters[chapter.id]
                            val downloaded = saved?.downloaded == true
                            val active = state.status.task.active && state.status.task.chapterId == chapter.id
                            val row = if (downloaded) Modifier.semantics(mergeDescendants = true) {}
                                else Modifier.toggleable(chapter.id in state.selected, enabled = editable, role = Role.Checkbox) {
                                    onSelect(if (it) state.selected + chapter.id else state.selected - chapter.id)
                                }
                            Row(Modifier.fillMaxWidth().then(row).heightIn(min = 56.dp).padding(start = 28.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(chapter.title, style = MaterialTheme.typography.bodyMedium,
                                        color = if (downloaded) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                    if (active) Text(stringResource(R.string.download_chapter_downloading),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    else if (!downloaded) saved?.failure?.let {
                                        Text(stringResource(downloadFailureResource(it)), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                if (downloaded) DownloadedMark(Modifier.padding(start = 8.dp))
                                else Checkbox(chapter.id in state.selected, onCheckedChange = null, enabled = editable, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Book progress and, while one exists, the task that changes it. One card instead of two status blocks. */
@Composable
private fun DownloadSummary(state: BookDownloadUiState, saved: Int, total: Int,
    onResume: () -> Unit, onCancel: () -> Unit, onOpenStorage: () -> Unit) {
    val information = state.information
    val title = information?.title ?: stringResource(R.string.download_page_title)
    val progress by animateFloatAsState(if (total > 0) (saved.toFloat() / total).coerceIn(0f, 1f) else 0f,
        ProgressIndicatorDefaults.ProgressAnimationSpec, label = "DownloadBookProgress")
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(state.bookId, 64.dp, 88.dp, information?.coverUri ?: Uri.EMPTY, title)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    information?.author?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (total > 0) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                        Text(stringResource(R.string.download_book_coverage, saved, total), style = MaterialTheme.typography.labelMedium,
                            color = if (saved >= total) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // The leaving section keeps the state it was shown with while it fades and collapses.
            AnimatedContent(state.takeIf { it.status.task.status !in setOf(DownloadTaskStatus.None, DownloadTaskStatus.Complete) },
                contentKey = { it != null },
                transitionSpec = { fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90)) using SizeTransform(clip = true) },
                label = "DownloadTask") { shown ->
                if (shown != null) DownloadTask(shown, onResume, onCancel, onOpenStorage)
            }
        }
    }
}

@Composable
private fun DownloadTask(state: BookDownloadUiState, onResume: () -> Unit, onCancel: () -> Unit, onOpenStorage: () -> Unit) {
    val task = state.status.task
    val content = state.status.content
    val counting = task.status == DownloadTaskStatus.Running &&
        task.stage !in setOf(DownloadStage.Unknown, DownloadStage.Details, DownloadStage.Directory)
    val progress by animateFloatAsState((content.taskSavedChapters.toFloat() / content.taskTotalChapters.coerceAtLeast(1)).coerceIn(0f, 1f),
        ProgressIndicatorDefaults.ProgressAnimationSpec, label = "DownloadTaskProgress")
    Column(Modifier.padding(top = 16.dp).testTag("download-task"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // The status and its actions share one line so the card stays compact.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (counting) stringResource(R.string.download_task_progress, content.taskSavedChapters, content.taskTotalChapters)
                else downloadStatusLabel(state.status), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (task.failure?.isStorageFailure == true) TextButton(onOpenStorage, Modifier.testTag("download-open-storage")) {
                Text(stringResource(R.string.storage_manager_title))
            }
            if (state.locked) TextButton(onCancel, enabled = !state.submitting) { Text(stringResource(R.string.cancel)) }
            if (task.status == DownloadTaskStatus.WaitingVerification) TextButton(onResume, enabled = !state.submitting) {
                Text(stringResource(R.string.download_task_verify))
            } else if (task.canResume && !state.locked) TextButton(onResume, enabled = !state.submitting) {
                Text(stringResource(if (task.status == DownloadTaskStatus.Failed) R.string.book_download_retry
                    else R.string.book_download_continue))
            }
        }
        if (task.active) {
            if (counting) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        }
        val details = listOfNotNull(task.failure?.let { stringResource(downloadFailureResource(it)) },
            if (task.status == DownloadTaskStatus.WaitingRetry) downloadRetryTimeText(task) else null)
        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = if (task.canResume && !state.locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.check_24px), stringResource(R.string.download_chapter_saved),
            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
