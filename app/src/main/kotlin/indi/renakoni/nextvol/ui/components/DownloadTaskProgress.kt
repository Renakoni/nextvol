package indi.renakoni.nextvol.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadStage
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.download.isStorageFailure

@Composable
internal fun DownloadTaskProgress(status: BookDownloadStatus, submitting: Boolean,
    onResume: () -> Unit, onCancel: (() -> Unit)?, onOpenStorage: () -> Unit) {
    val locked = submitting || status.task.active || status.task.status in
        setOf(DownloadTaskStatus.WaitingRetry, DownloadTaskStatus.WaitingVerification)
    val task = status.task
    val content = status.content
    val counting = task.status == DownloadTaskStatus.Running &&
        task.stage !in setOf(DownloadStage.Unknown, DownloadStage.Details, DownloadStage.Directory)
    val progress by animateFloatAsState((content.taskSavedChapters.toFloat() / content.taskTotalChapters.coerceAtLeast(1)).coerceIn(0f, 1f),
        ProgressIndicatorDefaults.ProgressAnimationSpec, label = "DownloadTaskProgress")
    Column(Modifier.padding(top = 16.dp).testTag("download-task"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // The status and its actions share one line so the card stays compact.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (counting) stringResource(R.string.download_task_progress, content.taskSavedChapters, content.taskTotalChapters)
                else downloadStatusLabel(status), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (task.failure?.isStorageFailure == true) TextButton(onOpenStorage, Modifier.testTag("download-open-storage")) {
                Text(stringResource(R.string.storage_manager_title))
            }
            if (locked && onCancel != null) TextButton(onCancel, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
            if (task.status == DownloadTaskStatus.WaitingVerification) TextButton(onResume, enabled = !submitting) {
                Text(stringResource(R.string.download_task_verify))
            } else if (task.canResume && !locked) TextButton(onResume, enabled = !submitting) {
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
            color = if (task.canResume && !locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
