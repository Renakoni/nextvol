package indi.renakoni.nextvol.data.download

import androidx.work.WorkInfo
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.image.SourceImageRequestException
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import java.io.IOException

enum class DownloadTaskStatus { None, Queued, Running, WaitingRetry, WaitingVerification, Interrupted, Failed, Cancelled, Complete }
enum class DownloadStage { Unknown, Details, Directory, Body, Image, Cover, Storage }
enum class DownloadFailure { Network, RateLimited, RetryExhausted, Authentication, Verification, SourceUnavailable, SourceRequest, Storage, StorageFull, StorageQuota, SystemRestricted, SystemInterrupted, Scheduling, SelectionUnavailable }

val DownloadFailure.isStorageFailure get() = this in setOf(DownloadFailure.Storage, DownloadFailure.StorageFull, DownloadFailure.StorageQuota)

data class DownloadTaskState(
    val status: DownloadTaskStatus = DownloadTaskStatus.None,
    val stage: DownloadStage = DownloadStage.Unknown,
    val chapterId: String = "",
    val failure: DownloadFailure? = null,
    val runAttemptCount: Int = 0,
    val retryCount: Int = 0,
    val nextAttemptAt: Long = 0,
) {
    val active get() = status == DownloadTaskStatus.Queued || status == DownloadTaskStatus.Running
    val canResume get() = status in setOf(DownloadTaskStatus.WaitingRetry, DownloadTaskStatus.WaitingVerification, DownloadTaskStatus.Interrupted, DownloadTaskStatus.Failed, DownloadTaskStatus.Cancelled)
}

data class BookDownloadStatus(
    val content: BookDownloadState = BookDownloadState(),
    val task: DownloadTaskState = DownloadTaskState(),
) {
    val displayPhase get() = when {
        task.active -> BookDownloadPhase.Updating
        task.status == DownloadTaskStatus.Failed -> BookDownloadPhase.Failed
        else -> content.phase
    }
}

/** Persist only an allow-listed category, never exception messages, headers or source URLs. */
internal fun downloadFailure(error: WebRequestError?, stage: DownloadStage): DownloadFailure {
    if (error?.throwable is DownloadSelectionChangedException) return DownloadFailure.SelectionUnavailable
    val image = error?.throwable as? SourceImageRequestException
    val content = error?.throwable as? hnovel.content.SourceContentException
    if ((content?.storageFailure ?: image?.storageFailure) == hnovel.network.FailureCode.StorageQuota) return DownloadFailure.StorageQuota
    if (generateSequence(error?.throwable) { it.cause }.take(16).any {
        it is android.database.sqlite.SQLiteFullException ||
            it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC
    }) return DownloadFailure.StorageFull
    return when (image?.kind ?: error?.kind) {
        WebRequestErrorKind.AuthenticationRequired -> DownloadFailure.Authentication
        WebRequestErrorKind.VerificationRequired -> DownloadFailure.Verification
        WebRequestErrorKind.SourceUnavailable -> DownloadFailure.SourceUnavailable
        else -> if (error?.throwable is indi.renakoni.nextvol.data.web.SourceUnavailableException) DownloadFailure.SourceUnavailable
            else if (content?.code == hnovel.content.ContentError.Certificate || image?.contentError == hnovel.content.ContentError.Certificate) DownloadFailure.Verification
            else if (content?.httpStatus in setOf(429, 503) || image?.httpStatus in setOf(429, 503)) DownloadFailure.RateLimited
            else if (stage == DownloadStage.Storage || error?.throwable is android.database.sqlite.SQLiteException ||
                content?.code == hnovel.content.ContentError.Storage || image?.contentError == hnovel.content.ContentError.Storage) DownloadFailure.Storage
            else if (content?.code in setOf(hnovel.content.ContentError.Network, hnovel.content.ContentError.Dns,
                hnovel.content.ContentError.RouteUnavailable)) DownloadFailure.Network
            else if (image?.retry != null || (image?.networkFailure ?: (error?.throwable is IOException))) DownloadFailure.Network else DownloadFailure.SourceRequest
    }
}

/** A persisted running flag is not evidence of a live executor after process reconstruction. */
internal fun BookDownloadEntity.taskState(work: WorkInfo.State?): DownloadTaskState {
    val stored = DownloadTaskStatus.entries.firstOrNull { it.name == taskStatus } ?: DownloadTaskStatus.None
    val resolved = when {
        stored == DownloadTaskStatus.Cancelled -> stored
        stored == DownloadTaskStatus.WaitingVerification -> stored
        stored == DownloadTaskStatus.WaitingRetry && work !in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED) -> stored
        work == WorkInfo.State.RUNNING -> DownloadTaskStatus.Running
        work == WorkInfo.State.ENQUEUED || work == WorkInfo.State.BLOCKED -> DownloadTaskStatus.Queued
        work == WorkInfo.State.CANCELLED -> DownloadTaskStatus.Cancelled
        stored == DownloadTaskStatus.Interrupted -> stored
        work == WorkInfo.State.FAILED -> DownloadTaskStatus.Failed
        stored == DownloadTaskStatus.Running || stored == DownloadTaskStatus.Queued -> DownloadTaskStatus.Interrupted
        stored == DownloadTaskStatus.None && phase == "updating" -> DownloadTaskStatus.Interrupted
        stored == DownloadTaskStatus.None && phase == "failed" -> DownloadTaskStatus.Failed
        else -> stored
    }
    return DownloadTaskState(resolved, DownloadStage.entries.firstOrNull { it.name == taskStage } ?: DownloadStage.Unknown,
        taskChapter, DownloadFailure.entries.firstOrNull { it.name == taskError }, taskRunAttempt,
        retryCount = taskRetryCount, nextAttemptAt = taskNextAttemptAt)
}
