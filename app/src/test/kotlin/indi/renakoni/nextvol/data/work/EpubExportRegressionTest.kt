package indi.renakoni.nextvol.data.work

import indi.renakoni.nextvol.data.export.EpubShareFiles
import indi.renakoni.nextvol.data.export.ExportBookToEpubUseCase

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.utils.ImageUtils
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.dom4j.DocumentHelper
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.zip.ZipFile

/** Actual worker, component decoder and ZIP writer; provider and network failures are controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class EpubExportRegressionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val book = SourceBookId(Identifier("fixture", "epub71"), "book")
    private val repository = mockk<BookRepository>()
    private val items = mutableListOf<DownloadItem>()
    private val progress = mockk<DownloadProgressRepository> { every { addExportItem(capture(items)) } just Runs }
    private val decoder = ContentJsonDecoder(ContentComponentRegistry())
    private var volumes = book.bind(BookVolumes("book", (1..2).map {
        Volume("v$it", "Same volume", listOf(ChapterInformation("c$it", "Chapter $it")))
    }))
    private var lastWorker: ExportBookToEPUBWork? = null
    private val files get() = lastWorker?.let { EpubShareFiles.files(context, it.id) }.orEmpty()
    private lateinit var output: File

    @Before fun prepare() {
        stubExportRepository(repository)
        mockkObject(ImageUtils)
        every { repository.getBookInformationFlow(book.storageKey, any()) } returns flowOf(Ok(book.bind(
            BookInformation("book", "Probe book", author = "Author", description = "", publishingHouse = "", wordCount = WordCount(2),
                lastUpdated = LocalDateTime.of(2026, 9, 19, 0, 0), isComplete = false)
        )))
        every { repository.getBookVolumesFlow(book.storageKey, any()) } answers { flowOf(Ok(volumes)) }
        coEvery { repository.volumeCover(book, any(), any(), any()) } returns Ok(null)
        setBody(textBody("Retained text"))
        coEvery { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) } returns
            Ok(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
    }

    @After fun finish() {
        unmockkObject(ImageUtils)
        files.forEach { it.copyTo(output.resolve(it.name), overwrite = true) }
        unmockkObject(EpubShareFiles)
    }

    private fun startCase(name: String) {
        output = File("build/epub-compliance/issue71", name).apply { mkdirs() }
    }

    private fun textBody(text: String, id: String = "lightnovelreader:simple_text") = buildJsonObject {
        putJsonArray("components") { add(buildJsonObject {
            put("id", id); put("data", SimpleTextComponentData(text).toJsonElement())
        }) }
    }

    private fun imageBody(url: String) = buildJsonObject {
        putJsonArray("components") { add(buildJsonObject {
            put("id", "lightnovelreader:image")
            put("data", ImageComponentData(Uri.parse(url)).toJsonElement())
        }) }
    }

    private fun setBody(body: JsonObject) {
        every { repository.getChapterContentFlow(any(), book.storageKey, any()) } answers {
            flowOf(Ok(ChapterContent(firstArg(), "Chapter", body)))
        }
    }

    private fun worker(type: String = "VOLUMES",
                       selected: String = volumes.volumes.joinToString(",") { it.volumeId }): ExportBookToEPUBWork =
        ExportBookToEPUBWork(context, workerParameters(workDataOf(
            "bookId" to book.storageKey, "title" to "Probe book", "exportType" to type,
            "selectedVolume" to selected, "includeImages" to true
        )), ExportBookToEpubUseCase(context, repository, progress, decoder, exportDownloads())).also { lastWorker = it }

    private fun bodies(file: File): List<String> = ZipFile(file).use { zip ->
        val opf = DocumentHelper.parseText(zip.getInputStream(zip.getEntry("EPUB/content.opf")).reader().readText())
        val manifest = opf.rootElement.element("manifest").elements("item")
        opf.rootElement.element("spine").elements("itemref").map { ref ->
            val href = manifest.single { it.attributeValue("id") == ref.attributeValue("idref") }.attributeValue("href")
            val doc = DocumentHelper.parseText(zip.getInputStream(zip.getEntry("EPUB/$href")).reader().readText())
            doc.rootElement.element("body").stringValue
        }
    }

    @Test fun normalSameNamedVolumesExportInCatalogOrderEvenWhenSelectionIsReversed() = runTest {
        startCase("normal-batch")
        every { repository.getChapterContentFlow(any(), book.storageKey, any()) } answers {
            val id = firstArg<String>()
            flowOf(Ok(ChapterContent(id, "Chapter", textBody(id))))
        }
        assertTrue(worker(selected = volumes.volumes.reversed().joinToString(",") { it.volumeId }).doWork() is ListenableWorker.Result.Success)
        assertEquals(2, files.size)
        assertEquals(volumes.volumes.flatMap { it.chapters }.map { it.id }, files.flatMap(::bodies))
    }

    @Test fun badImageInSecondVolumeBlocksGoodFirstVolumeBeforeAnyOutput() = runTest {
        startCase("one-bad-image")
        val badId = volumes.volumes[1].chapters.single().id
        every { repository.getChapterContentFlow(badId, book.storageKey, any()) } returns
            flowOf(Ok(ChapterContent(badId, "Chapter", imageBody("https://fixture.invalid/missing.jpg"))))
        coEvery { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) } returns Err(IOException("missing illustration"))
        assertTrue(worker().doWork() is ListenableWorker.Result.Failure)
        assertTrue(files.isEmpty())
    }

    @Test fun legacyShortComponentIdsRetainTheirText() = runTest {
        startCase("legacy-id-body-loss")
        setBody(textBody("Legacy text", "simple_text"))
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertEquals(listOf("Legacy text", "Legacy text"), files.flatMap(::bodies))
    }

    @Test fun emptyVolumeFailsWithoutEscapingWorkerFailureHandling() = runTest {
        startCase("empty-volume")
        volumes = book.bind(BookVolumes("book", listOf(Volume("empty", "Empty volume", emptyList()))))
        val result = worker("BOOK").doWork() as ListenableWorker.Result.Failure
        assertEquals("empty_volume", result.outputData.getString("reason"))
        assertEquals(-1f, items.single().progress)
    }

    @Test fun emptyVolumeInSplitModeFailsBeforeCreatingFiles() = runTest {
        startCase("empty-split-volume")
        volumes = book.bind(BookVolumes("book", listOf(Volume("empty", "Empty volume", emptyList()))))
        val result = worker().doWork() as ListenableWorker.Result.Failure
        assertEquals("empty_volume", result.outputData.getString("reason"))
        assertTrue(files.isEmpty())
    }

    @Test fun collidingVolumeCoverUrlsRemainDistinct() = runTest {
        startCase("volume-cover-collision")
        volumes = book.bind(BookVolumes("book", (1..3).map {
            Volume("v$it", "Volume $it", listOf(ChapterInformation("c$it", "Chapter $it")))
        }))
        val urls = listOf(Uri.parse("https://fixture.invalid/Aa.jpg"), Uri.parse("https://fixture.invalid/BB.jpg"))
        assertNotEquals(urls[0], urls[1]); assertEquals(urls[0].hashCode(), urls[1].hashCode())
        val selected = volumes.volumes.drop(1)
        selected.forEachIndexed { index, volume ->
            coEvery { repository.volumeCover(book, volume, any(), any()) } returns Ok(urls[index])
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (index == 0) Color.RED else Color.BLUE)
            }
            coEvery { ImageUtils.uriToBitmap(urls[index], context, book.storageKey, any(), any(), false) } returns Ok(bitmap)
        }
        assertTrue(worker(selected = selected.joinToString(",") { it.volumeId }).doWork() is ListenableWorker.Result.Success)
        val covers = files.map { file -> ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("EPUB/cover.jpg")).readBytes() } }
        assertEquals(2, covers.size); assertFalse(covers[0].contentEquals(covers[1]))
    }

    @Test fun optionalVolumeCoverLookupFailureUsesDefaultCover() = runTest {
        startCase("cover-lookup-failure")
        coEvery { repository.volumeCover(book, any(), any(), any()) } throws IOException("cover unavailable")
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertEquals(listOf("Retained text", "Retained text"), files.flatMap(::bodies))
    }

    @Test fun emptySelectionNeverFallsBackToWholeBook() = runTest {
        startCase("empty-selection")
        val result = worker(selected = "").doWork() as ListenableWorker.Result.Failure
        assertEquals("missing_volume", result.outputData.getString("reason"))
        assertTrue(files.isEmpty())
        verify(exactly = 0) { repository.getChapterContentFlow(any(), any(), any()) }
    }

    @Test fun publishFailureDoesNotExposeAnyArchives() = runTest {
        startCase("publish-failure")
        mockkObject(EpubShareFiles)
        every { EpubShareFiles.publish(context, any(), any(), any()) } throws IOException("disk full")
        val result = worker().doWork() as ListenableWorker.Result.Failure
        assertEquals("share_failed", result.outputData.getString("reason"))
        assertTrue(files.isEmpty())
        assertEquals(-1f, items.single().progress)
    }

    @Test fun restartingAnAlreadyPublishedRequestKeepsTheOriginalRecipientFiles() = runTest {
        startCase("restart-after-publication")
        val first = worker()
        assertTrue(first.doWork() is ListenableWorker.Result.Success)
        val original = files.map { it.readBytes().toList() }
        val interrupted = context.cacheDir.resolve("epub/${book.fileKey}/${first.id}/outputs/partial.epub")
        interrupted.parentFile!!.mkdirs()
        interrupted.writeText("interrupted")
        val retry = ExportBookToEPUBWork(context, workerParameters(first.inputData, first.id),
            ExportBookToEpubUseCase(context, repository, progress, decoder, exportDownloads()))
        assertTrue(retry.doWork() is ListenableWorker.Result.Success)
        assertEquals(original, files.map { it.readBytes().toList() })
        assertFalse(interrupted.exists())
        assertEquals(1, items.size)
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notification = org.robolectric.Shadows.shadowOf(manager).getNotification(ExportBookToEPUBWork.ofId(book.storageKey), 0)
        val intent = org.robolectric.Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(indi.renakoni.nextvol.ui.book.detail.EpubShareActivity::class.java.name, intent.component!!.className)
        assertEquals(first.id.toString(), intent.data!!.schemeSpecificPart)
    }

    @Test fun cancellationAtPublicationRemovesAllShareFiles() = runTest {
        startCase("cancel-publication")
        lateinit var job: Job
        mockkObject(EpubShareFiles)
        every { EpubShareFiles.publish(context, any(), any(), any()) } answers { callOriginal(); job.cancel() }
        job = launch(start = CoroutineStart.LAZY) { worker().doWork() }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(files.isEmpty())
        assertEquals(-1f, items.single().progress)
        assertFalse(context.cacheDir.resolve("epub/${book.fileKey}/${lastWorker!!.id}").exists())
    }

    @Test fun longUnicodeVolumeNamesKeepDistinctSuffixAndExtension() = runTest {
        startCase("long-volume-names")
        volumes = volumes.copy(volumes = volumes.volumes.map { it.copy(volumeTitle = "长标题".repeat(100)) })
        assertTrue(worker().doWork() is ListenableWorker.Result.Success)
        assertEquals(2, files.map { it.name }.distinct().size)
        files.forEach { file ->
            assertTrue(file.name.endsWith(".epub"))
            assertTrue(file.name.toByteArray(Charsets.UTF_8).size <= 255)
        }
    }
}
