package indi.renakoni.nextvol.data.localbook

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageInterceptor
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LocalBookImportInstrumentedTest {
    @Test fun epubTextAndImagesSurviveCacheClearOriginalRemovalAndDatabaseReopen() = runBlocking {
        withTimeout(30_000) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(base.filesDir, "import-fixture-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(base) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            }
            fun openDatabase() = Room.databaseBuilder(context, NextVolDatabase::class.java, File(root, "library.db").path).build()
            var database = openDatabase()
            var books = LocalBookStore(context, database)
            var loader: ImageLoader? = null
            try {
                val png = ByteArrayOutputStream().also {
                    Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                val original = File(root, "illustrated.epub")
                ZipOutputStream(original.outputStream()).use { zip ->
                    fun entry(name: String, bytes: ByteArray) {
                        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                    }
                    entry("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>".toByteArray())
                    entry("OPS/book.opf", """<package><metadata><title>Illustrated book</title><creator>Local author</creator></metadata>
                        <manifest><item id="body" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                        <item id="cover" href="images/cover.png" media-type="image/png" properties="cover-image"/></manifest>
                        <spine><itemref idref="body"/></spine></package>""".toByteArray())
                    entry("OPS/chapter.xhtml", "<html><body><h1>First chapter</h1><p>Persistent illustrated text</p><img src=\"images/cover.png\"/></body></html>".toByteArray())
                    entry("OPS/images/cover.png", png)
                }
                val draft = books.stage(original.toUri())
                val parsed = books.preview(draft)
                val (book, shelf) = books.publish(draft, parsed, parsed.title, null)
                assertEquals(listOf(book.storageKey), database.bookshelfDao().getBookshelf(shelf)!!.allBookIds)
                assertTrue(original.delete())
                val decoder = ContentJsonDecoder(ContentComponentRegistry())
                BookDownloadStore(context, database, decoder).clearReadingCache()
                database.bookVolumesDao().clear()
                database.close()
                database = openDatabase()
                books = LocalBookStore(context, database)
                val downloads = BookDownloadStore(context, database, decoder)
                loader = ImageLoader.Builder(context).components {
                    add(SourceImageInterceptor(WebSourceRegistry(), context, downloads))
                }.build()
                val info = books.readInformation(book).get()!!
                val content = books.readChapter(SourceChapterId(book, "0")).get()!!
                assertTrue(content.content.toString().contains("Persistent illustrated text"))
                assertEquals(1, books.readVolumes(book).get()!!.volumes.single().chapters.size)
                assertArrayEquals(png, File(info.coverUri.path!!).readBytes())
                val image = loader.execute(ImageRequest.Builder(context).data(SourceImage(book, info.coverUri.toString())).build())
                assertTrue(image.toString(), image is SuccessResult)
                assertEquals(3, (image as SuccessResult).image.width)
                assertEquals(2, image.image.height)
                books.delete(book)
                assertFalse(File(info.coverUri.path!!).exists())
                assertTrue(database.bookshelfDao().getBookshelf(shelf)!!.allBookIds.isEmpty())
            } finally {
                loader?.shutdown()
                database.close()
                root.deleteRecursively()
            }
        }
    }

    @Test fun androidCharsetsKeepChineseHeadingsAndBodyText() {
        val samples = listOf(
            "GB18030" to "第一章开端\n简体中文𠀀内容\n第二章旅途\n完整正文",
            "Big5" to "第一章開端\n繁體中文內容\n第二章旅途\n完整正文",
            "UTF-16LE" to "第一章开端\n中文与😀\n第二章旅途\n完整正文",
            "UTF-16BE" to "第一章开端\n中文与😀\n第二章旅途\n完整正文",
        )
        for ((encoding, text) in samples) {
            val chapters = mutableListOf<LocalBookChapter>()
            TxtBookParser.parse({ text.byteInputStream(Charset.forName(encoding)) }, "Book", encoding, onChapter = { chapters += it })
            assertEquals(encoding, 2, chapters.size)
            assertEquals(text.lineSequence().elementAt(1), (chapters.first().blocks.single() as LocalBookBlock.Text).value)
        }
    }
}
