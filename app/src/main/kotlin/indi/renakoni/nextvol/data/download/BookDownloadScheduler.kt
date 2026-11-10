package indi.renakoni.nextvol.data.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import indi.renakoni.nextvol.data.book.BookAliasStore
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.local.room.entity.BookDownloadEntity
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.work.CacheBookWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Manual and bookshelf downloads share ownership, cancellation and network constraints. */
@Singleton
class BookDownloadScheduler @Inject constructor(
    private val downloads: BookDownloadStore,
    private val workManager: WorkManager,
    private val aliases: BookAliasStore,
) {
    private val submissions = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    internal fun observeTask(workId: UUID): Flow<WorkInfo?> = flow {
        // A task ID is persisted before WorkManager finishes registering its worker.
        lock.withLock { }
        emitAll(workManager.getWorkInfoByIdFlow(workId))
    }

    fun enqueue(requested: SourceBookId): Flow<WorkInfo?> {
        if (LocalBookStore.isLocal(requested)) return flowOf(null)
        val submission = enqueueTask(requested, null, resumePrevious = false)
        return flow { emitAll(workManager.getWorkInfoByIdFlow(submission.await().workId)) }
    }

    suspend fun submit(
        requested: SourceBookId,
        chapterIds: List<String>? = null,
        resumePrevious: Boolean = true,
    ): DownloadSubmission {
        if (LocalBookStore.isLocal(requested)) return DownloadSubmission.Rejected(DownloadFailure.SourceUnavailable)
        if (chapterIds?.isEmpty() == true) return DownloadSubmission.Rejected(DownloadFailure.SelectionUnavailable)
        return try {
            enqueueTask(requested, chapterIds?.distinct(), resumePrevious).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DownloadSubmission.Rejected(submissionFailure(failure))
        }
    }

    private fun submissionFailure(failure: Exception) =
        if (failure is android.database.sqlite.SQLiteException)
            downloadFailure(io.nightfish.lightnovelreader.api.error.WebRequestError("", "", failure), DownloadStage.Storage)
        else DownloadFailure.Scheduling

    private fun enqueueTask(requested: SourceBookId, chapterIds: List<String>?, resumePrevious: Boolean) = run {
        val generation = downloads.generation()
        // Submit eagerly even when a caller does not collect the progress flow.
        submissions.async(start = CoroutineStart.UNDISPATCHED) { lock.withLock {
            val book = aliases.resolve(requested)
            val name = CacheBookWork.ofId(book.storageKey)
            val owner = downloads.entry(book)
            val previous = owner?.taskWorkId?.takeIf { it.isNotEmpty() }
                ?.let { workManager.getWorkInfoByIdFlow(UUID.fromString(it)).first() }
            val active = previous?.takeUnless { it.state.isFinished }
                ?: workManager.getWorkInfosForUniqueWorkFlow(name).first().firstOrNull { !it.state.isFinished }
            val revoked = owner?.taskStatus == DownloadTaskStatus.Cancelled.name
            if (active != null && !revoked) return@withLock DownloadSubmission.Accepted(active.id,
                owner?.takeIf { it.taskWorkId == active.id.toString() }?.taskState(active.state)
                    ?: DownloadTaskState(if (active.state == WorkInfo.State.RUNNING) DownloadTaskStatus.Running else DownloadTaskStatus.Queued),
                existing = true, selectionMatches = chapterIds == null || chapterIds.toSet() == owner?.selectedChapterIds())
            val request = OneTimeWorkRequestBuilder<CacheBookWork>()
                .addTag(CacheBookWork.generationTag(generation))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("bookId" to book.storageKey, "downloadGeneration" to generation, "persistedTask" to true))
                .build()
            downloads.queueTask(book, generation, request.id.toString(), chapterIds, resumePrevious)
            try {
                workManager.enqueueUniqueWork(name,
                    if (revoked) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request).await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                downloads.finishTask(BookDownloadStore.Task(book, generation, request.id.toString()), submissionFailure(failure))
                throw failure
            }
            DownloadSubmission.Accepted(request.id, DownloadTaskState(DownloadTaskStatus.Queued, DownloadStage.Details))
        } }
    }

    internal suspend fun resumeVerified(expected: BookDownloadEntity, verificationId: String? = null,
        interaction: Deferred<Unit>? = null, sourceIsCurrent: () -> Boolean) {
        val request = OneTimeWorkRequestBuilder<CacheBookWork>()
            .addTag(CacheBookWork.generationTag(expected.generation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("bookId" to expected.bookId, "downloadGeneration" to expected.generation,
                "persistedTask" to true, "sourceVerification" to verificationId))
            .build()
        val task = BookDownloadStore.Task(SourceBookId.fromStorageKey(expected.bookId), expected.generation, request.id.toString())
        var reserved = false
        try {
            val enqueued = lock.withLock {
                if (!sourceIsCurrent()) return@withLock false
                // Cancellation can arrive after Room writes ownership but before returning it.
                reserved = true
                if (!downloads.queueVerifiedTask(expected, request.id.toString())) {
                    reserved = false
                    return@withLock false
                }
                try {
                    workManager.enqueueUniqueWork(CacheBookWork.ofId(expected.bookId), ExistingWorkPolicy.REPLACE, request).await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    try { downloads.finishTask(task, submissionFailure(failure)) }
                    catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
                    return@withLock false
                }
                true
            }
            if (!enqueued || interaction == null) return
            coroutineScope {
                val finished = async { observeTask(request.id).first { it == null || it.state.isFinished } }
                try { select<Unit> {
                    interaction.onAwait { }
                    finished.onAwait { }
                } } finally { finished.cancel() }
            }
        } catch (cancelled: CancellationException) {
            if (interaction != null && reserved) withContext(NonCancellable) {
                downloads.pauseVerificationTask(task.copy(book = aliases.resolve(task.book)))
                workManager.cancelWorkById(request.id).await()
            }
            throw cancelled
        }
    }

    suspend fun dismiss(requested: SourceBookId, expectedWorkId: String? = null): Unit = lock.withLock {
        val book = aliases.resolve(requested)
        val workId = downloads.entry(book)?.taskWorkId?.takeIf { it.isNotEmpty() }
        if (expectedWorkId != null && workId != expectedWorkId) return@withLock
        downloads.dismissTask(book) // Revoke writes before asking the scheduler to stop.
        workId?.let { workManager.cancelWorkById(UUID.fromString(it)).await() }
        workManager.cancelUniqueWork(CacheBookWork.ofId(book.storageKey)).await()
        if (book != requested) workManager.cancelUniqueWork(CacheBookWork.ofId(requested.storageKey)).await()
    }
}
