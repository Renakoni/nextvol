package indi.renakoni.nextvol.data.export

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.data.work.exportDownloads
import indi.renakoni.nextvol.data.work.stubExportRepository
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.ZipFile

/** Real decoder, image preparation and EPUB writer, without creating a Worker or WorkManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class EpubExportUseCaseTest {
    private val context = RuntimeEnvironment.getApplication()
    private val book = SourceBookId(Identifier("fixture", "export-use-case"), "book")
    private val repository = mockk<BookRepository>()
    private val downloads = exportDownloads()
    private val items = mutableListOf<DownloadItem>()
    private val progress = mockk<DownloadProgressRepository> { every { addExportItem(capture(items)) } just Runs }
    private val useCase = ExportBookToEpubUseCase(context, repository, progress,
        ContentJsonDecoder(ContentComponentRegistry()), downloads)
    private val requests = mutableListOf<EpubExportRequest>()
    private var catalog = book.bind(BookVolumes("book", (1..2).map {
        Volume("v$it", "Same volume", listOf(ChapterInformation("c$it", "Chapter $it")))
    }))
    private var information = book.bind(BookInformation("book", "Book", author = "Author",
        description = "", publishingHouse = "", wordCount = WordCount(2),
        lastUpdated = LocalDateTime.of(2026, 9, 19, 0, 0), isComplete = false))

    @Before fun prepare() {
        stubExportRepository(repository)
        every { repository.getBookInformationFlow(book.storageKey, any()) } answers { flowOf(Ok(information)) }
        every { repository.getBookVolumesFlow(book.storageKey, any()) } answers { flowOf(Ok(catalog)) }
        every { repository.getChapterContentFlow(any(), book.storageKey, any()) } answers {
            flowOf(Ok(ChapterContent(firstArg(), "Chapter", text("Text ${firstArg<String>()}"))))
        }
        coEvery { repository.volumeCover(book, any(), any(), any()) } returns Ok(null)
        mockkObject(ImageUtils)
        coEvery { ImageUtils.uriToBitmap(any(), context, book.storageKey, any(), any(), false) } returns
            Ok(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
    }

    @After fun finish() {
        unmockkAll()
        requests.forEach {
            staging(it).deleteRecursively()
            EpubShareFiles.directory(context, it.id).deleteRecursively()
        }
    }

    private fun request(type: ExportType = ExportType.VOLUMES, images: Boolean = true,
                        selected: Set<String> = catalog.volumes.map { it.volumeId }.toSet()) =
        EpubExportRequest(UUID.randomUUID(), book, type, selected, images, 7).also(requests::add)

    private fun staging(request: EpubExportRequest) = context.cacheDir.resolve("epub/${book.fileKey}/${request.id}")

    private fun assertNoOutput(request: EpubExportRequest) {
        assertFalse(staging(request).exists())
        assertFalse(EpubShareFiles.directory(context, request.id).exists())
        assertEquals(-1f, items.last().progress)
    }

    private fun text(value: String, id: String = "lightnovelreader:simple_text") = buildJsonObject {
        putJsonArray("components") { add(buildJsonObject {
            put("id", id); put("data", SimpleTextComponentData(value).toJsonElement())
        }) }
    }

    private fun images(uri: Uri) = buildJsonObject {
        putJsonArray("components") { repeat(2) { add(buildJsonObject {
            put("id", "lightnovelreader:image"); put("data", ImageComponentData(uri).toJsonElement())
        }) } }
    }

    private fun body(content: JsonObject) {
        every { repository.getChapterContentFlow(any(), book.storageKey, any()) } answers {
            flowOf(Ok(ChapterContent(firstArg(), "Chapter", content)))
        }
    }

    private fun archiveText(file: File) = ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { it.name.endsWith(".xhtml") }
            .joinToString("\n") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }
    }

    @Test fun wholeBookAndReversedSelectionUseCatalogOrderAndIndependentAttemptState() = runTest {
        val failed = request(selected = emptySet())
        assertEquals("missing_volume", (useCase.execute(failed) as EpubExportResult.Failure).reason)
        for (type in ExportType.entries) {
            val request = request(type, selected = catalog.volumes.reversed().map { it.volumeId }.toSet())
            val updates = mutableListOf<EpubExportProgress>()
            val result = useCase.execute(request, updates::add) as EpubExportResult.Success
            val files = EpubShareFiles.files(context, request.id)
            assertEquals(request.id, result.id)
            assertEquals(book, result.book)
            assertEquals(if (type == ExportType.BOOK) 1 else 2, result.completedVolumes)
            assertEquals(result.completedVolumes, result.totalVolumes)
            assertEquals(result.completedVolumes, files.size)
            assertTrue(archiveText(files.first()).contains(catalog.volumes.first().chapters.single().id))
            assertTrue(archiveText(files.last()).contains(catalog.volumes.last().chapters.single().id))
            assertEquals(listOf(1, 2), updates.filter { it.phase == EpubExportProgress.Phase.CHAPTERS }.map { it.completed })
            assertEquals(1f, items.last().progress)
            assertFalse(staging(request).exists())
        }
    }

    @Test fun laterChapterFailureKeepsPreparedSourceContentForRetry() = runTest {
        val first = catalog.volumes.first().chapters.single().id
        val second = catalog.volumes.last().chapters.single().id
        coEvery { repository.exportChapter(book, second) } throws IOException("Chapter unavailable")
        val failed = request()
        assertTrue(useCase.execute(failed) is EpubExportResult.Failure)
        coVerify(exactly = 1) { downloads.saveCandidate(any(), match { it.id == first }, any()) }
        coVerify(exactly = 0) { downloads.saveChapter(any(), any(), any(), any(), any()) }
        assertNoOutput(failed)

        coEvery { repository.exportChapter(book, second) } returns Ok(ChapterContent(second, "Chapter 2", text("Recovered")))
        val retry = request()
        assertTrue(useCase.execute(retry) is EpubExportResult.Success)
        coVerify(exactly = 1) { repository.exportChapter(book, first) }
        coVerify(exactly = 2) { repository.exportChapter(book, second) }
        assertTrue(archiveText(EpubShareFiles.files(context, retry.id).first()).contains(first))
        assertTrue(archiveText(EpubShareFiles.files(context, retry.id).last()).contains("Recovered"))
    }

    @Test fun stagedConversionFeedsArchiveAndVolumeCoverUsesOnlyItsOwnSourceChapters() = runTest {
        coEvery { repository.exportContent(book, any()) } answers {
            secondArg<ChapterContent>().let { it.copy(content = text("Rendered ${it.id}")) }
        }
        val volume = catalog.volumes.last()
        coEvery { repository.volumeCover(book, volume, any(), any()) } answers {
            val raw = thirdArg<Map<String, ChapterContent>>()
            assertEquals(volume.chapters.map { it.id }.toSet(), raw.keys)
            assertTrue(raw.values.single().content.toString().contains("Text"))
            assertFalse(raw.values.single().content.toString().contains("Rendered"))
            Ok(null)
        }
        val request = request()
        assertTrue(useCase.execute(request) { update ->
            if (update.phase == EpubExportProgress.Phase.CHAPTERS) {
                val files = staging(request).listFiles()!!.filter { it.extension == "json" }
                assertEquals(update.completed, files.size)
                assertTrue(files.all { it.readText().contains("Rendered") })
            }
        } is EpubExportResult.Success)
        EpubShareFiles.files(context, request.id).forEach { assertTrue(archiveText(it).contains("Rendered")) }
        assertFalse(staging(request).exists())
    }

    @Test fun foreignVolumeIsRejectedBeforeAnyRepositoryOrDownloadAccess() {
        val other = SourceBookId(Identifier("fixture", "other"), book.remoteId)
        assertThrows(IllegalArgumentException::class.java) {
            request(selected = setOf(BookIdentity.volumeKey(other, "v1")))
        }
        verify { repository wasNot Called; downloads wasNot Called; progress wasNot Called }
    }

    @Test fun emptyOrMissingSelectionsNeverFallBackToTheWholeBook() = runTest {
        for (selected in listOf(emptySet(), setOf(BookIdentity.volumeKey(book, "missing")))) {
            val request = request(selected = selected)
            val result = useCase.execute(request) as EpubExportResult.Failure
            assertEquals("missing_volume", result.reason)
            assertEquals("preparing", result.stage)
            assertEquals(0, result.totalVolumes)
            assertNoOutput(request)
        }
        coVerify(exactly = 0) { downloads.begin(any(), any(), any()) }
    }

    @Test fun malformedAndUnknownComponentsFailWithLocationAndCloseTheAttempt() = runTest {
        for (content in listOf(buildJsonObject { put("components", "bad") }, text("lost", "unknown:component"))) {
            body(content)
            val request = request()
            val result = useCase.execute(request) as EpubExportResult.Failure
            assertEquals("invalid_content", result.reason)
            assertEquals("chapters", result.stage)
            assertEquals("Same volume", result.volume)
            assertEquals("Chapter 1", result.chapter)
            coVerify { downloads.finish(BookDownloadStore.Attempt(book, 7, request.id.toString()), false) }
            assertNoOutput(request)
        }
    }

    @Test fun textOnlySkipsIllustrationsButStillBuildsAnArchive() = runTest {
        body(images(Uri.parse("https://fixture.invalid/body.jpg")))
        val request = request(images = false)
        assertTrue(useCase.execute(request) is EpubExportResult.Success)
        coVerify(exactly = 0) { ImageUtils.uriToBitmap(any(), any(), any(), any(), any(), any()) }
        coVerify { downloads.saveChapter(any(), any(), any(), any(), requireImages = false) }
        EpubShareFiles.files(context, request.id).forEach { file ->
            assertFalse(archiveText(file).contains("body.jpg"))
            ZipFile(file).use { zip ->
                assertFalse(zip.entries().asSequence().any { it.name.startsWith("EPUB/image/") })
            }
        }
    }

    @Test fun duplicateIllustrationsAreFetchedOnceAndBadOptionalCoverFallsBack() = runTest {
        val image = Uri.parse("https://fixture.invalid/body.jpg")
        val cover = Uri.parse("https://fixture.invalid/cover.jpg")
        information = information.copy(coverUri = cover)
        body(images(image))
        coEvery { ImageUtils.uriToBitmap(cover, context, book.storageKey, true, any(), false) } returns Err(IOException("missing cover"))
        val request = request()
        val updates = mutableListOf<EpubExportProgress>()
        assertTrue(useCase.execute(request, updates::add) is EpubExportResult.Success)
        coVerify(exactly = 1) { ImageUtils.uriToBitmap(image, context, book.storageKey, false, any(), false) }
        assertTrue(updates.any { it.phase == EpubExportProgress.Phase.IMAGES })
        EpubShareFiles.files(context, request.id).forEach { file -> ZipFile(file).use { zip ->
            assertTrue(zip.entries().asSequence().any { it.name.endsWith(".jpg") && it.size > 0 })
            assertEquals(1, zip.entries().asSequence().count { it.name.startsWith("EPUB/image/") })
        } }
    }

    @Test fun badBodyImageFailsBeforePublishingAnyVolume() = runTest {
        body(images(Uri.parse("https://fixture.invalid/body.jpg")))
        coEvery { ImageUtils.uriToBitmap(any(), any(), any(), any(), any(), any()) } returns Err(IOException("bad image"))
        val request = request()
        val result = useCase.execute(request) as EpubExportResult.Failure
        assertEquals("image_failed", result.reason)
        assertEquals("images", result.stage)
        assertNoOutput(request)
    }

    @Test fun publicationFailureKeepsCompletedDownloadsAndOtherRecipients() = runTest {
        val previous = request()
        assertTrue(useCase.execute(previous) is EpubExportResult.Success)
        val original = EpubShareFiles.files(context, previous.id).map { it.readBytes().toList() }
        val request = request()
        val attempt = BookDownloadStore.Attempt(book, 7, request.id.toString())
        mockkObject(EpubShareFiles)
        every { EpubShareFiles.publish(context, request.id, any(), any()) } answers {
            coVerify { downloads.finish(attempt, true) }
            throw IOException("publication failed")
        }
        val result = useCase.execute(request) as EpubExportResult.Failure
        assertEquals("share_failed", result.reason)
        assertEquals("share", result.stage)
        assertEquals(0, result.completedVolumes)
        assertEquals(2, result.totalVolumes)
        coVerify(exactly = 0) { downloads.finish(attempt, false) }
        assertEquals(original, EpubShareFiles.files(context, previous.id).map { it.readBytes().toList() })
        assertNoOutput(request)
    }

    @Test fun publishedRequestReusesRecipientFilesWithoutReopeningDownloads() = runTest {
        val request = request()
        val result = useCase.execute(request)
        val original = EpubShareFiles.files(context, request.id).map { it.readBytes().toList() }
        staging(request).resolve("partial.epub").apply { parentFile!!.mkdirs(); writeText("interrupted") }
        clearMocks(repository, downloads, progress, answers = false)
        assertEquals(result, useCase.execute(request))
        assertEquals(original, EpubShareFiles.files(context, request.id).map { it.readBytes().toList() })
        assertFalse(staging(request).exists())
        verify { repository wasNot Called; downloads wasNot Called; progress wasNot Called }
    }

    @Test fun cancellationDuringPreparationClosesOnlyItsAttemptAndRetainsItsLocation() = runTest {
        val request = request()
        val started = CompletableDeferred<Unit>()
        coEvery { repository.exportChapter(book, any()) } coAnswers { started.complete(Unit); awaitCancellation() }
        var cancellation: EpubExportCancelled? = null
        val job = launch {
            try { useCase.execute(request) }
            catch (cancelled: EpubExportCancelled) { cancellation = cancelled; throw cancelled }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals("cancelled", cancellation!!.failure.reason)
        assertEquals("Chapter 1", cancellation.failure.chapter)
        coVerify(exactly = 1) { downloads.begin(book, 7, request.id.toString()) }
        coVerify { downloads.finish(BookDownloadStore.Attempt(book, 7, request.id.toString()), false) }
        assertNoOutput(request)
    }

    @Test fun cancellationOnEitherSideOfAtomicPublicationRemovesOnlyCurrentRequest() = runTest {
        val previous = request()
        assertTrue(useCase.execute(previous) is EpubExportResult.Success)
        val original = EpubShareFiles.files(context, previous.id).map { it.readBytes().toList() }
        mockkObject(EpubShareFiles)
        for (afterPublication in listOf(false, true)) {
            val request = request()
            lateinit var job: Job
            every { EpubShareFiles.publish(context, request.id, any(), any()) } answers {
                if (afterPublication) callOriginal()
                job.cancel()
                if (!afterPublication) callOriginal()
            }
            job = launch(start = CoroutineStart.LAZY) { useCase.execute(request) }
            job.start(); job.join()
            assertTrue(job.isCancelled)
            assertNoOutput(request)
            assertEquals(original, EpubShareFiles.files(context, previous.id).map { it.readBytes().toList() })
            coVerify(exactly = 0) { downloads.finish(BookDownloadStore.Attempt(book, 7, request.id.toString()), false) }
        }
    }

    @Test fun sourceVersionChangeRejectsCommitWithoutReclaimingReplacedOwnership() = runTest {
        val request = request()
        var revision = "1"
        every { repository.sourceRevision(book) } answers { revision }
        coEvery { downloads.saveChapter(any(), any(), any(), any(), any()) } coAnswers { revision = "2" }
        coEvery { downloads.finish(any(), false) } throws CancellationException("replaced attempt")
        val result = useCase.execute(request) as EpubExportResult.Failure
        assertEquals("cache_failed", result.reason)
        assertEquals("cache", result.stage)
        coVerify(exactly = 1) { downloads.begin(book, 7, request.id.toString()) }
        coVerify(exactly = 0) { downloads.finish(any(), true) }
        assertNoOutput(request)
    }

    @Test fun sourceCancellationWithoutJobCancellationRemainsATypedFailure() = runTest {
        coEvery { repository.exportChapter(book, any()) } throws CancellationException("source unloaded")
        val request = request()
        val result = useCase.execute(request) as EpubExportResult.Failure
        assertEquals("source_unavailable", result.reason)
        assertEquals("chapters", result.stage)
        assertNoOutput(request)
    }
}
