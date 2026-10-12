package indi.renakoni.nextvol.data.backup

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.backup.model.AppLocalData
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.statistics.StatisticsWriteCoordinator
import indi.renakoni.nextvol.data.statistics.StatsRepository
import indi.renakoni.nextvol.data.work.ExportDataWork
import indi.renakoni.nextvol.data.work.ImportDataWork
import indi.renakoni.nextvol.data.work.SaveBookshelfWork
import indi.renakoni.nextvol.data.work.workerParameters
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalSerializationApi::class)
class BackupConversionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: NextVolDatabase
    private lateinit var manager: BackupDataManager
    private var sequence = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
        val coordinator = StatisticsWriteCoordinator()
        val stats = StatsRepository(db.bookRecordDao(), db.dailyCountDao(), mockk(), coordinator)
        val downloads = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
        manager = BackupDataManager(db, db.bookInformationDao(), db.bookRecordDao(), db.dailyCountDao(),
            db.bookshelfDao(), db.chapterContentDao(), db.bookVolumesDao(), db.formattingRuleDao(),
            db.userReadingDataDao(), db.userDataDao(), mockk(relaxed = true), coordinator, stats, downloads)
    }

    @After fun tearDown() { db.close() }

    @Test fun legacyFullBackupConvertsThroughRealWorkersAndRestoresWithoutLoss() = runBlocking {
        convertUserData(includeCache = true)
    }

    @Test fun legacyBackupConvertsWithoutChapterCacheAndRestoresSelectedData() = runBlocking {
        convertUserData(includeCache = false)
    }

    private suspend fun convertUserData(includeCache: Boolean) {
        restore(legacyBytes())
        val before = requireNotNull(manager.exportAppLocalData(localBookCache = includeCache).get())
        assertEquals(if (includeCache) 2 else 0, before.localDataList.flatMap { it.bookInformationEntities }.size)
        assertEquals(2, before.localDataList.flatMap { it.bookshelfEntities }.single().allBookIds.size)
        assertTrue(before.localDataList.flatMap { it.bookRecordEntities }.isNotEmpty())
        val uri = Uri.parse("content://backup-conversion/output-${sequence++}")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        val worker = ExportDataWork(context, workerParameters(workDataOf(
            "uri" to uri.toString(), "exportLocalBookCache" to includeCache)), manager)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val file = temporary.newFile().apply { writeBytes(output.toByteArray()) }
        ZipFile(file).use { zip ->
            assertEquals(setOf("manifest.json", "data.cbor"), zip.entries().asSequence().map { it.name }.toSet())
            val manifest = Json.decodeFromString<BackupManifest>(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            assertEquals(BackupKind.USER_DATA, manifest.kind)
            assertEquals(BackupArchive.FORMAT, manifest.format)
            assertEquals(1, manifest.version)
            assertEquals(BackupContent.entries.toSet() - if (includeCache) emptySet() else setOf(BackupContent.CHAPTER_CACHE), manifest.contents)
        }
        assertPayloadEquals(before, BackupArchive.read(file))
        restore(output.toByteArray())
        assertPayloadEquals(before, requireNotNull(manager.exportAppLocalData(localBookCache = includeCache).get()))
    }

    @Test fun legacyBookshelfConvertsThroughShareWorkerAndRestoresAssociations() = runBlocking {
        restore(legacyBytes())
        val before = db.bookshelfDao().getAllBookshelves()
        assertEquals(1, before.size)
        assertEquals(2, before.single().allBookIds.size)
        val uri = Uri.parse("content://backup-conversion/shelf")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        val worker = SaveBookshelfWork(context, workerParameters(workDataOf(
            "uri" to uri.toString(), "bookshelfId" to 1)), manager, db.bookshelfDao())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val file = temporary.newFile().apply { writeBytes(output.toByteArray()) }
        ZipFile(file).use { zip ->
            val manifest = Json.decodeFromString<BackupManifest>(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            assertEquals(BackupKind.BOOKSHELF, manifest.kind)
            assertEquals(setOf(BackupContent.BOOKSHELF), manifest.contents)
        }
        assertTrue(BackupArchive.read(file).localDataList.all { it.chapterContentEntities.isEmpty() })
        restore(output.toByteArray())
        assertEquals(before, db.bookshelfDao().getAllBookshelves())
    }

    @Test fun allExportSelectionCombinationsRemainValidAfterLegacyImport() = runBlocking {
        restore(legacyBytes())
        val keys = listOf("exportLocalBookCache", "exportBookshelf", "exportReadingData", "exportSetting", "exportBookmark")
        for (mask in 0 until 32) {
            val flags = (0 until 5).map { mask and (1 shl it) != 0 }
            val uri = Uri.parse("content://backup-conversion/options-$mask")
            val output = ByteArrayOutputStream()
            shadowOf(context.contentResolver).registerOutputStream(uri, output)
            val input = androidx.work.Data.Builder().putString("uri", uri.toString())
            keys.forEachIndexed { index, key -> input.putBoolean(key, flags[index]) }
            val worker = ExportDataWork(context, workerParameters(input.build()), manager)
            assertEquals("Selection mask $mask", ListenableWorker.Result.success(), worker.doWork())
            val file = temporary.newFile().apply { writeBytes(output.toByteArray()) }
            val decoded = BackupArchive.read(file)
            manager.validateBackup(decoded)
            val expected = requireNotNull(manager.exportAppLocalData(flags[0], flags[1], flags[2], flags[3], flags[4]).get())
            assertPayloadEquals(expected, decoded, "Selection mask $mask")
            ZipFile(file).use { zip ->
                val manifest = Json.decodeFromString<BackupManifest>(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                assertEquals(BackupContent.entries.filterIndexed { index, _ -> flags[index] }.toSet(), manifest.contents)
            }
        }
    }

    @Test fun mergingLegacyIntoExistingLibraryThenConvertingPreservesBothLibraries() = runBlocking {
        val legacy = legacyBytes()
        val oldFile = temporary.newFile().apply { writeBytes(legacy) }
        val marker = SourceBookId(Identifier("conversion", "existing"), "keep-me").storageKey
        val sample = BackupArchive.read(oldFile).localDataList.flatMap { it.bookInformationEntities }.first()
        db.bookInformationDao().insert(sample.copy(id = marker, title = "existing library"))
        restore(legacy, overwrite = false)
        assertNotNull(db.bookInformationDao().getEntity(marker))
        val before = requireNotNull(manager.exportAppLocalData().get())
        assertEquals(3, before.localDataList.flatMap { it.bookInformationEntities }.size)
        val uri = Uri.parse("content://backup-conversion/merged")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        val worker = ExportDataWork(context, workerParameters(workDataOf("uri" to uri.toString())), manager)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        restore(output.toByteArray())
        assertEquals("existing library", db.bookInformationDao().getEntity(marker)!!.title)
        assertPayloadEquals(before, requireNotNull(manager.exportAppLocalData().get()))
    }

    private fun assertPayloadEquals(expected: AppLocalData, actual: AppLocalData, label: String = "payload") {
        assertArrayEquals(label, Cbor.encodeToByteArray(expected), Cbor.encodeToByteArray(actual))
    }

    private fun legacyBytes() = requireNotNull(javaClass.getResourceAsStream(
        "/backups/before-nextvol.lnr")).use { it.readBytes() }

    private suspend fun restore(bytes: ByteArray, overwrite: Boolean = true) {
        val uri = Uri.parse("content://backup-conversion/input-${sequence++}")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        val worker = ImportDataWork(context, workerParameters(workDataOf(
            "uri" to uri.toString(), "overwrite" to overwrite)), manager)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
