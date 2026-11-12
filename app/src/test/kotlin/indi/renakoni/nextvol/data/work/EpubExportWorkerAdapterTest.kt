package indi.renakoni.nextvol.data.work

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.google.common.util.concurrent.SettableFuture
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.export.*
import indi.renakoni.nextvol.ui.book.detail.EpubShareActivity
import io.mockk.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EpubExportWorkerAdapterTest {
    private val context = RuntimeEnvironment.getApplication()
    private val book = SourceBookId(Identifier("fixture", "adapter"), "book")
    private val useCase = spyk(ExportBookToEpubUseCase(context, mockk(), mockk(), mockk(), mockk()))
    private val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    @Test @Config(sdk = [27, 34])
    fun persistedKeysMapToTypedRequestAndCompletionIntentKeepsRequestId() = runTest {
        val id = UUID.randomUUID()
        val selected = listOf("v2", "v1").map { BookIdentity.volumeKey(book, it) }
        val input = workDataOf("bookId" to book.storageKey, "title" to "Book", "exportType" to "VOLUMES",
            "selectedVolume" to selected.joinToString(","), "includeImages" to false, "downloadGeneration" to 7L)
        val request = slot<EpubExportRequest>()
        coEvery { useCase.execute(capture(request), any()) } coAnswers {
            secondArg<(EpubExportProgress) -> Unit>().invoke(
                EpubExportProgress(40, EpubExportProgress.Phase.CHAPTERS, 2, 2, "Volume", "Chapter"))
            val progress = notifications.getNotification(null, id.hashCode())
            assertEquals(40, progress.extras.getInt(Notification.EXTRA_PROGRESS))
            assertTrue(progress.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Volume / Chapter"))
            EpubExportResult.Success(id, book, 2, 2)
        }
        val parameters = workerParameters(input, id)
        val result = ExportBookToEPUBWork(context, parameters, useCase).doWork() as ListenableWorker.Result.Success
        assertEquals(EpubExportRequest(id, book, ExportType.VOLUMES, selected.toSet(), false, 7), request.captured)
        assertEquals(workDataOf("exportId" to id.toString(), "completedVolumes" to 2, "totalVolumes" to 2), result.outputData)
        verify { parameters.foregroundUpdater.setForegroundAsync(context, id, match {
            it.notificationId == id.hashCode() &&
                (Build.VERSION.SDK_INT < 29 || it.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }) }
        val notification = notifications.getNotification(ExportBookToEPUBWork.ofId(book.storageKey), 0)
        assertEquals(context.getString(R.string.epub_export_share_ready), notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(EpubShareActivity::class.java.name, intent.component!!.className)
        assertEquals(id.toString(), intent.data!!.schemeSpecificPart)
    }

    @Test fun legacyDefaultsAndEnumSerializationRemainUnchanged() = runTest {
        val request = slot<EpubExportRequest>()
        coEvery { useCase.execute(capture(request), any()) } coAnswers { EpubExportResult.Success(firstArg<EpubExportRequest>().id, book, 1, 1) }
        val worker = ExportBookToEPUBWork(context, workerParameters(workDataOf("bookId" to book.storageKey, "exportType" to "BOOK")), useCase)
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertEquals(EpubExportRequest(worker.id, book, ExportType.BOOK), request.captured)
        assertEquals(listOf("BOOK", "VOLUMES"), ExportType.entries.map { it.name })
        assertEquals("export_to_epub:${book.storageKey}", ExportBookToEPUBWork.ofId(book.storageKey))
    }

    @Test fun invalidIdentityTypeAndForeignSelectionNeverReachTheUseCase() = runTest {
        val other = SourceBookId(Identifier("fixture", "other"), "book")
        val cases = listOf(
            workDataOf("bookId" to "bare-id", "exportType" to "BOOK") to "invalid_book_identity",
            workDataOf("bookId" to book.storageKey, "exportType" to "UNKNOWN") to "invalid_export_type",
            workDataOf("bookId" to book.storageKey, "exportType" to "VOLUMES",
                "selectedVolume" to BookIdentity.volumeKey(other, "v1")) to "invalid_volume_identity",
        )
        for ((input, reason) in cases) {
            val result = ExportBookToEPUBWork(context, workerParameters(input), useCase).doWork() as ListenableWorker.Result.Failure
            assertEquals(reason, result.outputData.getString("reason"))
            assertEquals(if (reason == "invalid_book_identity") null else book.storageKey, result.outputData.getString("bookId"))
            assertEquals(setOf("reason", "bookId"), result.outputData.keyValueMap.keys)
        }
        verify { useCase wasNot Called }
    }

    @Test fun failurePayloadKeepsStageLocationCountsAndLocalizedNotification() = runTest {
        val failure = EpubExportResult.Failure(book, "image_failed", "images", "V".repeat(200), "C".repeat(200), 0, 2)
        coEvery { useCase.execute(any(), any()) } returns failure
        val worker = ExportBookToEPUBWork(context, workerParameters(workDataOf("bookId" to book.storageKey, "exportType" to "VOLUMES")), useCase)
        val output = (worker.doWork() as ListenableWorker.Result.Failure).outputData
        assertEquals(setOf("reason", "bookId", "stage", "volume", "chapter", "completedVolumes", "totalVolumes", "message"), output.keyValueMap.keys)
        assertEquals("image_failed", output.getString("reason"))
        assertEquals(book.storageKey, output.getString("bookId"))
        assertEquals("images", output.getString("stage"))
        assertEquals("V".repeat(160), output.getString("volume"))
        assertEquals("C".repeat(160), output.getString("chapter"))
        assertEquals(0, output.getInt("completedVolumes", -1))
        assertEquals(2, output.getInt("totalVolumes", -1))
        assertEquals(context.getString(R.string.epub_export_failure_detail,
            context.getString(R.string.epub_export_error_image),
            "${failure.volume} / ${failure.chapter}".take(240), 0, 2), output.getString("message"))
        val notification = notifications.getNotification(ExportBookToEPUBWork.ofId(book.storageKey), 0)
        assertEquals(output.getString("message"), notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(notification.contentIntent)
    }

    @Test fun cancellationPropagatesWithItsFailureLocationAndWithoutACompletionIntent() = runTest {
        val started = CompletableDeferred<Unit>()
        coEvery { useCase.execute(any(), any()) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() }
            catch (cancelled: CancellationException) {
                throw EpubExportCancelled(EpubExportResult.Failure(book, "cancelled", "build", "Volume", "Chapter", 0, 2), cancelled)
            }
        }
        val worker = ExportBookToEPUBWork(context, workerParameters(workDataOf("bookId" to book.storageKey, "exportType" to "BOOK")), useCase)
        val job = launch { worker.doWork(); fail("Cancellation must not produce a worker result") }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        val notification = notifications.getNotification(ExportBookToEPUBWork.ofId(book.storageKey), 0)
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains(context.getString(R.string.epub_export_error_cancelled)))
        assertTrue(text.contains("Volume / Chapter"))
        assertNull(notification.contentIntent)
    }

    @Test fun foregroundFailureClearsInterruptedStagingWithoutTouchingOtherExports() = runTest {
        assertForegroundCleanup(cancel = false)
    }

    @Test fun cancellationWhileStartingForegroundClearsInterruptedStagingWithoutTouchingOtherExports() = runTest {
        assertForegroundCleanup(cancel = true)
    }

    private suspend fun assertForegroundCleanup(cancel: Boolean): Unit = coroutineScope {
        val id = UUID.randomUUID()
        val otherBook = SourceBookId(Identifier("fixture", "other"), "book")
        val staging = context.cacheDir.resolve("epub/${book.fileKey}/$id")
        val otherRequestStaging = context.cacheDir.resolve("epub/${book.fileKey}/${UUID.randomUUID()}")
        val otherBookStaging = context.cacheDir.resolve("epub/${otherBook.fileKey}/$id")
        val published = EpubShareFiles.directory(context, id)
        try {
            staging.resolve("outputs/interrupted.epub").apply { parentFile!!.mkdirs(); writeText("unfinished") }
            val retained = listOf(otherRequestStaging.resolve("keep"), otherBookStaging.resolve("keep"),
                published.resolve("Book.epub"))
            retained.forEach { it.parentFile!!.mkdirs(); it.writeText("keep") }
            published.resolve(".count").writeText("1")
            val foreground = SettableFuture.create<Void>()
            val started = CompletableDeferred<Unit>()
            val parameters = workerParameters(workDataOf("bookId" to book.storageKey, "exportType" to "BOOK"), id)
            every { parameters.foregroundUpdater.setForegroundAsync(context, id, any()) } answers {
                started.complete(Unit)
                foreground
            }
            val worker = ExportBookToEPUBWork(context, parameters, useCase)
            if (cancel) {
                val job = launch { worker.doWork(); fail("Cancellation must not produce a worker result") }
                started.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(foreground.isCancelled)
            } else {
                foreground.setException(IllegalStateException("Foreground unavailable"))
                val result = worker.doWork() as ListenableWorker.Result.Failure
                assertEquals("export_failed", result.outputData.getString("reason"))
                assertEquals("preparing", result.outputData.getString("stage"))
            }
            coVerify(exactly = 0) { useCase.execute(any(), any()) }
            assertFalse("Interrupted staging must be removed even before the use case starts", staging.exists())
            retained.forEach { assertEquals("keep", it.readText()) }
            assertEquals(listOf(published.resolve("Book.epub")), EpubShareFiles.files(context, id))
            val notification = notifications.getNotification(ExportBookToEPUBWork.ofId(book.storageKey), 0)
            assertNull(notification.contentIntent)
            if (cancel) assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                .contains(context.getString(R.string.epub_export_error_cancelled)))
        } finally {
            listOf(staging, otherRequestStaging, otherBookStaging, published).forEach { it.deleteRecursively() }
        }
    }
}
