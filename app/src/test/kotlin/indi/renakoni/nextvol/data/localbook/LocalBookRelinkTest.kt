package indi.renakoni.nextvol.data.localbook

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.bookmark.ReadingBookmark
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.backup.BackupDataManager
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.local.room.entity.UserReadingDataEntity
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.reading.RepositoryReaderRecordStore
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.data.work.SaveBookshelfWork
import indi.renakoni.nextvol.data.work.workerParameters
import indi.renakoni.nextvol.data.backup.BackupFiles
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalSerializationApi::class)
class LocalBookRelinkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val databases = mutableListOf<NextVolDatabase>()
    private inner class Library(name: String) {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(temporary.root, name).apply { mkdirs() }
        }
        val db = Room.databaseBuilder(context, NextVolDatabase::class.java, File(temporary.root, "$name.db").path)
            .allowMainThreadQueries().addMigrations(NextVolDatabase.MIGRATION_22_23, NextVolDatabase.MIGRATION_23_24, NextVolDatabase.MIGRATION_24_25, NextVolDatabase.MIGRATION_25_26, NextVolDatabase.MIGRATION_26_27, NextVolDatabase.MIGRATION_27_28, NextVolDatabase.MIGRATION_28_29).build().also(databases::add)
        val store = LocalBookStore(context, db)
        val coordinator = StatisticsWriteCoordinator()
        val backup = BackupDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
            db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(),
            db.userReadingDataDao(), db.userDataDao(), mockk(relaxed = true), coordinator,
            StatsRepository(db.bookRecordDao(), db.dailyCountDao(), mockk(), coordinator),
            BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry())))
        suspend fun import(file: File, rule: String = TxtBookParser.DEFAULT_RULE): SourceBookId {
            val draft = store.stage(file.toUri())
            return store.publish(draft, store.preview(draft, rule = rule), "My saved title", null).first
        }
        suspend fun export(cache: Boolean = true) = backup.exportAppLocalData(localBookCache = cache).get()!!
        suspend fun restore(data: AppLocalData) {
            val bytes = Cbor.encodeToByteArray(data)
            assertTrue(backup.importAppLocalData(Cbor.decodeFromByteArray(bytes)).isOk)
        }
        suspend fun preview(book: SourceBookId, file: File, rule: String = TxtBookParser.DEFAULT_RULE) =
            store.previewRelink(book, store.stage(file.toUri()), rule = rule)
    }
    @After fun close() { databases.forEach { it.close() } }
    private fun txt(name: String = "novel.txt", text: String = "Chapter 1\nFirst text\nChapter 2\nSecond text") =
        temporary.newFile(name).apply { writeText(text) }
    private fun epub() = temporary.newFile("novel.epub").apply {
        ZipOutputStream(outputStream()).use { zip ->
            val entries = mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>",
                "OPS/book.opf" to "<package><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Original</dc:title></metadata><manifest><item id='one' href='one.xhtml' media-type='application/xhtml+xml'/><item id='image' href='image.png' media-type='image/png'/></manifest><spine><itemref idref='one'/></spine></package>",
                "OPS/one.xhtml" to "<html><body><h1>Chapter one</h1><p>Text</p><img src='image.png'/></body></html>",
                "OPS/image.png" to "image fixture",
            )
            entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
    }
    private suspend fun Library.seedPosition(book: SourceBookId) {
        val chapter = SourceChapterId(book, "0").storageKey
        db.userReadingDataDao().insert(UserReadingDataEntity(book.storageKey, LocalDateTime.now(), 81, .4f,
            chapter, "Saved chapter", mapOf(chapter to .4f), mapOf(chapter to .7f)))
        db.readingBookmarkDao().insert(ReadingBookmark(bookId = book.storageKey, chapterId = chapter,
            chapterTitle = "Saved chapter", componentIndex = 0, offset = 2, fingerprint = "a".repeat(64), preview = "Text", progress = .4f))
    }

    private suspend fun roundTrip(file: File) {
        val old = Library("old")
        val book = old.import(file)
        old.seedPosition(book)
        val data = old.export(cache = false)
        val target = Library("new")
        target.restore(data)
        assertFalse(target.store.contains(book))
        assertTrue(target.db.importedBookDao().allIds().isEmpty())
        val shelves = target.db.bookshelfDao().getAllBookshelves()
        val progress = target.db.userReadingDataDao().getEntity(book.storageKey)
        val bookmarks = target.db.readingBookmarkDao().all()
        val renamed = File(temporary.root, "renamed.${file.extension}").apply { writeBytes(file.readBytes()) }
        val preview = target.preview(book, renamed)
        assertEquals(LocalBookRelinkMatch.Exact, preview.match)
        target.store.relink(preview)
        assertTrue(target.store.contains(book))
        assertTrue(target.store.observeAvailability(book).first())
        assertEquals(listOf(book.storageKey), target.db.importedBookDao().allIds())
        assertEquals(shelves, target.db.bookshelfDao().getAllBookshelves())
        assertEquals(progress, target.db.userReadingDataDao().getEntity(book.storageKey))
        assertEquals(bookmarks, target.db.readingBookmarkDao().all())
        assertEquals("My saved title", target.store.readInformation(book).get()!!.title)
        assertEquals(old.store.readVolumes(book).get(), target.store.readVolumes(book).get())
        val content = target.store.readChapter(SourceChapterId(book, "0")).get()!!
        assertTrue(content.content.toString().isNotEmpty())
        val reopened = LocalBookStore(target.context, target.db)
        reopened.restoreMetadata()
        assertTrue(reopened.contains(book))
        assertTrue(reopened.readChapter(SourceChapterId(book, "0")).isOk)
        val third = Library("third")
        third.restore(target.export(cache = false))
        assertEquals(LocalBookRelinkMatch.Exact, third.preview(book, renamed).match)
    }
    @Test fun txtRestoreWithoutCacheRetainsIdentityShelfProgressAndBookmarks() = runBlocking { roundTrip(txt(text = "Preface\nChapter 1\nText")) }
    @Test fun extensionOnlyFileNameKeepsItsFallbackChapterAcrossDevices() = runBlocking { roundTrip(txt(name = ".txt", text = "A book without headings")) }
    @Test fun epubRestoreResolvesImagesFromTheNewDeviceDirectory() = runBlocking { roundTrip(epub()) }

    @Test fun sharedBookshelfRetainsLocalFileEvidenceWithoutOtherBooksOrPrivateReadingData() = runBlocking {
        sharedShelfRoundTrip(legacy = false)
    }

    @Test fun sharedLegacyBookshelfRetainsTheDirectoryRequiredForConfirmedRelink() = runBlocking {
        sharedShelfRoundTrip(legacy = true)
    }

    private suspend fun sharedShelfRoundTrip(legacy: Boolean) {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        old.seedPosition(book)
        val shelf = old.db.bookshelfDao().getAllBookshelves().single()
        old.db.bookshelfDao().insertBookshelf(shelf.copy(id = 2, allBookIds = emptyList()))
        val otherDraft = old.store.stage(txt("private.txt").toUri())
        old.store.publish(otherDraft, old.store.preview(otherDraft), "Private book", 2)
        if (legacy) old.db.localBookFileManifestDao().delete(book.storageKey)
        val uri = Uri.parse("content://shelf-export/selected")
        val output = ByteArrayOutputStream()
        shadowOf(old.context.contentResolver).registerOutputStream(uri, output)
        val worker = SaveBookshelfWork(old.context,
            workerParameters(workDataOf("bookshelfId" to shelf.id, "uri" to uri.toString())),
            old.backup, old.db.bookshelfDao())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val data = BackupFiles.read(RuntimeEnvironment.getApplication().cacheDir) { output.toByteArray().inputStream() }
        val target = Library("new")
        target.restore(data)
        val preview = target.preview(book, file)
        assertEquals(if (legacy) LocalBookRelinkMatch.Legacy else LocalBookRelinkMatch.Exact, preview.match)
        val part = data.localDataList.single()
        assertEquals(listOf(shelf.id), part.bookshelfEntities.map { it.id })
        assertEquals(listOf(book.storageKey), part.bookInformationEntities.map { it.id })
        assertEquals(if (legacy) emptyList<String>() else listOf(book.storageKey), part.localBookFiles.map { it.bookId })
        assertTrue(part.chapterContentEntities.isEmpty())
        assertTrue(part.readingBookmarks.isEmpty())
        assertTrue(part.userReadingDataEntities.isEmpty())
        assertTrue(part.bookRecordEntities.isEmpty())
        assertTrue(part.userDataEntities.isEmpty())
        assertTrue(target.db.importedBookDao().allIds().isEmpty())
        target.store.relink(preview, confirmLegacy = legacy)
        assertEquals(listOf(book.storageKey), target.db.importedBookDao().allIds())
        assertEquals("My saved title", target.store.readInformation(book).get()!!.title)
        assertTrue(target.store.readChapter(SourceChapterId(book, "0")).isOk)
    }

    @Test fun restoredEmptyRecentHistoryCanRecordTheRelinkedBook() = runBlocking {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        UserDataRepository(old.db.userDataDao()).stringListUserData(UserDataPath.ReadingBooks.path).set(emptyList())
        val target = Library("new")
        target.restore(old.export(cache = false))
        target.store.relink(target.preview(book, file))
        val preferences = UserDataRepository(target.db.userDataDao())
        val records = RepositoryReaderRecordStore(mockk(), mockk(), preferences)
        records.updateRecentBooks { it + book.storageKey }
        assertEquals(listOf(book.storageKey), preferences.stringListUserData(UserDataPath.ReadingBooks.path).get())
    }

    @Test fun changedOriginalAndSameNameDifferentBookCannotBind() = runBlocking {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        val target = Library("new")
        target.restore(old.export())
        file.writeText("Chapter 1\nEntirely different text\nChapter 2\nSecond text")
        val preview = target.preview(book, file)
        assertEquals(LocalBookRelinkMatch.DifferentFile, preview.match)
        assertTrue(runCatching { target.store.relink(preview, confirmLegacy = true) }.isFailure)
        assertFalse(target.store.contains(book))
        assertEquals(listOf(book.storageKey), target.db.bookshelfDao().getAllBookshelves().single().allBookIds)
    }
    @Test fun changedRuleCannotMoveOldPositionsButOriginalRuleCanBeRestored() = runBlocking {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        val target = Library("new")
        target.restore(old.export())
        val preview = target.preview(book, file, rule = "")
        assertEquals(LocalBookRelinkMatch.DifferentMapping, preview.match)
        assertTrue(runCatching { target.store.relink(preview) }.isFailure)
        assertEquals(LocalBookRelinkMatch.Exact, target.preview(book, file).match)
    }
    @Test fun sameBytesWithWrongDecodingCannotBindToSavedPositions() = runBlocking {
        val file = txt(text = "Chapter 1\nOriginal body\nChapter 2\nMore text.").apply {
            if (length() % 2L != 0L) appendText(" ")
        }
        val library = Library("library")
        val book = library.import(file)
        val draft = library.store.stage(file.toUri())
        val wrong = library.store.previewRelink(book, draft, encoding = "UTF-16BE")
        assertEquals(LocalBookRelinkMatch.DifferentMapping, wrong.match)
        assertTrue(runCatching { library.store.relink(wrong) }.isFailure)
        val correct = library.store.previewRelink(book, draft, encoding = "UTF-8")
        assertEquals(LocalBookRelinkMatch.Exact, correct.match)
    }
    @Test fun legacyBackupRequiresMatchingFullDirectoryAndExplicitConfirmation() = runBlocking {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        old.seedPosition(book)
        val exported = old.export()
        val legacy = exported.copy(localDataList = exported.localDataList.map { it.copy(localBookFiles = emptyList()) })
        val target = Library("new")
        target.restore(legacy)
        val preview = target.preview(book, file)
        assertEquals(LocalBookRelinkMatch.Legacy, preview.match)
        assertTrue(runCatching { target.store.relink(preview) }.isFailure)
        target.store.relink(preview, confirmLegacy = true)
        assertTrue(target.store.contains(book))
        assertEquals(1, target.db.readingBookmarkDao().all().size)
    }
    @Test fun legacyBookTitlePlaceholderRelinksWithoutRestoringASyntheticVolume() = runBlocking {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        old.seedPosition(book)
        val data = old.export()
        val target = Library("new")
        target.restore(data.copy(localDataList = data.localDataList.map { it.copy(localBookFiles = emptyList()) }))
        val catalog = target.db.bookVolumesDao().getBookVolumes(book.storageKey)!!
        assertEquals("", catalog.volumes.single().volumeTitle)
        target.db.bookVolumesDao().insertVolume(book.storageKey, catalog.copy(
            volumes = catalog.volumes.map { it.copy(volumeTitle = "My saved title") }))
        val progress = target.db.userReadingDataDao().getEntity(book.storageKey)
        val bookmarks = target.db.readingBookmarkDao().all()
        val preview = target.preview(book, file)
        assertEquals(LocalBookRelinkMatch.Legacy, preview.match)
        assertTrue(runCatching { target.store.relink(preview) }.isFailure)
        target.store.relink(preview, confirmLegacy = true)
        assertEquals(catalog, target.store.readVolumes(book).get())
        assertEquals(progress, target.db.userReadingDataDao().getEntity(book.storageKey))
        assertEquals(bookmarks, target.db.readingBookmarkDao().all())
    }
    @Test fun legacyRelinkStillRequiresMatchingExplicitVolumeNames() = runBlocking {
        val text = "第一卷 起点\n第一章 开端\n正文甲\n第二章 旅途\n正文乙"
        val file = txt(text = text)
        val old = Library("old")
        val book = old.import(file)
        val data = old.export()
        val target = Library("new")
        target.restore(data.copy(localDataList = data.localDataList.map { it.copy(localBookFiles = emptyList()) }))
        assertEquals(LocalBookRelinkMatch.Legacy, target.preview(book, file).match)
        file.writeText(text.replace("第一卷 起点", "第二卷 归途"))
        val changed = target.preview(book, file)
        assertEquals(LocalBookRelinkMatch.DifferentMapping, changed.match)
        assertTrue(runCatching { target.store.relink(changed, confirmLegacy = true) }.isFailure)
        file.writeText(text.substringAfter('\n'))
        assertEquals(LocalBookRelinkMatch.DifferentMapping, target.preview(book, file).match)
    }
    @Test fun legacyBackupWithoutDirectoryOrWithChangedTitlesIsRejected() = runBlocking {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        val target = Library("new")
        val data = old.export()
        target.restore(data.copy(localDataList = data.localDataList.map { it.copy(localBookFiles = emptyList()) }))
        file.writeText("Chapter 3\nFirst text\nChapter 4\nSecond text")
        assertEquals(LocalBookRelinkMatch.DifferentMapping, target.preview(book, file).match)
        target.db.bookVolumesDao().clear()
        assertEquals(LocalBookRelinkMatch.MissingMapping, target.preview(book, file).match)
    }
    @Test fun sqlFailureRollsBackOwnerMetadataAndNewFilesAndRetainsReadableOldCopy() = runBlocking {
        val file = txt()
        val library = Library("library")
        val book = library.import(file)
        library.seedPosition(book)
        val before = library.export()
        val preview = library.preview(book, file)
        library.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_relink BEFORE INSERT ON chapter_content BEGIN SELECT RAISE(ABORT, 'injected relink failure'); END")
        assertTrue(runCatching { library.store.relink(preview) }.isFailure)
        assertEquals(before, library.export())
        assertTrue(library.store.readChapter(SourceChapterId(book, "0")).isOk)
        assertEquals(listOf(book.fileKey), File(library.context.filesDir, "local-books").listFiles()!!.map { it.name })
    }
    @Test fun dismissalAndCancelledConfirmationLeaveRestoredDataUntouched() = runBlocking {
        val file = txt()
        val old = Library("old")
        val book = old.import(file)
        val target = Library("new")
        target.restore(old.export())
        val before = target.export()
        val preview = target.preview(book, file)
        coroutineScope {
            val cancelled = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try {
                    target.store.relink(preview)
                    fail("Cancelled confirmation must not publish")
                } catch (_: CancellationException) { }
            }
            cancelled.join()
        }
        target.store.discard(preview.draft)
        assertEquals(before, target.export())
        assertFalse(target.store.contains(book))
        assertTrue(File(target.context.filesDir, "local-books").listFiles().orEmpty().isEmpty())
    }
    @Test fun stalePreviewAfterDeletionOrSecondRelinkCannotResurrectOrReplaceBook() = runBlocking {
        val file = txt()
        val library = Library("library")
        val book = library.import(file)
        val first = library.preview(book, file)
        val second = library.preview(book, file)
        library.store.relink(first)
        val owner = library.db.importedBookDao().get(book.storageKey)
        assertTrue(runCatching { library.store.relink(second) }.isFailure)
        assertEquals(owner, library.db.importedBookDao().get(book.storageKey))
        val deleted = library.preview(book, file)
        library.store.delete(book)
        assertTrue(runCatching { library.store.relink(deleted) }.isFailure)
        assertFalse(library.store.contains(book))
        assertTrue(library.db.bookInformationDao().getAllEntities().isEmpty())
    }
    @Test fun settingsOnlyBackupHasNoManifestsAndUnselectedBooksAreExcluded() = runBlocking {
        val library = Library("library")
        val book = library.import(txt())
        assertTrue(library.backup.exportAppLocalData(false, false, false, true, false).get()!!.localDataList.single().localBookFiles.isEmpty())
        assertTrue(library.backup.exportAppLocalData(false, false, false, false, true).get()!!.localDataList.single().localBookFiles.isEmpty())
        library.seedPosition(book)
        val bookmarks = library.backup.exportAppLocalData(false, false, false, false, true).get()!!.localDataList.single()
        assertEquals(listOf(book.storageKey), bookmarks.localBookFiles.map { it.bookId })
        assertEquals(listOf(book.storageKey), bookmarks.bookInformationEntities.map { it.id })
        assertTrue(bookmarks.chapterContentEntities.isEmpty())
    }
    @Test fun version22MigrationPreservesOriginalOwnershipAndReadingData() = runBlocking {
        val library = Library("library")
        val book = library.import(txt())
        library.seedPosition(book)
        library.db.openHelper.writableDatabase.apply {
            execSQL("DROP TABLE local_book_file_manifest")
            execSQL("ALTER TABLE imported_book RENAME TO imported_book_new")
            execSQL("CREATE TABLE imported_book (bookId TEXT NOT NULL PRIMARY KEY)")
            execSQL("INSERT INTO imported_book SELECT bookId FROM imported_book_new")
            execSQL("DROP TABLE imported_book_new")
            execSQL("DROP TABLE book_alias")
            indi.renakoni.nextvol.data.download.restorePre25DownloadSchema(this::execSQL)
            version = 22
        }
        library.db.close()
        val reopened = Library("library")
        assertEquals(29, reopened.db.openHelper.writableDatabase.version)
        assertTrue(reopened.store.contains(book))
        assertTrue(reopened.db.localBookFileManifestDao().all().isEmpty())
        assertEquals(81, reopened.db.userReadingDataDao().getEntity(book.storageKey)!!.totalReadTime)
        assertEquals(1, reopened.db.readingBookmarkDao().all().size)
        // Exporting an upgraded copy without cache still carries its legacy verification directory.
        val data = reopened.export(cache = false).localDataList.single()
        assertTrue(data.volumeEntities.isNotEmpty())
        assertTrue(data.chapterInformationEntities.isNotEmpty())
    }
}
