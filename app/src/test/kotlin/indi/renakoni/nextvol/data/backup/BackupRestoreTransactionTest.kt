package indi.renakoni.nextvol.data.backup

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.book.bind
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.backup.model.LocalData
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.*
import indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.storage.StorageUsageRepository
import io.mockk.coEvery
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Real file-backed Room, download files and transaction failures; no user data or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BackupRestoreTransactionTest {
    @get:Rule val directory = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = directory.root.resolve("files").apply { mkdirs() }
    } }
    private val old = SourceBookId(Identifier("backup", "fixture"), "old")
    private val incoming = SourceBookId(old.sourceId, "incoming")
    private val later = SourceBookId(old.sourceId, "later")
    private val chapter = SourceChapterId(old, "1")
    private val now = LocalDateTime.of(2026, 9, 21, 12, 0)
    private val image = SourceImage(old, "https://fixture.invalid/image.png")
    private val storage = mockk<StorageUsageRepository>(relaxed = true)
    private lateinit var db: NextVolDatabase
    private lateinit var downloads: BookDownloadStore
    private lateinit var stats: StatsRepository
    private lateinit var backup: BackupDataManager
    private lateinit var oldFile: File
    private lateinit var attempt: BookDownloadStore.Attempt

    private fun openLibrary() {
        db = Room.databaseBuilder(context, NextVolDatabase::class.java, directory.root.resolve("library.db").path)
            .allowMainThreadQueries().build()
        val coordinator = StatisticsWriteCoordinator()
        stats = StatsRepository(db.bookRecordDao(), db.dailyCountDao(), mockk(), coordinator)
        downloads = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
        backup = BackupDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
            db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(),
            db.userReadingDataDao(), db.userDataDao(), storage, coordinator, stats, downloads)
    }

    @Before fun setUp() = runBlocking {
        openLibrary()
        db.bookInformationDao().insert(information(old))
        db.bookshelfDao().insertBookshelf(BookshelfEntity(1, "My shelf", "default", autoCache = false,
            systemUpdateReminder = false, allBookIds = listOf(old.storageKey),
            pinnedBookIds = listOf(old.storageKey), updatedBookIds = emptyList()))
        db.bookshelfDao().insertBookshelfBookMetadata(BookshelfBookMetadataEntity(old.storageKey, now, listOf(1)))
        db.userReadingDataDao().insert(UserReadingDataEntity(old.storageKey, now, 12, .4f,
            chapter.storageKey, "Old chapter", mapOf(chapter.storageKey to .4f), mapOf(chapter.storageKey to .6f)))
        stats.updateReadingStatistics(ReadingStatsUpdate(old.storageKey, secondDelta = 120,
            localTime = LocalTime.NOON, readEventDelta = 1))
        db.userDataDao().insert("fixture/setting", "fixture", "String", "old")
        attempt = downloads.begin(old, downloads.generation(), "old-attempt")
        downloads.saveImage(attempt, image.uri, false, byteArrayOf(1, 2, 3))
        downloads.saveChapter(attempt, chapter.bind(ChapterContent("1", "Old chapter",
            ContentBuilder().simpleText("Saved offline text").build())), "signature", listOf(image.uri))
        oldFile = downloads.image(image)!!
    }

    @After fun close() { db.close() }

    private fun information(book: SourceBookId) = BookInformationEntity(book.storageKey, book.remoteId,
        "", Uri.EMPTY, "", "", emptyList(), "", WordCount(1), now, false)

    private fun payload(vararg books: SourceBookId) = AppLocalData(
        localDataList = books.map { LocalData.empty().copy(bookInformationEntities = listOf(information(it))) },
        globalLocalData = LocalData.empty().copy(userDataEntities = listOf(
            UserDataEntity("fixture/setting", "fixture", "String", "incoming"),
            UserDataEntity("fixture/new-setting", "fixture", "String", "new"))))

    private fun failAt(book: SourceBookId) {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER injected_restore_failure BEFORE INSERT ON book_information
            WHEN NEW.id = '${book.storageKey}'
            BEGIN SELECT RAISE(ABORT, 'injected restore write failure'); END
        """.trimIndent())
    }

    private suspend fun assertOldLibrary() {
        assertEquals(information(old), db.bookInformationDao().getEntity(old.storageKey))
        assertEquals(listOf(old.storageKey), db.bookshelfDao().getBookshelf(1)!!.allBookIds)
        assertEquals(listOf(1), db.bookshelfDao().getBookshelfBookMetadataEntity(old.storageKey)!!.bookShelfIds)
        assertEquals(.4f, db.userReadingDataDao().getEntity(old.storageKey)!!.readingProgress)
        assertEquals(.6f, db.userReadingDataDao().getEntity(old.storageKey)!!.maxChapterReadingProgressMap[chapter.storageKey])
        assertEquals(120, db.bookRecordDao().getBookRecordByIdAndDate(old.storageKey, LocalDate.now())!!.seconds)
        assertEquals(2, db.dailyCountDao().getByDate(LocalDate.now())!!.timeCount.getMinute(12))
        assertEquals("Old chapter", db.chapterContentDao().get(chapter.storageKey)!!.title)
        assertEquals("old-attempt", db.bookDownloadDao().get(old.storageKey)!!.attempt)
        assertArrayEquals(byteArrayOf(1, 2, 3), downloads.image(image)!!.readBytes())
        assertTrue(oldFile.isFile)
        assertEquals(0L, downloads.generation())
        assertEquals("old", db.userDataDao().get("fixture/setting"))
        assertNull(db.userDataDao().get("fixture/new-setting"))
        assertNull(db.bookInformationDao().getEntity(incoming.storageKey))
        assertNull(db.bookInformationDao().getEntity(later.storageKey))
    }

    private suspend fun expectWriteFailure(data: AppLocalData, overwrite: Boolean) {
        backup.validateBackup(data)
        val failure = runCatching { backup.importAppLocalData(data, overwrite) }.exceptionOrNull()
        assertNotNull("Fixture must reach the database write failure", failure)
        assertTrue(generateSequence(failure) { it.cause }.any {
            it.message.orEmpty().contains("injected restore write failure")
        })
    }

    @Test fun firstWriteFailureKeepsEveryOldRowAndOfflineFile() = runBlocking {
        failAt(incoming)
        expectWriteFailure(payload(incoming), overwrite = true)
        assertOldLibrary()
        downloads.finish(attempt, true)
    }

    @Test fun laterBatchFailureRollsBackEarlierBatchesAndSettingsAcrossReopen() = runBlocking {
        failAt(later)
        expectWriteFailure(payload(incoming, later), overwrite = true)
        db.close()
        openLibrary()
        downloads.prepare()
        assertOldLibrary()
    }

    @Test fun mergeFailureAlsoRollsBackTheWholeBackup() = runBlocking {
        failAt(later)
        expectWriteFailure(payload(incoming, later), overwrite = false)
        assertOldLibrary()
    }

    @Test fun failedRestoreRetainsBufferedReadingSeconds() = runBlocking {
        stats.accumulateBookReadTime(old.storageKey, 15)
        failAt(later)
        expectWriteFailure(payload(incoming, later), overwrite = true)
        stats.accumulateBookReadTime(old.storageKey, -1)
        assertEquals(135, db.bookRecordDao().getBookRecordByIdAndDate(old.storageKey, LocalDate.now())!!.seconds)
    }

    @Test fun successfulOverwriteCommitsAllPartsAndRevokesPreviousAttempts() = runBlocking {
        stats.accumulateBookReadTime(old.storageKey, 15)
        withTimeout(5000) { assertTrue(backup.importAppLocalData(payload(incoming, later), overwrite = true).isOk) }
        assertNull(db.bookInformationDao().getEntity(old.storageKey))
        assertNotNull(db.bookInformationDao().getEntity(incoming.storageKey))
        assertNotNull(db.bookInformationDao().getEntity(later.storageKey))
        assertTrue(db.bookshelfDao().getAllBookshelves().isEmpty())
        assertNull(db.chapterContentDao().get(chapter.storageKey))
        assertEquals("old", db.userDataDao().get("fixture/setting"))
        assertEquals("new", db.userDataDao().get("fixture/new-setting"))
        assertEquals(1L, downloads.generation())
        assertFalse(oldFile.exists())
        stats.accumulateBookReadTime(old.storageKey, -1)
        assertNull(db.bookRecordDao().getBookRecordByIdAndDate(old.storageKey, LocalDate.now()))
        assertTrue(runCatching { downloads.finish(attempt, true) }.exceptionOrNull() is CancellationException)
        db.close()
        openLibrary()
        assertNotNull(db.bookInformationDao().getEntity(later.storageKey))
    }

    @Test fun mergeKeepsOldDownloadsAndBufferedTime() = runBlocking {
        stats.accumulateBookReadTime(old.storageKey, 15)
        assertTrue(backup.importAppLocalData(payload(incoming, later)).isOk)
        assertNotNull(db.bookInformationDao().getEntity(old.storageKey))
        assertNotNull(db.bookInformationDao().getEntity(later.storageKey))
        assertEquals(0L, downloads.generation())
        assertArrayEquals(byteArrayOf(1, 2, 3), downloads.image(image)!!.readBytes())
        stats.accumulateBookReadTime(old.storageKey, -1)
        assertEquals(135, db.bookRecordDao().getBookRecordByIdAndDate(old.storageKey, LocalDate.now())!!.seconds)
        downloads.finish(attempt, true)
    }

    @Test fun invalidVersionAndIdentityAreRejectedBeforeAnyChange() = runBlocking {
        assertTrue(backup.importAppLocalData(payload(incoming).copy(version = 99), overwrite = true).isErr)
        assertOldLibrary()
        val invalid = payload(incoming).let {
            it.copy(localDataList = listOf(LocalData.empty().copy(bookInformationEntities =
                listOf(information(incoming).copy(id = "invalid-unscoped-id")))))
        }
        assertTrue(runCatching { backup.importAppLocalData(invalid, overwrite = true) }.exceptionOrNull() is IllegalArgumentException)
        assertOldLibrary()
    }

    @Test fun cancellationBeforeCommitRollsBackAndRetainsBufferedTime() = runBlocking {
        stats.accumulateBookReadTime(old.storageKey, 15)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { storage.invalidateSnapshot() } coAnswers { entered.complete(Unit); release.await() }
        val restore = launch(Dispatchers.Default) { backup.importAppLocalData(payload(incoming), overwrite = true) }
        withTimeout(5000) { entered.await() }
        restore.cancel()
        release.complete(Unit)
        withTimeout(5000) { restore.join() }
        assertOldLibrary()
        stats.accumulateBookReadTime(old.storageKey, -1)
        assertEquals(135, db.bookRecordDao().getBookRecordByIdAndDate(old.storageKey, LocalDate.now())!!.seconds)
    }

    @Test fun restoreSerializesLateDownloadAndStatisticsWithoutNestedLockDeadlock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { storage.invalidateSnapshot() } coAnswers { entered.complete(Unit); release.await() }
        val restore = async(Dispatchers.Default) { backup.importAppLocalData(payload(incoming), overwrite = true) }
        withTimeout(5000) { entered.await() }
        val lateDownload = async(Dispatchers.Default) { runCatching { downloads.finish(attempt, true) } }
        val reading = async(Dispatchers.Default) {
            stats.accumulateBookReadTime(incoming.storageKey, 15)
            stats.accumulateBookReadTime(incoming.storageKey, -1)
        }
        val export = async(Dispatchers.Default) { backup.exportAppLocalData() }
        release.complete(Unit)
        withTimeout(5000) {
            assertTrue(restore.await().isOk)
            assertTrue(lateDownload.await().exceptionOrNull() is CancellationException)
            reading.await()
            assertTrue(export.await().isOk)
        }
        assertNull(db.bookRecordDao().getBookRecordByIdAndDate(old.storageKey, LocalDate.now()))
        assertEquals(15, db.bookRecordDao().getBookRecordByIdAndDate(incoming.storageKey, LocalDate.now())!!.seconds)
    }

    private suspend fun legacyPayload(): AppLocalData {
        val originalImage = Uri.parse("content://backup-fixture/legacy-image")
        shadowOf(context.contentResolver).registerInputStreamSupplier(originalImage) { byteArrayOf(4, 5, 6).inputStream() }
        val volumes = incoming.bind(BookVolumes(incoming.remoteId,
            listOf(Volume("v", "Volume", listOf(ChapterInformation("1", "Incoming chapter"))))))
        db.bookInformationDao().insert(information(incoming))
        db.bookVolumesDao().insertVolume(incoming.storageKey, volumes)
        db.chapterContentDao().cache(SourceChapterId(incoming, "1").bind(ChapterContent("1", "Incoming chapter",
            ContentBuilder().image(originalImage).build())))
        val result = backup.exportAppLocalData().get()!!.localDataList.single().let { part ->
            part.copy(bookInformationEntities = part.bookInformationEntities.filter { it.id == incoming.storageKey },
                chapterContentEntities = part.chapterContentEntities.filter { it.id != chapter.storageKey },
                bookRecordEntities = emptyList(), dailyCountEntities = emptyList(), bookshelfEntities = emptyList(),
                bookshelfBookMetadataEntities = emptyList(), userReadingDataEntities = emptyList(),
                bookDownloadEntities = emptyList(), downloadedChapterEntities = emptyList(),
                userDataEntities = listOf(UserDataEntity(UserDataPath.CompletedDownloadBookList.path,
                    "", "StringList", "CACHE|${incoming.storageKey}")))
        }
        db.bookInformationDao().deleteByIds(listOf(incoming.storageKey))
        db.bookVolumesDao().deleteByBookIds(listOf(incoming.storageKey))
        db.chapterContentDao().deleteByIds(listOf(SourceChapterId(incoming, "1").storageKey))
        return AppLocalData(localDataList = listOf(result), globalLocalData = LocalData.empty())
    }

    @Test fun legacyImageWriteFailurePreservesOldRowsAndFilesAndCanRetry() = runBlocking {
        val data = legacyPayload()
        val obstruction = context.filesDir.resolve("book-downloads/1/${incoming.fileKey}")
        coEvery { storage.invalidateSnapshot() } coAnswers {
            obstruction.parentFile!!.mkdirs()
            obstruction.writeText("not a directory")
        }
        val failure = runCatching { backup.importAppLocalData(data, overwrite = true) }.exceptionOrNull()
        assertNotNull("The real image commit must fail", failure)
        assertTrue("Fixture must reach the obstructed image directory", obstruction.isFile)
        assertOldLibrary()
        coEvery { storage.invalidateSnapshot() } returns Unit
        db.close()
        openLibrary()
        downloads.prepare()
        assertFalse(obstruction.exists())
        assertTrue(backup.importAppLocalData(data, overwrite = true).isOk)
        val uri = "content://backup-fixture/legacy-image"
        assertArrayEquals(byteArrayOf(4, 5, 6), downloads.image(SourceImage(incoming, uri))!!.readBytes())
        assertFalse(oldFile.exists())
    }

    @Test fun reconstructionCleansAbandonedGenerationsButKeepsOwnedFiles() = runBlocking {
        val abandoned = context.filesDir.resolve("book-downloads/1/abandoned").apply {
            parentFile!!.mkdirs(); writeText("uncommitted staging")
        }
        db.close()
        openLibrary()
        downloads.prepare()
        assertFalse(abandoned.exists())
        assertOldLibrary()
    }
}
