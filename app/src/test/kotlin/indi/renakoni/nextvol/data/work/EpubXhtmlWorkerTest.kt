package indi.renakoni.nextvol.data.work

import indi.renakoni.nextvol.data.export.EpubShareFiles
import indi.renakoni.nextvol.data.export.ExportBookToEpubUseCase

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.DownloadProgressRepository
import indi.renakoni.nextvol.utils.ImageUtils
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.dom4j.DocumentHelper
import org.dom4j.Element
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class EpubXhtmlWorkerTest {
    @Test fun actualComponentsHaveXhtmlNamesBeforePackaging() {
        val context = RuntimeEnvironment.getApplication()
        for (element in listOf(SimpleTextComponentData("a\nb").toHtmlElement(context),
            ImageComponentData(Uri.parse("https://example.org/image.png")).toHtmlElement(context))) {
            assertEquals("http://www.w3.org/1999/xhtml", element.namespaceURI)
            element.elements().forEach { assertEquals(element.namespaceURI, it.namespaceURI) }
        }
    }

    @Test fun workerExportsTextEmptyChaptersAndPngSourcesThroughRealDecoderAndWriter() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val book = SourceBookId(Identifier("fixture", "epub"), "book")
        val repository = mockk<BookRepository>()
        stubExportRepository(repository)
        val progress = mockk<DownloadProgressRepository>(relaxed = true)
        val registry = ContentComponentRegistry()
        val decoder = ContentJsonDecoder(registry)
        val imageUrls = listOf("https://example.org/Aa.png", "https://example.org/BB.png")
        assertEquals(imageUrls[0].hashCode(), imageUrls[1].hashCode())
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), png.take(4).toByteArray())
        val output = File("build/epub-compliance/worker").apply { mkdirs() }
        mockkObject(ImageUtils)
        coEvery { ImageUtils.uriToBitmap(any(), context, book.storageKey, allowMemoryCache = false) } answers {
            assertTrue(firstArg<Uri>().toString() in imageUrls)
            Ok(BitmapFactory.decodeByteArray(png, 0, png.size))
        }
        try {
            for ((volumeCount, missingMetadata) in listOf(1 to false, 2 to false, 1 to true)) {
                val volumes = book.bind(BookVolumes("book", (1..volumeCount).map { volume ->
                    Volume("v$volume", "Same volume", (1..2).map { ChapterInformation("v$volume-c$it", if (missingMetadata) " \t " else "Chapter & \uD83D\uDE00") })
                }))
                val info = book.bind(BookInformation("book", if (missingMetadata) "" else "Book $volumeCount", author = if (missingMetadata) "" else "Author",
                    description = "", publishingHouse = "", wordCount = WordCount(1),
                    lastUpdated = LocalDateTime.of(2026, 9, 10, 0, 0), isComplete = false))
                every { repository.getBookInformationFlow(book.storageKey, any()) } returns flowOf(Ok(info))
                every { repository.getBookVolumesFlow(book.storageKey, any()) } returns flowOf(Ok(volumes))
                coEvery { repository.volumeCover(book, any(), any(), any()) } returns Ok(null)
                volumes.volumes.flatMap { it.chapters }.forEachIndexed { index, chapter ->
                    val body = buildJsonObject {
                        putJsonArray("components") {
                            if (index % 2 == 0) {
                                add(buildJsonObject {
                                    put("id", "lightnovelreader:simple_text")
                                    put("data", SimpleTextComponentData("  A & <B> \uD83D\uDE00\uD840\uDC00\u0001\nZ\t&#0;  ").toJsonElement())
                                })
                                (imageUrls + imageUrls.first()).forEach { url ->
                                    add(buildJsonObject {
                                        put("id", "lightnovelreader:image")
                                        put("data", ImageComponentData(Uri.parse(url)).toJsonElement())
                                    })
                                }
                                add(buildJsonObject {
                                    put("id", "lightnovelreader:simple_text")
                                    put("data", SimpleTextComponentData("Extra text").toJsonElement())
                                })
                            }
                        }
                    }
                    every { repository.getChapterContentFlow(chapter.id, book.storageKey, any()) } returns
                        flowOf(Ok(ChapterContent(chapter.id, chapter.title, body)))
                }
                for (type in listOf("BOOK", "VOLUMES")) {
                    val name = if (missingMetadata) "book-missing-metadata.epub" else "book-$volumeCount.epub"
                    val data = workDataOf("bookId" to book.storageKey, "exportType" to type,
                        "selectedVolume" to volumes.volumes.joinToString(",") { it.volumeId })
                    val worker = ExportBookToEPUBWork(context, workerParameters(data),
                        ExportBookToEpubUseCase(context, repository, progress, decoder, exportDownloads()))
                    assertTrue(worker.doWork() is ListenableWorker.Result.Success)
                    val files = EpubShareFiles.files(context, worker.id)
                    assertEquals(if (type == "BOOK") 1 else volumeCount, files.size)
                    files.forEachIndexed { index, file ->
                        verifyEpub(file, if (type == "BOOK") volumeCount * 2 else 2, missingMetadata)
                        file.copyTo(output.resolve("$type-$index-$name"), overwrite = true)
                    }
                }
            }
        } finally {
            unmockkObject(ImageUtils)
        }
    }

    private fun verifyEpub(file: File, chapterCount: Int, missingMetadata: Boolean) = ZipFile(file).use { zip ->
        fun document(path: String) = DocumentHelper.parseText(zip.getInputStream(zip.getEntry("EPUB/$path")).reader().readText())
        val opf = document("content.opf")
        if (missingMetadata) {
            assertTrue(opf.selectNodes("//*[local-name()='creator']").isEmpty())
            assertTrue(opf.selectSingleNode("//*[local-name()='title']").text.isNotBlank())
        }
        for (field in listOf("description", "publisher")) {
            assertTrue(opf.selectNodes("//*[local-name()='$field']").isEmpty())
        }
        val manifest = opf.rootElement.element("manifest").elements("item")
        assertEquals(manifest.size, manifest.map { it.attributeValue("id") }.distinct().size)
        manifest.forEach {
            assertTrue(it.attributeValue("id").matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*")))
            assertNotNull(zip.getEntry("EPUB/" + it.attributeValue("href")))
        }
        val spine = opf.rootElement.element("spine").elements("itemref")
        assertEquals(chapterCount, spine.size)
        val hrefs = spine.map { item -> manifest.single { it.attributeValue("id") == item.attributeValue("idref") }.attributeValue("href") }
        assertEquals(hrefs, document("nav.xhtml").selectNodes("//*[local-name()='a']").map { (it as Element).attributeValue("href") })
        assertEquals(hrefs, document("toc.ncx").selectNodes("//*[local-name()='navPoint'][not(*[local-name()='navPoint'])]/*[local-name()='content']")
            .map { (it as Element).attributeValue("src") })
        hrefs.forEachIndexed { index, href ->
            val doc = document(href)
            assertEquals(if (missingMetadata) "Untitled chapter" else "Chapter & \uD83D\uDE00", doc.rootElement.element("head").elementText("title"))
            assertTrue(doc.selectNodes("//*").all { (it as Element).namespaceURI == "http://www.w3.org/1999/xhtml" })
            assertEquals(if (index % 2 == 0) "  A & <B> \uD83D\uDE00\uD840\uDC00Z\t&#0;  Extra text" else "", doc.rootElement.element("body").stringValue)
            assertEquals(if (index % 2 == 0) 3 else 0, doc.selectNodes("//*[local-name()='br']").size)
            val images = doc.selectNodes("//*[local-name()='img']").map { (it as Element).attributeValue("src") }
            assertEquals(if (index % 2 == 0) 3 else 0, images.size)
            if (images.isNotEmpty()) assertEquals(2, images.distinct().size)
            images.forEach { src ->
                assertEquals("image/jpeg", manifest.single { it.attributeValue("href") == src }.attributeValue("media-type"))
                val bytes = zip.getInputStream(zip.getEntry("EPUB/$src")).use { it.readBytes() }
                assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), bytes.take(3).toByteArray())
            }
        }
    }
}
