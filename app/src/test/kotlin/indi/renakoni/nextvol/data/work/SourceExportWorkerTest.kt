package indi.renakoni.nextvol.data.work

import indi.renakoni.nextvol.data.export.EpubShareFiles
import indi.renakoni.nextvol.data.export.ExportBookToEpubUseCase

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceExportWorkerTest {
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")

    @Test fun fullAndSelectedVolumeExportsKeepSameNamedBooksAndVolumesIndependent() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = mockk<BookRepository>()
        stubExportRepository(repository)
        val items = mutableListOf<DownloadItem>()
        val progress = mockk<DownloadProgressRepository> { every { addExportItem(capture(items)) } just Runs }
        val files = mutableListOf<java.io.File>()
        for (book in listOf(a, b)) {
            val volumes = book.bind(BookVolumes("same", listOf("v1", "v2").map {
                Volume(it, "Same volume", listOf(ChapterInformation("c-$it", "Chapter")))
            }))
            val info = book.bind(BookInformation("same", "Same title", author = "Same author",
                description = "", publishingHouse = "", wordCount = WordCount(1),
                lastUpdated = LocalDateTime.of(2026, 9, 9, 0, 0), isComplete = false))
            every { repository.getBookInformationFlow(book.storageKey, any()) } returns flowOf(Ok(info))
            every { repository.getBookVolumesFlow(book.storageKey, any()) } returns flowOf(Ok(volumes))
            for (chapter in volumes.volumes.flatMap { it.chapters }) {
                every { repository.getChapterContentFlow(chapter.id, book.storageKey, any()) } returns
                    flowOf(Ok(ChapterContent(chapter.id, "Chapter", buildJsonObject { put("components", JsonArray(emptyList())) })))
            }
            coEvery { repository.volumeCover(book, any(), any(), any()) } returns Ok(null)
            for (type in listOf("BOOK", "VOLUMES")) {
                val workId = UUID.randomUUID()
                val data = workDataOf("bookId" to book.storageKey, "exportType" to type,
                    "selectedVolume" to volumes.volumes.joinToString(",") { it.volumeId })
                val otherWork = context.cacheDir.resolve("epub/${book.fileKey}/other-work/keep").apply {
                    parentFile!!.mkdirs(); writeText("keep")
                }
                val worker = ExportBookToEPUBWork(context, workerParameters(data, workId),
                    ExportBookToEpubUseCase(context, repository, progress, ContentJsonDecoder(ContentComponentRegistry()), exportDownloads()))
                assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("ExportEPUB").toString(),
                    worker.doWork() is ListenableWorker.Result.Success)
                assertFalse(context.cacheDir.resolve("epub/${book.fileKey}/$workId").exists())
                assertTrue(otherWork.exists())
                files += EpubShareFiles.files(context, workId)
            }
            coVerify(exactly = 1) { repository.volumeCover(book, match { it.volumeId == volumes.volumes[1].volumeId }, any(), any()) }
        }
        assertEquals(6, files.distinct().size)
        for (file in files) ZipFile(file).use { assertTrue(it.size() > 0) }
        assertTrue(items.all { it.progress == 1f })
        assertEquals(listOf(a.storageKey, a.storageKey, b.storageKey, b.storageKey), items.map { it.bookId })
    }

    @Test fun selectedVolumeFromAnotherSourceIsRejectedBeforeRepositoryAccess() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val repository = mockk<BookRepository>()
        val data = workDataOf("bookId" to a.storageKey, "exportType" to "VOLUMES",
            "selectedVolume" to BookIdentity.volumeKey(b, "v1"))
        val result = ExportBookToEPUBWork(context, workerParameters(data),
            ExportBookToEpubUseCase(context, repository, mockk(), mockk(), exportDownloads())).doWork()
            as ListenableWorker.Result.Failure
        assertEquals("invalid_volume_identity", result.outputData.getString("reason"))
        verify { repository wasNot Called }
    }
}
