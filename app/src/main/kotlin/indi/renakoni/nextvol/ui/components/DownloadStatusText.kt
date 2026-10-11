package indi.renakoni.nextvol.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.download.BookDownloadPhase
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import indi.renakoni.nextvol.data.download.DownloadFailure
import indi.renakoni.nextvol.data.download.DownloadTaskState
import indi.renakoni.nextvol.data.download.DownloadTaskStatus
import indi.renakoni.nextvol.data.download.DownloadSubmission

@Composable
fun downloadStatusLabel(status: BookDownloadStatus): String = stringResource(when (status.task.status) {
    DownloadTaskStatus.Queued -> if (LocalContext.current.hasDownloadNetwork()) R.string.download_task_waiting_scheduler
        else R.string.download_task_waiting_network
    DownloadTaskStatus.WaitingRetry -> R.string.download_task_waiting_retry
    DownloadTaskStatus.WaitingVerification -> R.string.download_task_waiting_verification
    DownloadTaskStatus.Running -> R.string.book_download_updating
    DownloadTaskStatus.Interrupted -> R.string.download_task_interrupted
    DownloadTaskStatus.Cancelled -> R.string.download_task_cancelled
    DownloadTaskStatus.Failed -> R.string.book_download_failed
    DownloadTaskStatus.Complete -> if (status.content.phase == BookDownloadPhase.Complete) R.string.cached else R.string.book_download_partial
    else -> when (status.content.phase) {
        BookDownloadPhase.None -> R.string.cached_false
        BookDownloadPhase.Partial -> R.string.book_download_partial
        BookDownloadPhase.Complete -> R.string.cached
        BookDownloadPhase.Updating -> R.string.book_download_updating
        BookDownloadPhase.Failed -> R.string.book_download_failed
    }
})

/** One short line: the state, how much of the book is offline, and why a task stopped. */
@Composable
fun downloadStatusText(status: BookDownloadStatus): String {
    val parts = mutableListOf(downloadStatusLabel(status))
    if (status.content.totalChapters > 0) {
        parts += stringResource(R.string.download_task_content, status.content.savedChapters, status.content.totalChapters)
    }
    status.task.failure?.let { parts += stringResource(downloadFailureResource(it)) }
    if (status.task.status == DownloadTaskStatus.WaitingRetry) parts += downloadRetryTimeText(status.task)
    return parts.joinToString(" · ")
}

@Composable
internal fun downloadRetryTimeText(task: DownloadTaskState): String = stringResource(R.string.download_task_retry_time,
    task.retryCount, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(task.nextAttemptAt)))

internal fun downloadFailureResource(failure: DownloadFailure) = when (failure) {
    DownloadFailure.Network -> R.string.download_error_network
    DownloadFailure.RateLimited -> R.string.download_error_rate_limited
    DownloadFailure.RetryExhausted -> R.string.download_error_retry_exhausted
    DownloadFailure.Authentication -> R.string.download_error_authentication
    DownloadFailure.Verification -> R.string.download_error_verification
    DownloadFailure.SourceUnavailable -> R.string.download_error_source
    DownloadFailure.SourceRequest -> R.string.download_error_request
    DownloadFailure.Storage -> R.string.download_error_storage
    DownloadFailure.StorageFull -> R.string.download_error_storage_full
    DownloadFailure.StorageQuota -> R.string.download_error_storage_quota
    DownloadFailure.SystemRestricted -> R.string.download_error_system_restricted
    DownloadFailure.SystemInterrupted -> R.string.download_error_system_interrupted
    DownloadFailure.Scheduling -> R.string.download_error_scheduling
    DownloadFailure.SelectionUnavailable -> R.string.download_selection_unavailable
}

private fun Context.hasDownloadNetwork(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    return manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}

fun Context.downloadSubmissionText(result: DownloadSubmission): String = when (result) {
    is DownloadSubmission.Rejected -> getString(downloadFailureResource(result.failure))
    is DownloadSubmission.Accepted -> {
        val state = when (result.task.status) {
            DownloadTaskStatus.WaitingRetry -> getString(R.string.download_task_retry_time, result.task.retryCount,
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(result.task.nextAttemptAt)))
            DownloadTaskStatus.WaitingVerification -> getString(R.string.download_task_waiting_verification)
            DownloadTaskStatus.Running -> getString(R.string.cache_book_running)
            else -> getString(if (hasDownloadNetwork()) R.string.download_task_waiting_scheduler else R.string.download_task_waiting_network)
        }
        if (!result.selectionMatches) getString(R.string.download_selection_active)
        else if (result.existing) state else getString(R.string.download_submission_accepted, state)
    }
}
