package indi.renakoni.nextvol.data.bookmark

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.backup.BackupDataManager
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.backup.model.LocalData
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.work.ExportDataWork
import indi.renakoni.nextvol.data.work.workerParameters
import indi.renakoni.nextvol.data.backup.BackupFiles
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalSerializationApi::class)
class ReadingBookmarkTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "reading-bookmarks-test"
    private val book = SourceBookId(Identifier("fixture", "one"), "same-book")
    private val other = SourceBookId(Identifier("fixture", "two"), "same-book")
    private lateinit var db: NextVolDatabase
    private fun bookmark(owner: SourceBookId = book, offset: Int = 12) = ReadingBookmark(
        bookId = owner.storageKey, chapterId = SourceChapterId(owner, "1").storageKey,
        chapterTitle = "Chapter", componentIndex = 0, offset = offset,
        fingerprint = "a".repeat(64), preview = "Recognizable text", progress = .4f)
    private fun open() = Room.databaseBuilder(context, NextVolDatabase::class.java, name)
        .allowMainThreadQueries().addMigrations(NextVolDatabase.MIGRATION_21_22, NextVolDatabase.MIGRATION_22_23, NextVolDatabase.MIGRATION_23_24, NextVolDatabase.MIGRATION_24_25, NextVolDatabase.MIGRATION_25_26, NextVolDatabase.MIGRATION_26_27, NextVolDatabase.MIGRATION_27_28, NextVolDatabase.MIGRATION_28_29).build()
    private fun backup(): BackupDataManager {
        val coordinator = StatisticsWriteCoordinator()
        val stats = StatsRepository(db.bookRecordDao(), db.dailyCountDao(), mockk(), coordinator)
        return BackupDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
            db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(),
            db.userReadingDataDao(), db.userDataDao(), mockk(relaxed = true), coordinator, stats,
            BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry())))
    }
    @Before fun create() { context.deleteDatabase(name); db = open() }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun positionsDeduplicateSourcesStaySeparateAndDeletionSurvivesReopen() = runBlocking {
        val repository = ReadingBookmarkRepository(db)
        val saved = bookmark()
        assertTrue(repository.add(saved))
        assertFalse(repository.add(bookmark()))
        assertTrue(repository.add(bookmark(other)))
        assertTrue(repository.add(bookmark(offset = 90)))
        assertEquals(2, repository.observe(book).first().size)
        repository.delete(other, saved.id)
        assertEquals(2, repository.observe(book).first().size)
        repository.delete(book, saved.id)
        db.close(); db = open()
        assertEquals(listOf(90), db.readingBookmarkDao().observe(book.storageKey).first().map { it.offset })
        assertEquals(1, db.readingBookmarkDao().observe(other.storageKey).first().size)
        assertTrue(db.bookshelfDao().getAllBookshelfIds().isEmpty())
        assertNull(db.userReadingDataDao().getEntity(book.storageKey))
    }

    @Test fun version21MigrationPreservesOtherTablesAndCreatesValidatedBookmarkSchema() = runBlocking {
        db.userDataDao().insert("fixture/keep", "fixture", "String", "saved")
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE local_book_file_manifest")
            it.execSQL("DROP TABLE reading_bookmark")
            it.execSQL("ALTER TABLE imported_book RENAME TO imported_book_new")
            it.execSQL("CREATE TABLE imported_book (bookId TEXT NOT NULL PRIMARY KEY)")
            it.execSQL("INSERT INTO imported_book SELECT bookId FROM imported_book_new")
            it.execSQL("DROP TABLE imported_book_new")
            indi.renakoni.nextvol.data.download.restorePre25DownloadSchema(it::execSQL)
            it.version = 21
        }
        db = open()
        assertTrue(db.userDataDao().getAllEntities().any { it.path == "fixture/keep" })
        assertTrue(ReadingBookmarkRepository(db).add(bookmark()))
    }

    @Test fun actualWorkerOptionControlsBackupAndBothOldAndNewCborRestore() = runBlocking {
        val saved = bookmark()
        db.readingBookmarkDao().insert(saved)
        val manager = backup()
        for (include in listOf(false, true)) {
            val bytes = ByteArrayOutputStream()
            val uri = Uri.parse("content://bookmark-export/$include")
            shadowOf(context.contentResolver).registerOutputStream(uri, bytes)
            val worker = ExportDataWork(context, workerParameters(workDataOf("uri" to uri.toString(),
                "exportBookmark" to include, "exportLocalBookCache" to false, "exportBookshelf" to false,
                "exportReadingData" to false, "exportSetting" to false)), manager)
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            val decoded = BackupFiles.read(context.cacheDir) { bytes.toByteArray().inputStream() }
            assertEquals(if (include) listOf(saved) else emptyList(), decoded.localDataList.flatMap { it.readingBookmarks })
        }
        val newBackup = Cbor.decodeFromByteArray<AppLocalData>(Cbor.encodeToByteArray(manager.exportAppLocalData().get()!!))
        db.readingBookmarkDao().clear()
        assertTrue(manager.importAppLocalData(newBackup).isOk)
        assertTrue(manager.importAppLocalData(newBackup).isOk)
        assertEquals(listOf(saved), db.readingBookmarkDao().all())
        // Default fields are omitted: this has the same absent-bookmark shape as an old backup.
        val oldBytes = Cbor.encodeToByteArray(AppLocalData(localDataList = listOf(LocalData.empty()), globalLocalData = LocalData.empty()))
        assertFalse(oldBytes.toString(Charsets.ISO_8859_1).contains("readingBookmarks"))
        assertTrue(manager.importAppLocalData(Cbor.decodeFromByteArray(oldBytes)).isOk)
        assertEquals(listOf(saved), db.readingBookmarkDao().all())
    }

    @Test fun invalidIdentitiesAndMidRestoreFailurePreserveExistingBookmarks() = runBlocking {
        val saved = bookmark()
        db.readingBookmarkDao().insert(saved)
        val manager = backup()
        val invalid = saved.copy(chapterId = SourceChapterId(other, "1").storageKey)
        assertThrows(IllegalArgumentException::class.java) { manager.validateBackup(AppLocalData(
            localDataList = listOf(LocalData.empty().copy(readingBookmarks = listOf(invalid))), globalLocalData = LocalData.empty())) }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_bookmark BEFORE INSERT ON reading_bookmark BEGIN SELECT RAISE(ABORT, 'injected bookmark failure'); END")
        val incoming = AppLocalData(localDataList = listOf(LocalData.empty().copy(readingBookmarks = listOf(bookmark(other)))), globalLocalData = LocalData.empty())
        assertTrue(runCatching { manager.importAppLocalData(incoming, overwrite = true) }.isFailure)
        assertEquals(listOf(saved), db.readingBookmarkDao().all())
    }
}
