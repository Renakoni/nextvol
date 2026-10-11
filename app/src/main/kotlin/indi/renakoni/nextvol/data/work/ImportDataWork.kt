package indi.renakoni.nextvol.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.michaelbull.result.onErr
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.data.ExternalFiles
import indi.renakoni.nextvol.data.backup.BackupException
import indi.renakoni.nextvol.data.backup.BackupFailure
import indi.renakoni.nextvol.data.backup.BackupFiles
import indi.renakoni.nextvol.data.local.LocalDataManager
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

@HiltWorker
class ImportDataWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localDataManager: LocalDataManager
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "ImportDataWork"
    }

    override suspend fun doWork(): Result {
        val staged = inputData.getString(ExternalFiles.STAGED_BACKUP)?.let { ExternalFiles.backupFile(appContext, it) }
        val fileUri = inputData.getString("uri")?.let(Uri::parse)
        if (staged == null && fileUri == null) return Result.failure()
        var interrupted = false
        try {
            return importFile(fileUri, staged)
        } catch (cancelled: CancellationException) {
            interrupted = true
            throw cancelled
        } finally {
            // WorkManager can restart a stopped worker; retain its input until a terminal result.
            if (!interrupted) staged?.delete()
        }
    }

    private suspend fun importFile(fileUri: Uri?, staged: File?): Result {
        val overwrite = inputData.getBoolean("overwrite", false)
        val appLocalData = try {
            BackupFiles.read(applicationContext.cacheDir) {
                staged?.inputStream() ?: applicationContext.contentResolver.openInputStream(requireNotNull(fileUri))
                    ?: throw IOException("Cannot open backup source")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load file")
            e.printStackTrace()
            return Result.failure(workDataOf(BackupException.ERROR_KEY to (e as? BackupException)?.reason?.name))
        }
        if (appLocalData.version != localDataManager.currentAppDataVersion) {
            return Result.failure(workDataOf(BackupException.ERROR_KEY to BackupFailure.UNSUPPORTED_VERSION.name))
        }
        try {
            localDataManager.validateBackup(appLocalData)
        } catch (failure: IllegalArgumentException) {
            Log.e(TAG, "Invalid backup identities", failure)
            return Result.failure(workDataOf(BackupException.ERROR_KEY to BackupFailure.INVALID.name))
        }
        try {
            localDataManager.importAppLocalData(appLocalData, overwrite)
                .onErr {
                    Log.e(TAG, "Failed to import the data", it)
                    return Result.failure()
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Failed to import the data", failure)
            return Result.failure()
        }
        return Result.success()
    }
}
