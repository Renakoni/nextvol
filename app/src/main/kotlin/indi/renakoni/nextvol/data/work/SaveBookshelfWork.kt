package indi.renakoni.nextvol.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.data.backup.BackupArchive
import indi.renakoni.nextvol.data.backup.BackupContent
import indi.renakoni.nextvol.data.backup.BackupException
import indi.renakoni.nextvol.data.backup.BackupFiles
import indi.renakoni.nextvol.data.backup.BackupKind
import indi.renakoni.nextvol.data.backup.BackupDataManager
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.backup.model.LocalData
import indi.renakoni.nextvol.data.local.room.dao.BookshelfDao
import kotlinx.coroutines.CancellationException
import java.io.IOException

@HiltWorker
class SaveBookshelfWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val backupDataManager: BackupDataManager,
    private val bookshelfDao: BookshelfDao
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "ExportDataWork"
    }

    override suspend fun doWork(): Result {
        val id = inputData.getInt("bookshelfId", -1)
        val uri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val bookshelfEntityList =
            if (id != -1) bookshelfDao.getBookshelf(id)?.let(::listOf) ?: emptyList()
            else bookshelfDao.getAllBookshelves()
        if (bookshelfEntityList.isEmpty() && id != -1) {
            Log.e(TAG, "Bookshelf doesn't exit (id=$id)")
            return Result.failure()
        }
        val bookshelfIds = bookshelfEntityList.map { it.id }
        val bookshelfBookMetadataEntities = mutableListOf<String>().apply {
                for (entity in bookshelfEntityList) {
                    this.addAll(entity.allBookIds)
                }
            }.distinct()
            .mapNotNull{
                bookshelfDao.getBookshelfBookMetadataEntity(it)
            }.map { entity ->
                entity.copy(
                    bookShelfIds = entity.bookShelfIds.filter { bookshelfIds.contains(it) }
                )
            }
        val appLocalData = AppLocalData(
            version = backupDataManager.currentAppDataVersion,
            localDataList = listOf(
                backupDataManager.localFileReferences(bookshelfEntityList.flatMap { it.allBookIds }.toSet()).copy(
                    bookshelfEntities = bookshelfEntityList,
                    bookshelfBookMetadataEntities = bookshelfBookMetadataEntities
                )
            ),
            globalLocalData = LocalData.empty()
        )
        try {
            backupDataManager.validateBackup(appLocalData)
            val manifest = BackupArchive.manifest(BackupKind.BOOKSHELF, setOf(BackupContent.BOOKSHELF))
            BackupFiles.write(applicationContext.cacheDir, appLocalData, manifest) {
                applicationContext.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("Cannot open backup destination")
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file")
            e.printStackTrace()
            return Result.failure(workDataOf(BackupException.ERROR_KEY to (e as? BackupException)?.reason?.name))
        }
    }
}
