package indi.renakoni.nextvol.data.local

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.backup.BackupDataManager
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate
import indi.renakoni.nextvol.data.work.SaveBookshelfWork
import indi.renakoni.nextvol.data.work.workerParameters
import indi.renakoni.nextvol.data.backup.BackupArchive
import indi.renakoni.nextvol.data.backup.BackupFiles
import indi.renakoni.nextvol.utils.ofId
import com.github.michaelbull.result.get
import io.mockk.coEvery
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalSerializationApi::class, ExperimentalCoroutinesApi::class)
class SourceIdentityRoomTest {
    private lateinit var db: NextVolDatabase
    private lateinit var local: LocalBookDataSource
    private lateinit var shelves: BookshelfRepository
    private lateinit var stats: StatsRepository
    private lateinit var backup: BackupDataManager
    private lateinit var downloads: BookDownloadStore
    private val a = SourceBookId(Identifier("site", "a"), "123")
    private val b = SourceBookId(Identifier("site", "b"), "123")
    private val other = SourceBookId(a.sourceId, "456")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java)
            .allowMainThreadQueries().build()
        local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), BookAliasStore(db))
        downloads = BookDownloadStore(RuntimeEnvironment.getApplication(), db, ContentJsonDecoder(ContentComponentRegistry()))
        val scheduler = indi.renakoni.nextvol.data.download.BookDownloadScheduler(downloads, mockk(), local.aliases)
        shelves = BookshelfRepository(db.bookshelfDao(), scheduler, indi.renakoni.nextvol.data.web.WebSourceRegistry(), local.aliases)
        val coordinator = StatisticsWriteCoordinator()
        stats = StatsRepository(db.bookRecordDao(), db.dailyCountDao(), mockk(), coordinator)
        backup = BackupDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
            db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(),
            db.userReadingDataDao(), db.userDataDao(), mockk(relaxed = true), coordinator, stats, downloads)
    }

    @After fun tearDown() {
        try { db.close() } finally { Dispatchers.resetMain() }
    }

    @Test fun missingDirectoryIsACacheMissRatherThanAnEmptySuccess() = runBlocking {
        assertNull(db.bookVolumesDao().getBookVolumes(a.storageKey))
        assertNull(local.getBookVolumes(a.storageKey))
    }

    @Test fun emptyVolumeDirectoryIsACacheMiss() = runBlocking {
        val volumes = a.bind(BookVolumes(a.remoteId, listOf(Volume("empty", "empty", emptyList()))))
        local.updateBookVolumes(volumes)
        assertEquals(1, db.bookVolumesDao().getVolumeEntitiesByBookId(a.storageKey).size)
        assertNull(db.bookVolumesDao().getBookVolumes(a.storageKey))
        assertNull(local.getBookVolumes(a.storageKey))
    }

    @Test fun emptyVolumeAlongsideReadableChaptersRemainsCached() = runBlocking {
        val volumes = a.bind(BookVolumes(a.remoteId, listOf(Volume("empty", "empty", emptyList()),
            Volume("readable", "readable", listOf(ChapterInformation("one", "One"))))))
        local.updateBookVolumes(volumes)
        assertEquals(volumes, db.bookVolumesDao().getBookVolumes(a.storageKey))
        assertEquals(volumes, local.getBookVolumes(a.storageKey))
    }

    private fun info(book: SourceBookId, title: String) = book.bind(BookInformation(
        id = book.remoteId, title = title, author = "author", description = "", publishingHouse = "",
        wordCount = WordCount(1), lastUpdated = LocalDateTime.of(2026, 9, 8, 0, 0), isComplete = false))

    private suspend fun save(book: SourceBookId, title: String) {
        local.updateBookInformation(info(book, title))
        local.updateBookVolumes(book.bind(BookVolumes(book.remoteId,
            listOf(Volume("1", "volume", listOf(ChapterInformation("9", title)))))))
        local.updateChapterContent(SourceChapterId(book, "9").bind(
            ChapterContent("9", title, JsonObject(emptyMap()), nextChapter = "10")))
        local.updateUserReadingData(book.storageKey) {
            it.copyWithUpdatedChapterReadingProgress("9", 0.5f).copy(lastReadChapterId = "9", totalReadTime = 30)
        }
    }

    @Test fun identicalTitleAndAuthorAreTwoBooksInOneShelf() = runBlocking {
        save(a, "Same title"); save(b, "Same title")
        shelves.addBookshelf(Bookshelf(id = 1, name = "mixed"))
        shelves.addBookIntoBookShelf(1, info(a, "Same title"))
        shelves.addBookIntoBookShelf(1, info(b, "Same title"))
        val ids = shelves.getBookshelf(1)!!.allBookIds
        assertEquals(2, ids.size)
        assertEquals(setOf(a.storageKey, b.storageKey), ids.toSet())
        assertEquals("author", local.getBookInformation(a.storageKey)!!.author)
        assertEquals("author", local.getBookInformation(b.storageKey)!!.author)
        local.updateUserReadingData(a.storageKey) { it.copy(totalReadTime = 99) }
        assertEquals(30, local.getUserReadingData(b.storageKey).totalReadTime)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun explicitReadingCacheClearRetainsBookshelvesProgressAndSettings() = runBlocking {
        save(a, "Same title"); save(b, "Same title")
        shelves.addBookshelf(Bookshelf(id = 1, name = "mixed"))
        shelves.addBookIntoBookShelf(1, info(a, "Same title"))
        shelves.addBookIntoBookShelf(1, info(b, "Same title"))
        val reading = local.getUserReadingData(a.storageKey)
        val context = RuntimeEnvironment.getApplication()
        val data = indi.renakoni.nextvol.data.userdata.UserDataRepository(db.userDataDao())
        data.stringUserData("fixture/login").set("retained-login")
        val searchCache = java.io.File(context.cacheDir, "wenku8-search/catalog.tsv.gz").apply {
            parentFile!!.mkdirs()
            writeText("search metadata")
        }
        val cache = coil3.disk.DiskCache.Builder().directory(okio.Path.Companion.run {
            java.nio.file.Files.createTempDirectory("manual-image-cache").toString().toPath()
        }).maxSizeBytes(1024 * 1024).build()
        cache.openEditor("image")!!.let { editor ->
            cache.fileSystem.write(editor.metadata) { }
            cache.fileSystem.write(editor.data) { writeUtf8("cached-image") }
            editor.commit()
        }
        val loader = coil3.ImageLoader.Builder(context).diskCache(cache).build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        val models = androidx.lifecycle.ViewModelStore()
        val model = indi.renakoni.nextvol.ui.storagemanager.StorageManagerViewModel(
            mockk {
                coEvery { getCachedSnapshot() } returns null
                coEvery { refreshSnapshot() } returns indi.renakoni.nextvol.data.storage.StorageUsageSnapshot()
            }, downloads, mockk(), mockk(), indi.renakoni.nextvol.defaultplugin.wenku8.search.Wenku8SearchCatalog(context)
        )
        val modelJob = model.viewModelScope.coroutineContext.job
        models.put("storage", model)
        try {
            assertNotNull(cache.openSnapshot("image")?.also { it.close() })
            val usage = indi.renakoni.nextvol.data.storage.StorageUsageRepository(context, db, data).refreshSnapshot()
            assertEquals(searchCache.length(), usage.searchCacheBytes)
            assertEquals(usage.readingContentBytes + usage.imageCacheBytes + usage.searchCacheBytes, usage.readingCacheBytes)
            model.clearReadingCache()
            assertFalse(searchCache.exists())
            assertTrue(db.chapterContentDao().getAllEntities().isEmpty())
            assertNull(cache.openSnapshot("image"))
            assertEquals(setOf(a.storageKey, b.storageKey), shelves.getBookshelf(1)!!.allBookIds.toSet())
            assertEquals(reading, local.getUserReadingData(a.storageKey))
            assertEquals("Same title", local.getBookInformation(a.storageKey)!!.title)
            assertEquals(1, local.getBookVolumes(a.storageKey)!!.volumes.size)
            assertEquals("retained-login", data.stringUserData("fixture/login").get())
        } finally {
            try {
                models.clear()
                // Clearing cancels observers but does not wait for their in-flight Room queries.
                withTimeout(5_000) { modelJob.join() }
            } finally { loader.shutdown(); cache.shutdown(); coil3.SingletonImageLoader.reset() }
        }
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun readingCacheClearKeepsAnExistingExplicitDownload() = runBlocking {
        save(a, "Downloaded"); save(b, "Read online")
        val context = RuntimeEnvironment.getApplication()
        val data = indi.renakoni.nextvol.data.userdata.UserDataRepository(db.userDataDao())
        data.stringUserData(UserDataPath.CompletedDownloadBookList.path).set("CACHE|${a.storageKey}")
        val loader = coil3.ImageLoader.Builder(context).diskCache(null).build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        val models = androidx.lifecycle.ViewModelStore()
        val model = indi.renakoni.nextvol.ui.storagemanager.StorageManagerViewModel(
            mockk {
                coEvery { getCachedSnapshot() } returns null
                coEvery { refreshSnapshot() } returns indi.renakoni.nextvol.data.storage.StorageUsageSnapshot()
            }, downloads, mockk(), mockk(), indi.renakoni.nextvol.defaultplugin.wenku8.search.Wenku8SearchCatalog(context)
        )
        val modelJob = model.viewModelScope.coroutineContext.job
        models.put("storage", model)
        try {
            model.clearReadingCache()
            assertNotNull("Explicit downloads must survive reading-cache cleanup",
                local.getChapterContent(SourceChapterId(a, "9").storageKey))
            assertNull(local.getChapterContent(SourceChapterId(b, "9").storageKey))
            assertEquals(30, local.getUserReadingData(a.storageKey).totalReadTime)
        } finally {
            models.clear()
            withTimeout(5_000) { modelJob.join() }
            loader.shutdown(); coil3.SingletonImageLoader.reset()
        }
    }

    @Test fun sameIdsKeepBooksVolumesChaptersAndDeletionIndependent() = runBlocking {
        for ((book, title) in listOf(a to "A", b to "B", other to "Other")) save(book, title)
        for ((book, title) in listOf(a to "A", b to "B", other to "Other")) {
            val chapter = SourceChapterId(book, "9")
            assertEquals(title, local.getBookInformation(book.storageKey)!!.title)
            assertEquals(chapter.storageKey, local.getBookVolumes(book.storageKey)!!.volumes.single().chapters.single().id)
            assertEquals(SourceChapterId(book, "10").storageKey, local.getChapterContent(chapter.storageKey)!!.nextChapter)
            assertEquals(chapter.storageKey, local.getUserReadingDataFlow(book.storageKey).first().lastReadChapterId)
        }
        db.chapterContentDao().deleteByIds(listOf(SourceChapterId(a, "9").storageKey))
        db.bookVolumesDao().deleteByBookIds(listOf(a.storageKey))
        db.bookInformationDao().deleteByIds(listOf(a.storageKey))
        db.userReadingDataDao().deleteByIds(listOf(a.storageKey))
        assertNull(local.getBookInformation(a.storageKey))
        assertEquals("B", local.getChapterContent(SourceChapterId(b, "9").storageKey)!!.title)
        assertEquals("Other", local.getChapterContent(SourceChapterId(other, "9").storageKey)!!.title)
        assertEquals(30, local.getUserReadingData(b.storageKey).totalReadTime)
    }

    @Test fun settlementAndBookshelfChangesAffectOnlyTheirBookWhileSummarySpansSources() = runBlocking {
        save(a, "A"); save(b, "B")
        shelves.addBookshelf(Bookshelf(id = 1, name = "mixed"))
        shelves.addBookIntoBookShelf(1, info(a, "A"))
        shelves.addBookIntoBookShelf(1, info(b, "B"))
        shelves.addUpdatedBooksIntoBookShelf(1, a.storageKey)
        assertEquals(setOf(a.storageKey, b.storageKey), shelves.getBookshelf(1)!!.allBookIds.toSet())
        assertEquals(listOf(a.storageKey), shelves.getBookshelf(1)!!.updatedBookIds)
        stats.accumulateBookReadTime(a.storageKey, 30)
        stats.accumulateBookReadTime(b.storageKey, 30)
        stats.accumulateBookReadTime(a.storageKey, -1)
        stats.markBookFinished(a.storageKey)
        assertNull(db.bookRecordDao().getBookRecordByIdAndDate(b.storageKey, LocalDate.now()))
        stats.accumulateBookReadTime(b.storageKey, -1)
        assertEquals(1, stats.getTotalReadingSummary().totalMinutes)
        assertFalse(db.bookRecordDao().getBookRecordByIdAndDate(b.storageKey, LocalDate.now())!!.isFinished)
        shelves.deleteBookFromBookshelf(1, a.storageKey)
        assertEquals(listOf(b.storageKey), shelves.getBookshelf(1)!!.allBookIds)
        assertNull(shelves.getBookshelfBookMetadata(a.storageKey))
        assertEquals(listOf(1), shelves.getBookshelfBookMetadata(b.storageKey)!!.bookShelfIds)
    }

    @Test fun currentCborBackupRestoresBothSourcesAndTheirAssociations() = runBlocking {
        save(a, "A"); save(b, "B")
        shelves.addBookshelf(Bookshelf(id = 1, name = "mixed"))
        for (book in listOf(a, b)) {
            shelves.addBookIntoBookShelf(1, info(book, book.sourceId.id))
            stats.updateReadingStatistics(ReadingStatsUpdate(book.storageKey, secondDelta = 30, readEventDelta = 1, localTime = LocalTime.NOON))
        }
        db.userDataDao().insert(UserDataPath.ReadingBooks.path, "", "StringList", listOf(a.storageKey, b.storageKey).joinToString(","))
        val exported = requireNotNull(backup.exportAppLocalData().get())
        val restored = Cbor.decodeFromByteArray<AppLocalData>(Cbor.encodeToByteArray(exported))
        backup.cleanDatabaseWithoutGlobalUserData()
        assertNull(local.getBookInformation(a.storageKey))
        assertTrue(backup.importAppLocalData(restored).isOk)
        assertEquals("A", local.getBookInformation(a.storageKey)!!.title)
        assertEquals("B", local.getBookInformation(b.storageKey)!!.title)
        assertEquals(setOf(a.storageKey, b.storageKey), shelves.getBookshelf(1)!!.allBookIds.toSet())
        assertEquals(SourceChapterId(b, "9").storageKey, local.getUserReadingData(b.storageKey).lastReadChapterId)
        assertEquals(1, stats.getTotalReadingSummary().totalMinutes)
        assertEquals(2, stats.getTotalReadingSummary().totalReadCount)
        assertEquals(listOf(a.storageKey, b.storageKey).joinToString(","), db.userDataDao().get(UserDataPath.ReadingBooks.path))
    }

    @Test fun canonicalAliasesSurviveBackupRoundTripAndRejectCrossSourceMappings() = runBlocking {
        save(a, "Single")
        val oldBackup = backup.exportAppLocalData().get()!!
        val volumes = other.bind(BookVolumes(other.remoteId, listOf(Volume("default", "",
            listOf(ChapterInformation("9", "First"), ChapterInformation("10", "Second"))))))
        local.aliases.merge(a, other, info(other, "Series"), volumes)
        val exported = backup.exportAppLocalData().get()!!
        val restored = Cbor.decodeFromByteArray<AppLocalData>(Cbor.encodeToByteArray(exported))
        assertEquals(1, restored.localDataList.single().bookAliases.size)
        try { backup.importAppLocalData(oldBackup); fail("An old backup cannot resurrect alias rows") }
        catch (_: IllegalArgumentException) { }
        assertNull(db.bookInformationDao().get(a.storageKey))
        backup.cleanDatabaseWithoutGlobalUserData()
        assertEquals(a, local.aliases.resolve(a))
        assertTrue(backup.importAppLocalData(restored).isOk)
        assertEquals(other, local.aliases.resolve(a))
        assertEquals("Series", local.getBookInformation(a.storageKey)!!.title)
        assertEquals(SourceChapterId(a, "9").storageKey, local.getUserReadingData(a.storageKey).lastReadChapterId)
        val invalid = restored.copy(localDataList = listOf(restored.localDataList.single().copy(bookAliases = listOf(
            indi.renakoni.nextvol.data.local.room.entity.BookAliasEntity(a.storageKey, b.storageKey)))))
        try { backup.importAppLocalData(invalid, overwrite = true); fail("Cross-source aliases must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(other, local.aliases.resolve(a))
    }

    @Test fun preNextVolBackupRestoresSourceAssociationsAndDownloadOwnership() = runBlocking {
        val wenku = SourceBookId("Wenku8".ofId(), "123")
        val restored = BackupFiles.read(RuntimeEnvironment.getApplication().cacheDir) {
            requireNotNull(javaClass.getResourceAsStream("/backups/before-nextvol.lnr"))
        }
        assertTrue(backup.importAppLocalData(restored).isOk)
        assertEquals(Identifier("lightnovelreader", "Wenku8"), wenku.sourceId)
        assertEquals("Wenku fixture", local.getBookInformation(wenku.storageKey)!!.title)
        assertEquals("Other source fixture", local.getBookInformation(b.storageKey)!!.title)
        assertEquals(setOf(wenku.storageKey, b.storageKey), shelves.getBookshelf(1)!!.allBookIds.toSet())
        assertEquals(SourceChapterId(wenku, "9").storageKey, local.getUserReadingData(wenku.storageKey).lastReadChapterId)
        assertEquals(0.5f, local.getUserReadingData(wenku.storageKey)
            .currentChapterReadingProgressMap.getValue(SourceChapterId(wenku, "9").storageKey), 0f)
        assertEquals(1, stats.getTotalReadingSummary().totalMinutes)
        assertEquals(2, stats.getTotalReadingSummary().totalReadCount)
        assertEquals(listOf(wenku.storageKey, b.storageKey).joinToString(","), db.userDataDao().get(UserDataPath.ReadingBooks.path))
        val owner = db.bookDownloadDao().get(wenku.storageKey)!!
        assertEquals("before-nextvol-revision", owner.revision)
        assertEquals("", owner.attempt)
        val chapter = SourceChapterId(wenku, "9").storageKey
        assertEquals(wenku.storageKey, db.bookDownloadDao().chapter(chapter)!!.bookId)
        assertEquals("before-nextvol-signature", db.bookDownloadDao().chapter(chapter)!!.signature)
        downloads.clearReadingCache()
        assertNotNull(local.getChapterContent(chapter))
        assertNull(local.getChapterContent(SourceChapterId(b, "9").storageKey))
    }

    @Test fun invalidOrCrossBookBackupIsRejectedBeforeExistingRecordsAreWritten() = runBlocking {
        save(a, "A"); save(b, "B")
        val exported = requireNotNull(backup.exportAppLocalData().get())
        val data = exported.localDataList.single()
        val wrong = data.volumeEntities.first().copy(chapterIds = listOf(SourceChapterId(other, "9").storageKey))
        val malformed = exported.copy(localDataList = listOf(data.copy(volumeEntities = listOf(wrong))))
        try {
            backup.importAppLocalData(malformed)
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals("A", local.getBookInformation(a.storageKey)!!.title)
        assertEquals("B", local.getBookInformation(b.storageKey)!!.title)
    }

    @Test fun bookshelfWorkerWritesMixedSourceReferencesThatRestoreIndependently() = runBlocking {
        save(a, "Same title"); save(b, "Same title")
        shelves.addBookshelf(Bookshelf(id = 1, name = "mixed"))
        for (book in listOf(a, b)) shelves.addBookIntoBookShelf(1, info(book, "Same title"))
        val context = RuntimeEnvironment.getApplication()
        val file = context.filesDir.resolve("mixed-bookshelf.nvbackup")
        val uri = Uri.parse("content://fixture/mixed-bookshelf.nvbackup")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerOutputStream(uri, file.outputStream())
        try {
            val worker = SaveBookshelfWork(context, workerParameters(workDataOf(
                "bookshelfId" to 1, "uri" to uri.toString())), backup, db.bookshelfDao())
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            val restored = BackupArchive.read(file)
            assertEquals(setOf(a.storageKey, b.storageKey), restored.localDataList.single()
                .bookshelfBookMetadataEntities.map { it.id }.toSet())
            db.bookshelfDao().clear()
            assertTrue(backup.importAppLocalData(restored).isOk)
            shelves.deleteBookFromBookshelf(1, a.storageKey)
            assertEquals(listOf(b.storageKey), shelves.getBookshelf(1)!!.allBookIds)
        } finally { file.delete() }
    }
}
