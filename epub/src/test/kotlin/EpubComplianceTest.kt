import io.nightfish.potatoepub.builder.EpubBuilder
import io.nightfish.potatoepub.builder.SimpleContentBuilder
import org.dom4j.DocumentHelper
import java.io.File
import java.time.LocalDateTime
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubComplianceTest {
    @Test
    fun missingMetadataUsesFallbackTitlesWithoutInventingAnAuthor() {
        for ((index, missing) in listOf(null, " \t ").withIndex()) {
            val epub = EpubBuilder().apply {
                title = missing
                id = missing
                creator = missing
                description = missing
                publisher = missing
                modifier = LocalDateTime.of(2026, 9, 10, 0, 0)
                chapter {
                    if (missing != null) title(missing)
                    content { title(""); text("Body") }
                }
                chapter {
                    title("Catalog title")
                    content { title("Document title"); text("Body") }
                }
            }.build()
            assertEquals("Untitled book", epub.opfPackage.metadata.title)
            assertEquals("Untitled book", epub.nav.title)
            assertTrue(epub.opfPackage.metadata.id.startsWith("urn:uuid:"))
            java.util.UUID.fromString(epub.opfPackage.metadata.id.removePrefix("urn:uuid:"))
            assertEquals(null, epub.opfPackage.metadata.creator)
            assertEquals(listOf("Untitled chapter", "Document title"), epub.documents.values.map {
                it.rootElement.element("head").elementText("title")
            })
            assertEquals(listOf("Untitled chapter", "Catalog title"), epub.nav.ol.items.map { it.title })
            epub.save(File("build/epub-compliance/missing-metadata-$index.epub"))
        }
    }

    @Test
    fun imagesAndForeignMarkupHaveMatchingManifestProperties() {
        val directory = File("build/epub-compliance").apply { mkdirs() }
        val png = File(directory, "illustration.png").also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
        val jpeg = File(javaClass.getResource("/cover.jpg")!!.toURI())
        val content = SimpleContentBuilder().apply {
            title("Illustrations")
            image(jpeg, "illustration", "image/illustration.jpg")
            addContent(DocumentHelper.createElement("div").apply {
                addElement("img").addAttribute("src", "image/illustration.png").addAttribute("alt", "Illustration")
                addElement("svg", "http://www.w3.org/2000/svg").apply {
                    addAttribute("viewBox", "0 0 10 10")
                    addElement("circle").addAttribute("cx", "5").addAttribute("cy", "5").addAttribute("r", "4")
                }
                addElement("math", "http://www.w3.org/1998/Math/MathML").addElement("mi").addText("x")
            })
        }
        val epub = EpubBuilder().apply {
            title = "Images"
            modifier = LocalDateTime.of(2026, 9, 10, 0, 0)
            cover(jpeg)
            imgRes("image/illustration.jpg", "illustration", jpeg)
            res("png", "image/illustration.png", "image/png", png)
            chapter { title("Illustrations"); content(content.build()) }
        }.build()
        epub.save(File(directory, "images-and-foreign.epub"))
        val manifest = epub.opfPackage.manifest.items
        assertEquals("image/png", manifest.single { it.id == "png" }.mediaType)
        assertEquals("svg mathml", manifest.single { it.href.endsWith(".xhtml") && it.id != "nav" }.properties)
    }

    @Test
    fun singleAndMultipleChaptersHaveTitlesAndCompleteResourceReferences() {
        for (chapterCount in listOf(1, 3)) {
            val file = File("build/epub-compliance/chapters-$chapterCount.epub")
            EpubBuilder().apply {
                title = "Book & \uD83D\uDE00"
                modifier = LocalDateTime.of(2026, 9, 10, 0, 0)
                creator = ""
                description = ""
                publisher = " \t "
                repeat(chapterCount) { index ->
                    chapter {
                        title("Same chapter")
                        content {
                            if (index == 0) text("Ordinary text & < > \uD83D\uDE00\uD840\uDC00\u0001")
                        }
                    }
                }
            }.build().save(file)
            ZipFile(file).use { zip ->
                val first = zip.entries().nextElement()
                assertEquals("mimetype", first.name)
                assertEquals(ZipEntry.STORED, first.method)
                val opf = DocumentHelper.parseText(zip.getInputStream(zip.getEntry("EPUB/content.opf")).reader().readText())
                for (field in listOf("creator", "description", "publisher")) {
                    assertTrue(opf.selectNodes("//*[local-name()='$field']").isEmpty())
                }
                val manifest = opf.rootElement.element("manifest").elements("item")
                assertEquals(manifest.size, manifest.map { it.attributeValue("id") }.distinct().size)
                manifest.forEach { assertNotNull(zip.getEntry("EPUB/" + it.attributeValue("href"))) }
                val spine = opf.rootElement.element("spine").elements("itemref")
                assertEquals(chapterCount, spine.size)
                spine.forEachIndexed { index, item ->
                    val href = manifest.single { it.attributeValue("id") == item.attributeValue("idref") }.attributeValue("href")
                    val doc = DocumentHelper.parseText(zip.getInputStream(zip.getEntry("EPUB/$href")).reader().readText())
                    assertEquals("Same chapter", doc.rootElement.element("head").elementText("title"))
                    assertTrue(doc.selectNodes("//*").all { (it as org.dom4j.Element).namespaceURI == "http://www.w3.org/1999/xhtml" })
                    assertEquals(if (index == 0) "Ordinary text & < > \uD83D\uDE00\uD840\uDC00" else "", doc.rootElement.element("body").stringValue)
                }
            }
        }
    }
}
