package indi.renakoni.nextvol.data.download

import androidx.work.WorkInfo
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DownloadTaskStateTest {
    @Test fun platformRestrictionRemainsResumableWithoutRefundingRetries() {
        val owner = BookDownloadEntity("book", taskStatus = DownloadTaskStatus.Interrupted.name,
            taskError = DownloadFailure.SystemRestricted.name, taskRetryCount = 2)
        val state = owner.taskState(WorkInfo.State.FAILED)
        assertEquals(DownloadTaskStatus.Interrupted, state.status)
        assertEquals(DownloadFailure.SystemRestricted, state.failure)
        assertEquals(2, state.retryCount)
        assertTrue(state.canResume)
        assertFalse(state.active)
        assertEquals(DownloadTaskStatus.Cancelled, owner.taskState(WorkInfo.State.CANCELLED).status)
    }

    @Test fun verificationWaitSurvivesFinishedOrMissingExecutorWithoutBecomingActive() {
        val owner = BookDownloadEntity("book", taskStatus = DownloadTaskStatus.WaitingVerification.name)
        for (work in listOf(null, WorkInfo.State.FAILED, WorkInfo.State.RUNNING)) {
            val state = owner.taskState(work)
            assertEquals(DownloadTaskStatus.WaitingVerification, state.status)
            assertTrue(state.canResume)
            assertFalse(state.active)
        }
    }

    @Test fun orphanedRunningAndQueuedRecordsAreResumableNotActive() {
        for (status in listOf(DownloadTaskStatus.Running, DownloadTaskStatus.Queued)) {
            val owner = BookDownloadEntity("book", taskStatus = status.name)
            assertEquals(DownloadTaskStatus.Interrupted, owner.taskState(null).status)
            assertEquals(DownloadTaskStatus.Interrupted, owner.taskState(WorkInfo.State.SUCCEEDED).status)
            assertFalse(owner.taskState(null).active)
            assertTrue(owner.taskState(null).canResume)
        }
    }

    @Test fun executorStateIsMergedWithoutOverridingExplicitCancellation() {
        val owner = BookDownloadEntity("book", taskStatus = DownloadTaskStatus.Running.name)
        assertEquals(DownloadTaskStatus.Running, owner.taskState(WorkInfo.State.RUNNING).status)
        assertEquals(DownloadTaskStatus.Queued, owner.taskState(WorkInfo.State.ENQUEUED).status)
        assertEquals(DownloadTaskStatus.Cancelled, owner.taskState(WorkInfo.State.CANCELLED).status)
        assertEquals(DownloadTaskStatus.Failed, owner.taskState(WorkInfo.State.FAILED).status)
        assertEquals(DownloadTaskStatus.Cancelled, owner.copy(taskStatus = DownloadTaskStatus.Cancelled.name)
            .taskState(WorkInfo.State.RUNNING).status)
    }

    @Test fun taskCompletionDoesNotMakeAPartialDownloadComplete() {
        val task = DownloadTaskState(DownloadTaskStatus.Complete)
        assertEquals(BookDownloadPhase.Partial, BookDownloadStatus(BookDownloadState(BookDownloadPhase.Partial, 1, 2), task).displayPhase)
    }

    @Test fun failuresStoreCategoriesNotSensitiveMessages() {
        val sensitive = WebRequestError("Cookie: session=secret", "https://private.invalid/chapter?token=secret", IOException("private body"))
        assertEquals(DownloadFailure.Network, downloadFailure(sensitive, DownloadStage.Body))
        assertEquals(DownloadFailure.Storage, downloadFailure(sensitive, DownloadStage.Storage))
        assertEquals(DownloadFailure.Verification, downloadFailure(sensitive.copy(kind = WebRequestErrorKind.VerificationRequired), DownloadStage.Body))
        assertEquals(DownloadFailure.Authentication, downloadFailure(sensitive.copy(kind = WebRequestErrorKind.AuthenticationRequired), DownloadStage.Details))
        assertEquals(DownloadFailure.SourceUnavailable, downloadFailure(sensitive.copy(kind = WebRequestErrorKind.SourceUnavailable), DownloadStage.Directory))
        assertEquals(DownloadFailure.Verification, downloadFailure(sensitive.copy(throwable =
            hnovel.content.SourceContentException(hnovel.content.ContentError.Certificate, "certificate")), DownloadStage.Body))
    }
}
