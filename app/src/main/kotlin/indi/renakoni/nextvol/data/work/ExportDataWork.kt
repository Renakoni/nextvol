package indi.renakoni.nextvol.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.data.backup.BackupArchive
import indi.renakoni.nextvol.data.backup.BackupContent
import indi.renakoni.nextvol.data.backup.BackupException
import indi.renakoni.nextvol.data.backup.BackupFiles
import indi.renakoni.nextvol.data.backup.BackupKind
import indi.renakoni.nextvol.data.backup.BackupDataManager
import kotlinx.coroutines.CancellationException
import java.io.IOException

@HiltWorker
class ExportDataWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupDataManager: BackupDataManager
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "ExportDataWork"
    }

    override suspend fun doWork(): Result {
        val fileUri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val exportLocalBookCache = inputData.getBoolean("exportLocalBookCache", true)
        val exportBookshelf = inputData.getBoolean("exportBookshelf", true)
        val exportReadingData = inputData.getBoolean("exportReadingData", true)
        val exportSetting = inputData.getBoolean("exportSetting", true)
        val exportBookmark = inputData.getBoolean("exportBookmark", true)
        val manifest = BackupArchive.manifest(BackupKind.USER_DATA, buildSet {
            if (exportLocalBookCache) add(BackupContent.CHAPTER_CACHE)
            if (exportBookshelf) add(BackupContent.BOOKSHELF)
            if (exportReadingData) add(BackupContent.READING_DATA)
            if (exportSetting) add(BackupContent.SETTINGS)
            if (exportBookmark) add(BackupContent.BOOKMARKS)
        })
        backupDataManager.exportAppLocalData(
            localBookCache = exportLocalBookCache,
            bookshelf = exportBookshelf,
            readingRecord = exportReadingData,
            bookmark = exportBookmark,
            settings = exportSetting
        ).andThen { appLocalData ->
            runCatching {
                backupDataManager.validateBackup(appLocalData)
                BackupFiles.write(applicationContext.cacheDir, appLocalData, manifest) {
                    applicationContext.contentResolver.openOutputStream(fileUri, "wt")
                        ?: throw IOException("Cannot open backup destination")
                }
            }
        }.onErr {
            if (it is CancellationException) throw it
            Log.e(TAG, "Failed to get AppLocalData")
            it.printStackTrace()
            return Result.failure(workDataOf(BackupException.ERROR_KEY to (it as? BackupException)?.reason?.name))
        }
        return Result.success()
    }
}
