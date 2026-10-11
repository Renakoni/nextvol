package indi.renakoni.nextvol.data.backup

import android.app.Application
import android.net.Uri
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.ExternalFiles
import indi.renakoni.nextvol.data.local.LocalDataManager
import indi.renakoni.nextvol.data.local.cbor.AppLocalData
import indi.renakoni.nextvol.data.local.cbor.LocalData
import indi.renakoni.nextvol.data.work.ExportDataWork
import indi.renakoni.nextvol.data.work.ImportDataWork
import indi.renakoni.nextvol.data.work.workerParameters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BackupWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val data = AppLocalData(localDataList = listOf(LocalData.empty()), globalLocalData = LocalData.empty())

    @Test fun stagedExternalBackupRestoresWithoutTheSenderPermissionAndIsCleanedOnSuccessOrFailure() = runBlocking {
        val manager = mockk<LocalDataManager>(relaxed = true)
        every { manager.currentAppDataVersion } returns 1
        coEvery { manager.importAppLocalData(any(), any()) } returns Ok(Unit)
        for (valid in listOf(true, false)) {
            val staged = ExternalFiles.backupFile(context, "${java.util.UUID.randomUUID()}.nvbackup")
            staged.parentFile!!.mkdirs()
            if (valid) BackupArchive.write(staged, data, BackupArchive.manifest(BackupKind.USER_DATA, emptySet()))
            else staged.writeText("not a backup")
            val worker = ImportDataWork(context, workerParameters(workDataOf(
                ExternalFiles.STAGED_BACKUP to staged.name, "overwrite" to false)), manager)
            val result = worker.doWork()
            if (valid) assertEquals(ListenableWorker.Result.success(), result)
            else assertEquals(ListenableWorker.Result.failure(workDataOf(BackupException.ERROR_KEY to BackupFailure.INVALID.name)), result)
            assertFalse(staged.exists())
        }
        coVerify(exactly = 1) { manager.importAppLocalData(data, false) }
    }

    @Test fun interruptedRestoreRetainsTheStagedBackupForWorkManagerToRestart() = runBlocking {
        val manager = mockk<LocalDataManager>(relaxed = true)
        every { manager.currentAppDataVersion } returns 1
        val staged = ExternalFiles.backupFile(context, "${java.util.UUID.randomUUID()}.nvbackup")
        staged.parentFile!!.mkdirs()
        BackupArchive.write(staged, data, BackupArchive.manifest(BackupKind.USER_DATA, emptySet()))
        val parameters = workerParameters(workDataOf(ExternalFiles.STAGED_BACKUP to staged.name))
        coEvery { manager.importAppLocalData(any(), any()) } throws kotlinx.coroutines.CancellationException("stopped")
        try {
            ImportDataWork(context, parameters, manager).doWork()
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(staged.exists())
        coEvery { manager.importAppLocalData(any(), any()) } returns Ok(Unit)
        assertEquals(ListenableWorker.Result.success(), ImportDataWork(context, parameters, manager).doWork())
        assertFalse(staged.exists())
    }

    @Test fun malformedAndFutureArchivesReportSpecificFailuresWithoutRestoring() = runBlocking {
        val future = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{\"format\":\"nextvol-backup\",\"version\":99}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data.cbor"))
                zip.closeEntry()
            }
        }.toByteArray()
        for ((reason, bytes) in listOf(BackupFailure.INVALID to byteArrayOf(), BackupFailure.UNSUPPORTED_VERSION to future)) {
            val manager = mockk<LocalDataManager>(relaxed = true)
            val uri = Uri.parse("content://backup-test/$reason")
            shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
            val worker = ImportDataWork(context, workerParameters(workDataOf("uri" to uri.toString(), "overwrite" to true)), manager)
            assertEquals(ListenableWorker.Result.failure(workDataOf(BackupException.ERROR_KEY to reason.name)), worker.doWork())
            verify(exactly = 0) { manager.validateBackup(any()) }
            coVerify(exactly = 0) { manager.importAppLocalData(any(), any()) }
        }
    }

    @Test fun deeplyNestedManifestReportsInvalidWithoutRestoringAndCleansCache() = runBlocking {
        val nested = "[".repeat(3900) + "0" + "]".repeat(3900)
        val text = """{"format":"nextvol-backup","version":1,"kind":"user-data","contents":[],"padding":$nested}"""
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(text.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("data.cbor"))
                zip.closeEntry()
            }
        }.toByteArray()
        val manager = mockk<LocalDataManager>(relaxed = true)
        val uri = Uri.parse("content://backup-test/deep-manifest")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        val cachedFiles = context.cacheDir.listFiles()!!.toSet()
        val worker = ImportDataWork(context, workerParameters(workDataOf("uri" to uri.toString(), "overwrite" to true)), manager)
        assertEquals(ListenableWorker.Result.failure(workDataOf(BackupException.ERROR_KEY to BackupFailure.INVALID.name)), worker.doWork())
        verify(exactly = 0) { manager.validateBackup(any()) }
        coVerify(exactly = 0) { manager.importAppLocalData(any(), any()) }
        assertEquals(cachedFiles, context.cacheDir.listFiles()!!.toSet())
    }

    @Test fun businessDataVersionRemainsCheckedIndependentlyOfEnvelopeVersion() = runBlocking {
        val manager = mockk<LocalDataManager>(relaxed = true)
        every { manager.currentAppDataVersion } returns 1
        val file = context.cacheDir.resolve("future-payload.nvbackup")
        try {
            BackupArchive.write(file, data.copy(version = 99), BackupArchive.manifest(BackupKind.USER_DATA, emptySet()))
            val uri = Uri.parse("content://backup-test/future-payload")
            shadowOf(context.contentResolver).registerInputStream(uri, file.inputStream())
            val worker = ImportDataWork(context, workerParameters(workDataOf("uri" to uri.toString())), manager)
            assertEquals(ListenableWorker.Result.failure(workDataOf(BackupException.ERROR_KEY to BackupFailure.UNSUPPORTED_VERSION.name)), worker.doWork())
            coVerify(exactly = 0) { manager.importAppLocalData(any(), any()) }
        } finally { file.delete() }
    }

    @Test fun exporterRecordsSelectedContentsAndItsArchiveImportsThroughTheWorker() = runBlocking {
        val manager = mockk<LocalDataManager>(relaxed = true)
        every { manager.currentAppDataVersion } returns 1
        coEvery { manager.exportAppLocalData(any(), any(), any(), any(), any()) } returns Ok(data)
        coEvery { manager.importAppLocalData(any(), any()) } returns Ok(Unit)
        val bytes = ByteArrayOutputStream()
        val uri = Uri.parse("content://backup-test/roundtrip")
        shadowOf(context.contentResolver).registerOutputStream(uri, bytes)
        val exporter = ExportDataWork(context, workerParameters(workDataOf(
            "uri" to uri.toString(), "exportLocalBookCache" to false, "exportBookshelf" to true,
            "exportReadingData" to false, "exportSetting" to false, "exportBookmark" to true,
        )), manager)
        assertEquals(ListenableWorker.Result.success(), exporter.doWork())
        val file = context.cacheDir.resolve("worker-output.nvbackup")
        try {
            file.writeBytes(bytes.toByteArray())
            ZipFile(file).use { zip ->
                val text = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                val manifest = Json.decodeFromString<BackupManifest>(text)
                assertEquals(BackupKind.USER_DATA, manifest.kind)
                assertEquals(setOf(BackupContent.BOOKSHELF, BackupContent.BOOKMARKS), manifest.contents)
            }
            shadowOf(context.contentResolver).registerInputStream(uri, bytes.toByteArray().inputStream())
            val importer = ImportDataWork(context, workerParameters(workDataOf("uri" to uri.toString(), "overwrite" to false)), manager)
            assertEquals(ListenableWorker.Result.success(), importer.doWork())
            coVerify(exactly = 1) { manager.importAppLocalData(data, false) }
        } finally { file.delete() }
    }
}
