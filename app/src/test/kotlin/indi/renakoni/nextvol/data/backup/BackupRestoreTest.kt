package indi.renakoni.nextvol.data.backup

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.work.workDataOf
import androidx.work.ListenableWorker
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.bind
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.backup.model.LocalData
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.work.ImportDataWork
import indi.renakoni.nextvol.data.work.workerParameters
import indi.renakoni.nextvol.data.backup.BackupArchive
import indi.renakoni.nextvol.data.backup.BackupContent
import indi.renakoni.nextvol.data.backup.BackupKind
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLog
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BackupRestoreTest {
    @Test fun failedOverwriteMustPreserveTheExistingLibrary() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
        val file = context.cacheDir.resolve("review-backup.nvbackup")
        try {
            val coordinator = StatisticsWriteCoordinator()
            val stats = StatsRepository(db.bookRecordDao(), db.dailyCountDao(), mockk(), coordinator)
            val downloads = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
            val backup = BackupDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
                db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(),
                db.userReadingDataDao(), db.userDataDao(), mockk(relaxed = true), coordinator, stats, downloads)
            val old = SourceBookId(Identifier("review", "fixture"), "old-book")
            val incoming = SourceBookId(old.sourceId, "incoming-book")
            fun info(book: SourceBookId) = book.bind(BookInformation(id = book.remoteId, title = book.remoteId,
                author = "", description = "", publishingHouse = "", wordCount = WordCount(1),
                lastUpdated = LocalDateTime.of(2026, 9, 21, 0, 0), isComplete = false))
            db.bookInformationDao().insert(info(incoming))
            val incomingEntity = db.bookInformationDao().getEntity(incoming.storageKey)!!
            db.bookInformationDao().clear()
            db.bookInformationDao().insert(info(old))
            val payload = AppLocalData(localDataList = listOf(LocalData.empty().copy(bookInformationEntities = listOf(incomingEntity))),
                globalLocalData = LocalData.empty())
            backup.validateBackup(payload)
            BackupArchive.write(file, payload, BackupArchive.manifest(BackupKind.USER_DATA, setOf(BackupContent.BOOKSHELF)))
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "application/zip"
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            provider.attachInfo(context, ProviderInfo().apply { authority = "review-backup" })
            ShadowContentResolver.registerProviderInternal("review-backup", provider)
            // Simulate an actual database write failure after a valid backup has been decoded.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER review_write_failure BEFORE INSERT ON book_information BEGIN SELECT RAISE(ABORT, 'review injected write failure'); END")
            val worker = ImportDataWork(context, workerParameters(workDataOf("uri" to "content://review-backup/data", "overwrite" to true)), backup)
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            val failure = ShadowLog.getLogsForTag(ImportDataWork.TAG).last().throwable
            assertNotNull("The fixture must reach the injected database write failure", failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("review injected write failure") })
            assertNotNull("Failed overwrite erased the pre-existing book", db.bookInformationDao().getEntity(old.storageKey))
        } finally {
            db.close()
            file.delete()
        }
    }
}
