package indi.renakoni.nextvol.data.localbook

import android.app.Application
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.room.Room
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.BookReadingDataRepository
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.book.ChapterRepository
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.bookshelf.BookshelfRepository
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.BookshelfEntity
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class LocalBookStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(temporary.root, "files").apply { mkdirs() }
    } }
    private lateinit var database: NextVolDatabase
    private lateinit var store: LocalBookStore
    private lateinit var local: LocalBookDataSource
    private lateinit var downloads: BookDownloadStore
    private lateinit var books: BookRepository
    private val registry = mockk<WebSourceRegistry> {
        every { sources } returns MutableStateFlow(emptyList())
        coEvery { resolve(any()) } throws AssertionError("Local imports must never resolve an online source")
    }

    @Before fun setUp() = runBlocking {
        openDatabase()
        database.bookshelfDao().createBookshelf(shelf(7))
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, NextVolDatabase::class.java, File(temporary.root, "library.db").path)
            .addMigrations(NextVolDatabase.MIGRATION_18_19, NextVolDatabase.MIGRATION_19_20, NextVolDatabase.MIGRATION_20_21, NextVolDatabase.MIGRATION_21_22, NextVolDatabase.MIGRATION_22_23, NextVolDatabase.MIGRATION_23_24, NextVolDatabase.MIGRATION_24_25, NextVolDatabase.MIGRATION_25_26, NextVolDatabase.MIGRATION_26_27, NextVolDatabase.MIGRATION_27_28, NextVolDatabase.MIGRATION_28_29).allowMainThreadQueries().build()
        store = LocalBookStore(context, database)
        local = LocalBookDataSource(database.bookInformationDao(), database.bookVolumesDao(), database.chapterContentDao(), database.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(database))
        downloads = BookDownloadStore(context, database, ContentJsonDecoder(ContentComponentRegistry()))
        val work = mockk<androidx.work.WorkManager>(relaxed = true)
        val scheduler = indi.renakoni.nextvol.data.download.BookDownloadScheduler(downloads, work, local.aliases)
        val shelves = BookshelfRepository(database.bookshelfDao(), scheduler, registry, local.aliases)
        val text = TextProcessingRepository(mockk { every { enabled } returns false },
            mockk { every { enabled } returns false }, ContentComponentRegistry())
        books = BookRepository(local, shelves, text, work, ChapterRepository(registry, local, text, store, downloads),
            BookReadingDataRepository(local), registry, downloads, store, scheduler)
    }

    @After fun close() { database.close() }

    private fun shelf(id: Int) = BookshelfEntity(id, "Shelf $id", BookshelfSortType.Default.key,
        autoCache = false, systemUpdateReminder = false, allBookIds = emptyList(), pinnedBookIds = emptyList(), updatedBookIds = emptyList())

    private fun source() = temporary.newFile("${java.util.UUID.randomUUID()}.txt").apply {
        writeText("第一卷 起点\n第一章开端\n完整正文甲\n第二章 旅途\n完整正文乙")
    }

    private suspend fun importBook(file: File = source(), shelf: Int? = 7): SourceBookId {
        val draft = store.stage(file.toUri())
        return store.publish(draft, store.preview(draft), "Imported novel", shelf).first
    }

    @Test fun anUnsectionedImportDoesNotInventAVolumeNamedAfterTheBook() = runBlocking {
        val file = temporary.newFile("unsectioned.txt").apply {
            writeText("第一章 开端\n完整正文甲\n第二章 旅途\n完整正文乙")
        }
        val book = importBook(file)
        val volume = books.getBookVolumesFlow(book.storageKey).last().get()!!.volumes.single()
        assertEquals("", volume.volumeTitle)
        assertEquals(2, volume.chapters.size)
    }

    @Test fun originalAndParsedContentSurviveCacheClearingAndDatabaseReopening() = runBlocking {
        val original = source()
        val book = importBook(original)
        val volumes = books.getBookVolumesFlow(book.storageKey).last().get()!!
        assertEquals("第一卷 起点", volumes.volumes.single().volumeTitle)
        val first = volumes.volumes.single().chapters.first().id
        assertTrue(original.delete())
        local.updateUserReadingData(book.storageKey) { it.copy(totalReadTime = 42, lastReadChapterId = first, readingProgress = 0.5f) }
        downloads.clearReadingCache()
        assertNull(database.chapterContentDao().get(first))
        database.bookVolumesDao().clear()
        database.close()
        openDatabase()

        assertEquals("Imported novel", books.getBookInformationFlow(book.storageKey).last().get()!!.title)
        assertEquals(volumes, books.getBookVolumesFlow(book.storageKey).last().get())
        val content = books.getChapterContentFlow(first, book.storageKey).last().get()!!
        assertTrue(content.content.toString().contains("完整正文甲"))
        assertEquals(SourceChapterId(book, "1").storageKey, content.nextChapter)
        assertTrue(books.readingAvailability(book.storageKey).first().local)
        assertFalse(books.readingAvailability(book.storageKey).first().online)
        assertTrue(books.getIsBookCached(book.storageKey))
        assertEquals(42, books.getUserReadingData(book.storageKey).totalReadTime)
        books.preloadChapterContent(first, book.storageKey)
        assertTrue(books.refreshBookInformation(book).isOk)
        assertTrue(books.downloadDirectory(book).isOk)
        assertTrue(books.downloadChapter(book, first).isOk)
        assertNull(books.bookTagPage(book, "tag").get())
        coVerify(exactly = 0) { registry.resolve(any()) }
    }

    @Test fun repeatedImportsCreateIndependentIdentitiesAndKeepTheFirstReadingPosition() = runBlocking {
        val original = source()
        val first = importBook(original)
        val chapter = SourceChapterId(first, "1").storageKey
        local.updateUserReadingData(first.storageKey) { it.copy(totalReadTime = 50, lastReadChapterId = chapter) }
        val second = importBook(original)
        assertNotEquals(first, second)
        assertEquals(listOf(first.storageKey, second.storageKey), database.bookshelfDao().getBookshelf(7)!!.allBookIds)
        assertEquals(chapter, books.getUserReadingData(first.storageKey).lastReadChapterId)
        assertNull(books.getUserReadingData(second.storageKey).lastReadChapterId)
        assertEquals(2, database.importedBookDao().allIds().size)
    }

    @Test fun cancellationLeavesNoBookRecordsOrOriginalCopies() = runBlocking {
        val draft = store.stage(source().toUri())
        store.preview(draft)
        store.discard(draft)
        assertTrue(database.importedBookDao().allIds().isEmpty())
        assertTrue(database.bookInformationDao().getAllEntities().isEmpty())
        assertTrue(database.bookshelfDao().getBookshelf(7)!!.allBookIds.isEmpty())
        assertTrue(File(context.filesDir, "local-books").listFiles().orEmpty().isEmpty())
    }

    @Test fun deletedTargetShelfRollsBackThePublishedFilesAndEveryDatabaseWrite() = runBlocking {
        val draft = store.stage(source().toUri())
        val preview = store.preview(draft)
        database.bookshelfDao().deleteBookshelf(7)
        val result = runCatching { store.publish(draft, preview, preview.title, 7) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(database.importedBookDao().allIds().isEmpty())
        assertTrue(database.bookInformationDao().getAllEntities().isEmpty())
        assertTrue(database.bookVolumesDao().getAllVolumeEntities().isEmpty())
        assertTrue(database.chapterContentDao().getAllEntities().isEmpty())
        assertTrue(File(context.filesDir, "local-books").listFiles().orEmpty().isEmpty())
    }

    @Test fun anEmptyLibraryCreatesItsFirstShelfOnlyAfterConfirmation() = runBlocking {
        database.bookshelfDao().deleteBookshelf(7)
        val draft = store.stage(source().toUri())
        val preview = store.preview(draft)
        assertTrue(database.bookshelfDao().getAllBookshelfIds().isEmpty())
        val (book, shelf) = store.publish(draft, preview, preview.title, null)
        assertEquals(listOf(book.storageKey), database.bookshelfDao().getBookshelf(shelf)!!.allBookIds)
    }

    @Test fun deletingOneCopyAlsoRemovesOrphanedCacheAndShelfLinksButKeepsOtherCopies() = runBlocking {
        val original = source()
        val first = importBook(original)
        val second = importBook(original)
        database.bookshelfDao().createBookshelf(shelf(8).copy(allBookIds = listOf(first.storageKey)))
        local.updateUserReadingData(second.storageKey) { it.copy(totalReadTime = 73) }
        fun bookmark(book: SourceBookId) = indi.renakoni.nextvol.data.bookmark.ReadingBookmark(
            bookId = book.storageKey, chapterId = SourceChapterId(book, "0").storageKey,
            chapterTitle = "Chapter", componentIndex = 0, offset = 0, fingerprint = "a".repeat(64), preview = "Text", progress = 0f)
        database.readingBookmarkDao().insert(bookmark(first))
        database.readingBookmarkDao().insert(bookmark(second))
        // Clear only the cache directory index first: deletion must still find cached chapters.
        database.bookVolumesDao().deleteByBookIds(listOf(first.storageKey))
        assertTrue(store.storedBytes(first) > 0)
        store.delete(first)
        assertEquals(listOf(second.storageKey), database.readingBookmarkDao().all().map { it.bookId })
        assertFalse(store.contains(first))
        assertEquals(0L, store.storedBytes(first))
        assertNull(database.chapterContentDao().get(SourceChapterId(first, "0").storageKey))
        assertTrue(database.bookshelfDao().getBookshelf(8)!!.allBookIds.isEmpty())
        assertEquals(listOf(second.storageKey), database.bookshelfDao().getBookshelf(7)!!.allBookIds)
        assertTrue(books.getChapterContentFlow("0", second.storageKey).last().get()!!.content.toString().contains("完整正文甲"))
        assertEquals(73, books.getUserReadingData(second.storageKey).totalReadTime)
        assertTrue(original.isFile)
    }

    @Test fun restartCleansAbandonedStagingAndUnpublishedDirectoriesWithoutDeletingPublishedBooks() = runBlocking {
        val kept = importBook()
        val pending = store.stage(source().toUri())
        val interrupted = store.stage(source().toUri())
        val moved = File(context.filesDir, "local-books/${interrupted.book.fileKey}")
        assertTrue(interrupted.directory.renameTo(moved))
        store = LocalBookStore(context, database)
        store.restoreMetadata()
        assertFalse(pending.directory.exists())
        assertFalse(moved.exists())
        assertTrue(store.contains(kept))
        assertTrue(store.readChapter(SourceChapterId(kept, "0")).isOk)
    }

    @Test fun missingLocalFilesReportAnErrorWithoutFallingBackToAnyOnlineSource() = runBlocking {
        val book = importBook()
        val chapter = SourceChapterId(book, "0")
        assertTrue(File(context.filesDir, "local-books/${book.fileKey}/0.json").delete())
        assertTrue(books.getChapterContentFlow(chapter.storageKey, book.storageKey).last().isErr)
        val unknown = SourceBookId(LocalBookStore.SOURCE, "not-imported")
        assertTrue(books.getBookInformationFlow(unknown.storageKey).last().isErr)
        coVerify(exactly = 0) { registry.resolve(any()) }
    }

    @Test fun metadataClearedByAnOldBackupCanBeRebuiltFromTheRetainedOriginal() = runBlocking {
        val book = importBook()
        database.bookInformationDao().clear()
        store.restoreMetadata()
        assertEquals("Imported novel", database.bookInformationDao().get(book.storageKey)!!.title)
        assertTrue(store.contains(book))
    }

    @Test fun storageBookRefreshRestoresHealthyAndDamagedImportsBeforeBuildingTheList() = runBlocking {
        val healthy = importBook()
        val damaged = importBook()
        File(context.filesDir, "local-books/${damaged.fileKey}/index.json").writeText("broken")
        database.bookInformationDao().clear()
        database.bookVolumesDao().clear()
        database.chapterContentDao().clear()
        val usage = indi.renakoni.nextvol.data.storage.StorageUsageRepository(context, database,
            indi.renakoni.nextvol.data.userdata.UserDataRepository(database.userDataDao()))
        val query = indi.renakoni.nextvol.data.storage.StorageBookQuery(database, usage, store)
        usage.refreshSnapshot()
        assertTrue(query.getCachedBooks()!!.isEmpty())
        assertTrue(database.bookInformationDao().getAllEntities().isEmpty())

        val listed = query.refreshBooks().associateBy { it.id }
        assertEquals(setOf(healthy.storageKey, damaged.storageKey), listed.keys)
        for (book in listOf(healthy, damaged)) {
            val item = listed.getValue(book.storageKey)
            assertEquals(store.storedBytes(book), item.importedFileBytes)
            assertTrue(item.importedFileBytes > 0L)
            assertEquals(item.usage.totalBytes + item.importedFileBytes, item.size)
        }
        assertEquals("Imported novel", database.bookInformationDao().get(healthy.storageKey)!!.title)
        store.delete(damaged)
        assertEquals(listOf(healthy.storageKey), query.refreshBooks().map { it.id })
    }

    @Test fun aDamagedImportRemainsVisibleAndCanBeDeletedAfterMetadataWasCleared() = runBlocking {
        val book = importBook()
        File(context.filesDir, "local-books/${book.fileKey}/index.json").writeText("broken")
        database.bookInformationDao().clear()
        store.restoreMetadata()
        assertTrue(books.getBookInformationFlow(book.storageKey).last().isOk)
        assertTrue(books.getBookVolumesFlow(book.storageKey).last().isErr)
        assertTrue(store.storedBytes(book) > 0)
        store.delete(book)
        assertEquals(0L, store.storedBytes(book))
        assertTrue(database.importedBookDao().allIds().isEmpty())
    }

    @Test fun room18UpgradeRetainsBooksShelvesAndReadingPositions() = runBlocking {
        val imported = importBook()
        val oldBook = SourceBookId(io.nightfish.lightnovelreader.api.identifier.Identifier("fixture", "online"), "legacy")
        database.bookInformationDao().insert(database.bookInformationDao().get(imported.storageKey)!!.copy(id = oldBook.storageKey))
        local.updateUserReadingData(oldBook.storageKey) { it.copy(totalReadTime = 91) }
        database.openHelper.writableDatabase.apply {
            indi.renakoni.nextvol.data.download.restorePre25DownloadSchema(this::execSQL)
            execSQL("DROP TABLE local_book_file_manifest"); execSQL("DROP TABLE imported_book"); execSQL("DROP TABLE bangumi_binding"); execSQL("DROP TABLE bangumi_sync_record"); execSQL("DROP TABLE book_alias"); version = 18
        }
        database.close()
        openDatabase()
        assertEquals(29, database.openHelper.writableDatabase.version)
        assertEquals("Imported novel", database.bookInformationDao().get(oldBook.storageKey)!!.title)
        assertEquals(91, books.getUserReadingData(oldBook.storageKey).totalReadTime)
        assertNotNull(database.bookshelfDao().getBookshelf(7))
        assertTrue(database.importedBookDao().allIds().isEmpty())
    }

    @Test fun importedCopiesUseTheExistingReadingStatisticsWithoutIdentityCollisions() = runBlocking {
        val first = importBook()
        val second = importBook()
        val stats = indi.renakoni.nextvol.data.statistics.StatsRepository(database.bookRecordDao(), database.dailyCountDao(), books,
            indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator())
        stats.updateReadingStatistics(indi.renakoni.nextvol.data.statistics.ReadingStatsUpdate(first.storageKey, secondDelta = 120, readEventDelta = 1))
        stats.markBookFinished(first.storageKey)
        val today = java.time.LocalDate.now()
        assertEquals(120, database.bookRecordDao().getBookRecordByIdAndDate(first.storageKey, today)!!.seconds)
        assertTrue(database.bookRecordDao().getBookRecordByIdAndDate(first.storageKey, today)!!.isFinished)
        assertNull(database.bookRecordDao().getBookRecordByIdAndDate(second.storageKey, today))
        assertEquals(2, stats.getTotalReadingSummary().totalMinutes)
        coVerify(exactly = 0) { registry.resolve(any()) }
    }
}
