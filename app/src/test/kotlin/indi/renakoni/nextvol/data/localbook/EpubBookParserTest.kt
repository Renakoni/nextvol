package indi.renakoni.nextvol.data.localbook

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookParserTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun epub(
        manifest: String = "<item id='one' href='one.xhtml' media-type='application/xhtml+xml'/>",
        spine: String = "<itemref idref='one'/>",
        resources: Map<String, String> = mapOf("OPS/one.xhtml" to "<html><body><h1>Chapter one</h1><p>Complete text.</p></body></html>"),
        extra: Map<String, ByteArray> = emptyMap(),
    ): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            val entries = mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>",
                "OPS/book.opf" to "<package><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Imported novel</dc:title><dc:creator>Author</dc:creator><dc:description>Summary</dc:description></metadata><manifest>$manifest</manifest><spine>$spine</spine></package>",
            ) + resources
            for ((name, value) in entries.mapValues { it.value.toByteArray() } + extra) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value)
                zip.closeEntry()
            }
        }
    }

    private fun parse(file: File) = EpubBookParser.parse(file, temporary.newFolder(), "Filename")
    private fun assertReason(reason: LocalBookImportReason, block: () -> Unit) {
        val failure = assertThrows(Exception::class.java, block)
        assertEquals(reason, LocalBookImportFailure.from(failure).reason)
    }
    private fun body(book: ParsedLocalBook) = book.chapters.flatMap { it.blocks }
        .filterIsInstance<LocalBookBlock.Text>().joinToString("") { it.value }

    @Test fun spineControlsOrderAndMetadataIsReadFromThePackage() {
        val file = epub(
            manifest = "<item id='one' href='one.xhtml' media-type='application/xhtml+xml'/><item id='two' href='two.xhtml' media-type='application/xhtml+xml'/>",
            spine = "<itemref idref='two'/><itemref idref='one'/>",
            resources = linkedMapOf(
                "OPS/one.xhtml" to "<html><body><h1>First in ZIP</h1><p>One.</p></body></html>",
                "OPS/two.xhtml" to "<html><body><h1>First in spine</h1><p>Two.</p></body></html>",
            ),
        )
        val book = parse(file)
        assertEquals("Imported novel", book.title)
        assertEquals("Author", book.author)
        assertEquals("Summary", book.description)
        assertEquals(listOf("First in spine", "First in ZIP"), book.chapters.map { it.title })
        assertEquals("First in spineTwo.First in ZIPOne.", body(book))
    }

    @Test fun epub3NavigationAnchorsSplitASharedSpineDocumentAndKeepThePreface() {
        val book = parse(epub(
            manifest = "<item id='one' href='one.xhtml' media-type='application/xhtml+xml'/><item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>",
            resources = mapOf(
                "OPS/one.xhtml" to "<html><body><p>Preface</p><h1 id='a'>A</h1><p>Text A</p><h1 id='b'>B</h1><p>Text B</p></body></html>",
                "OPS/nav.xhtml" to "<html><body><nav epub:type='toc'><ol><li><a href='one.xhtml#a'>Chapter A</a></li><li><a href='one.xhtml#b'>Chapter B</a></li></ol></nav></body></html>",
            ),
        ))
        assertEquals(3, book.chapters.size)
        assertEquals(listOf("Chapter A", "Chapter B"), book.chapters.drop(1).map { it.title })
        assertEquals("PrefaceAText ABText B", body(book))
    }

    @Test fun epub2NcxLabelsAreUsedAndInlineFormattingDoesNotSplitWords() {
        val book = parse(epub(
            manifest = "<item id='one' href='one.xhtml' media-type='application/xhtml+xml'/><item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>",
            resources = mapOf(
                "OPS/one.xhtml" to "<html><body><p>some<em>thing</em> &amp; more</p><script>discard script</script></body></html>",
                "OPS/toc.ncx" to "<ncx><navMap><navPoint><navLabel><text>NCX title</text></navLabel><content src='one.xhtml'/></navPoint></navMap></ncx>",
            ),
        ))
        assertEquals("NCX title", book.chapters.single().title)
        assertEquals("something & more", body(book))
    }

    @Test fun rasterImagesAndSvgImageWrappersStayInsideTheImportDirectory() {
        val pixels = byteArrayOf(1, 2, 3, 4)
        val assets = temporary.newFolder()
        val file = epub(
            manifest = "<item id='one' href='one.xhtml' media-type='application/xhtml+xml'/><item id='cover' href='img/a%20b.png' media-type='image/png' properties='cover-image'/>",
            resources = mapOf("OPS/one.xhtml" to "<html><body><svg><image xlink:href='img/a%20b.png'/></svg><p>Text</p></body></html>"),
            extra = mapOf("OPS/img/a b.png" to pixels),
        )
        val book = EpubBookParser.parse(file, assets, "Fallback")
        val image = book.chapters.single().blocks.filterIsInstance<LocalBookBlock.Image>().single()
        assertEquals(book.coverPath, image.path)
        assertTrue(image.path.startsWith("assets/"))
        assertArrayEquals(pixels, File(assets, image.path.substringAfter('/')).readBytes())
    }

    @Test fun missingAndUnsupportedSpineResourcesFailWithoutProducingABook() {
        assertReason(LocalBookImportReason.CorruptEpub) { parse(epub(resources = emptyMap())) }
        assertThrows(IllegalArgumentException::class.java) { parse(epub(spine = "<itemref idref='missing'/>")) }
        assertReason(LocalBookImportReason.NoContent) { parse(epub(resources = mapOf("OPS/one.xhtml" to "<html><body></body></html>"))) }
        assertReason(LocalBookImportReason.UnsupportedEpub) {
            parse(epub(resources = mapOf("OPS/one.xhtml" to "<html><body><svg><path d='M0 0'/></svg></body></html>")))
        }
    }

    @Test fun encryptedTextIsRejectedButObfuscatedUnusedFontsAreAllowed() {
        fun encryption(path: String) = mapOf("META-INF/encryption.xml" to "<encryption><EncryptedData><CipherData><CipherReference URI='$path'/></CipherData></EncryptedData></encryption>".toByteArray())
        assertReason(LocalBookImportReason.EncryptedEpub) { parse(epub(extra = encryption("OPS/one.xhtml"))) }
        assertEquals(1, parse(epub(extra = encryption("OPS/font.otf"))).chapters.size)
    }

    @Test fun traversalExternalAndEncodedAbsoluteResourcePathsAreRejected() {
        for (path in listOf("../../outside.png", "https://example.invalid/image.png", "%2Foutside.png", "..%2F..%2Foutside.png", "..\\outside.png", "bad%zz.png", "one.xhtml#bad%zz")) {
            assertReason(LocalBookImportReason.CorruptEpub) {
                parse(epub(resources = mapOf("OPS/one.xhtml" to "<html><body><img src='$path'/></body></html>")))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(epub(extra = mapOf("../escape" to byteArrayOf(1)))) }
        assertThrows(IllegalArgumentException::class.java) { parse(epub(extra = mapOf("OPS/./one.xhtml" to byteArrayOf(1)))) }
    }

    @Test fun compressedTextBeyondTheFormerResourceLimitIsComplete() {
        val text = "A".repeat(16 * 1024 * 1024 + 1)
        val book = parse(epub(resources = mapOf("OPS/one.xhtml" to "<html><body><p>$text</p></body></html>")))
        assertEquals(text, body(book))
    }

    @Test fun damagedArchivesAreRejected() {
        assertReason(LocalBookImportReason.CorruptEpub) {
            parse(temporary.newFile().apply { writeText("not a ZIP") })
        }
    }

    @Test fun largeTextAndParagraphCountsAreSplitWithoutCuttingUnicodeOrDroppingText() {
        val longText = "a".repeat(TxtBookParser.MAX_CHAPTER_CHARACTERS - 1) + "😀" + "b"
        val book = parse(epub(resources = mapOf("OPS/one.xhtml" to "<html><body><p>$longText</p>${"<p>x</p>".repeat(2001)}</body></html>")))
        assertEquals(longText + "x".repeat(2001), body(book))
        assertTrue(book.chapters.all { it.blocks.size <= TxtBookParser.MAX_CHAPTER_BLOCKS })
        assertTrue(book.chapters.all { chapter -> chapter.blocks.filterIsInstance<LocalBookBlock.Text>().sumOf { it.value.length } <= TxtBookParser.MAX_CHAPTER_CHARACTERS })
        assertTrue(book.chapters.flatMap { it.blocks }.filterIsInstance<LocalBookBlock.Text>().all { it.value == it.value.toByteArray().toString(Charsets.UTF_8) })
    }
}
