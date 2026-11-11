package indi.renakoni.nextvol.data.bangumi

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.InvalidationTracker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class BangumiSyncWork @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: BangumiRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if ("bangumi-recovery" in tags) {
            // Periodic recovery must also go through the 30-second batch, never write directly.
            if (repository.reconcile().isNotEmpty()) BangumiSyncScheduler.enqueue(WorkManager.getInstance(applicationContext))
            return Result.success()
        }
        return if (repository.syncAll()) Result.retry() else Result.success()
    }
}

@Singleton
class BangumiSyncScheduler @Inject constructor(
    private val repository: BangumiRepository,
    private val database: NextVolDatabase,
    private val workManager: WorkManager,
) {
    fun syncNow() {
        workManager.enqueueUniqueWork("bangumi-manual", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BangumiSyncWork>().setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            repository.accounts.load()
            var connected: Boolean? = null
            var previous = emptySet<String>()
            merge(changes(), repository.accounts.state.map { Unit }).sample(750).collect {
                try {
                    val hasAccount = repository.accounts.state.value.user != null
                    if (hasAccount != connected) {
                        // Recover process death between a reading commit and enqueue, only while connected.
                        if (hasAccount) workManager.enqueueUniquePeriodicWork("bangumi-recovery", ExistingPeriodicWorkPolicy.KEEP,
                            PeriodicWorkRequestBuilder<BangumiSyncWork>(15, TimeUnit.MINUTES)
                                .addTag("bangumi-recovery")
                                .setInitialDelay(15, TimeUnit.MINUTES)
                                .setConstraints(networkConstraint).build())
                        else {
                            workManager.cancelUniqueWork("bangumi-recovery")
                            workManager.cancelUniqueWork("bangumi-progress")
                            workManager.cancelUniqueWork("bangumi-manual")
                        }
                        connected = hasAccount
                    }
                    val pending = repository.reconcile(reuseCatalog = true)
                    if ((pending - previous).isNotEmpty()) enqueue(workManager)
                    previous = pending
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // Disconnect invalidates an in-flight account; the next account emission reconciles afresh.
                }
            }
        }
    }

    private fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer("bangumi_binding", "user_reading_data", "book_information", "volume", "chapter_information") {
            override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
        }
        database.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { database.invalidationTracker.removeObserver(observer) }
    }.conflate()

    companion object {
        private val networkConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        internal fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniqueWork("bangumi-progress", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<BangumiSyncWork>().setConstraints(networkConstraint)
                    .setInitialDelay(30, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
