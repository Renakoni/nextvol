package indi.renakoni.nextvol.ui.book.download

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import indi.renakoni.nextvol.ui.components.downloadStatusText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDownloadScreen(
    state: BookDownloadUiState, onBack: () -> Unit, onReload: () -> Unit,
    onSelect: (Set<String>) -> Unit,
    onSubmit: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit,
    onOpenStorage: () -> Unit = {},
) {
    val chapters = remember(state.volumes) { state.allChapters }
    val chapterIds = remember(chapters) { chapters.map { it.id }.toSet() }
    val allSelected = chapterIds.isNotEmpty() && state.selected.containsAll(chapterIds)
    var expandedVolume by rememberSaveable(state.bookId) { mutableStateOf<Int?>(null) }
    val editable = state.ready && !state.locked
    Scaffold(
        topBar = { TopAppBar(
            title = { Text(stringResource(R.string.download_page_title)) },
            navigationIcon = { IconButton(onBack) {
                Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.download_back))
            } },
        ) },
        bottomBar = { Surface(tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Button(onSubmit, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = editable && state.selected.isNotEmpty(),
                    contentPadding = PaddingValues(16.dp)) {
                    Text(if (state.selected.isEmpty()) stringResource(R.string.download_choose_chapters) else
                        stringResource(R.string.download_start_selected, state.selected.size))
                }
            }
        } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp)) {
            item(key = "summary") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val information = state.information
                        Cover(state.bookId, 64.dp, 88.dp, information?.coverUri ?: Uri.EMPTY,
                            information?.title ?: stringResource(R.string.download_page_title))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(information?.title ?: stringResource(R.string.download_page_title),
                                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            information?.author?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(R.string.download_book_coverage, state.status.content.savedChapters,
                                if (state.ready) chapters.size else state.status.content.totalChapters),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (state.status.task.status != DownloadTaskStatus.None) item(key = "task") {
                Surface(modifier = Modifier.padding(top = 16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(downloadStatusText(state.status), style = MaterialTheme.typography.bodyMedium)
                        if (state.status.task.active) {
                            if (state.status.task.status == DownloadTaskStatus.Queued || state.status.task.stage in
                                setOf(DownloadStage.Unknown, DownloadStage.Details, DownloadStage.Directory)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else LinearProgressIndicator(
                                progress = { (state.status.content.taskSavedChapters.toFloat() /
                                    state.status.content.taskTotalChapters.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row {
                            if (state.status.task.canResume && (!state.locked || state.status.task.status == DownloadTaskStatus.WaitingVerification))
                                TextButton(onResume, enabled = !state.submitting) {
                                Text(stringResource(if (state.status.task.status == DownloadTaskStatus.WaitingVerification)
                                    R.string.download_task_verify else R.string.download_resume_selection))
                            }
                            if (state.locked) TextButton(onCancel, enabled = !state.submitting) {
                                Text(stringResource(R.string.download_cancel_reselect))
                            }
                        }
                    }
                }
            }
            if (state.status.task.failure?.isStorageFailure == true || state.directoryFailure?.isStorageFailure == true) {
                item(key = "storage-recovery") {
                    TextButton(onOpenStorage, Modifier.padding(top = 8.dp).testTag("download-open-storage")) {
                        Text(stringResource(R.string.storage_manager_title))
                    }
                }
            }
            when {
                state.loading -> item(key = "directory-loading") {
                    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.download_directory_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.directoryFailure != null -> item(key = "directory-error") {
                    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.download_directory_failed), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(downloadFailureResource(state.directoryFailure)), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onReload) { Text(stringResource(R.string.download_directory_retry)) }
                    }
                }
                else -> {
                    item(key = "selection-tools") {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.download_directory_ready, chapters.size), Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ onSelect(if (allSelected) emptySet() else chapterIds) }, enabled = editable) {
                                Text(stringResource(if (allSelected) R.string.download_select_none else R.string.download_select_all))
                            }
                        }
                    }
                    state.volumes?.volumes?.forEachIndexed { volumeIndex, volume ->
                        val expanded = expandedVolume == volumeIndex
                        item(key = "volume:$volumeIndex") {
                            val ids = volume.chapters.map { it.id }.toSet()
                            val selected = ids.count { it in state.selected }
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
                                        Text(stringResource(R.string.info_volume_chapters_count, ids.size), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TriStateCheckbox(when (selected) { 0 -> ToggleableState.Off; ids.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                                    onClick = { onSelect(if (selected == ids.size) state.selected - ids else state.selected + ids) }, enabled = editable,
                                    modifier = Modifier.testTag("download-volume-$volumeIndex").semantics { contentDescription = title })
                            }
                        }
                        if (expanded) items(volume.chapters, key = { "chapter:${it.id}" }) { chapter ->
                            val saved = state.chapters.chapters[chapter.id]
                            val active = state.status.task.active && state.status.task.chapterId == chapter.id
                            Row(Modifier.fillMaxWidth().toggleable(chapter.id in state.selected, enabled = editable, role = Role.Checkbox) {
                                onSelect(if (it) state.selected + chapter.id else state.selected - chapter.id)
                            }.heightIn(min = 56.dp).padding(start = 28.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(chapter.title, style = MaterialTheme.typography.bodyMedium)
                                    val label = when {
                                        active -> R.string.download_chapter_downloading
                                        saved?.failure != null -> downloadFailureResource(saved.failure)
                                        saved?.downloaded == true -> R.string.download_chapter_saved
                                        else -> null
                                    }
                                    if (label != null) Text(stringResource(label), style = MaterialTheme.typography.labelSmall,
                                        color = if (saved?.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                }
                                Checkbox(chapter.id in state.selected, onCheckedChange = null, enabled = editable, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
