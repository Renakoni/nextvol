package indi.renakoni.nextvol.data.download

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageInterceptor
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Android file/Room persistence and image decoding, with no network or user library changes. */
@RunWith(AndroidJUnit4::class)
@OptIn(coil3.annotation.DelicateCoilApi::class)
class DownloadPersistenceInstrumentedTest {
    @Test fun canonicalSeriesRetainsSingleWorkProgressAndImagesAfterReopen() = runBlocking {
        withTimeout(30_000) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(base.filesDir, "canonical-fixture-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "files").apply { mkdirs() } }
            fun open() = Room.databaseBuilder(context, NextVolDatabase::class.java, File(root, "library.db").path).build()
            var db = open()
            val decoder = ContentJsonDecoder(ContentComponentRegistry())
            var store = BookDownloadStore(context, db, decoder)
            val single = SourceBookId(Identifier("fixture", "canonical"), "novel/1")
            val series = SourceBookId(single.sourceId, "series/10")
            val singleChapter = SourceChapterId(single, single.remoteId)
            val targetChapter = SourceChapterId(series, single.remoteId)
            val raw = BookVolumes(single.remoteId, listOf(Volume("default", "", listOf(ChapterInformation(single.remoteId, "First")))))
            val canonicalVolumes = series.bind(BookVolumes(series.remoteId, listOf(Volume("default", "Series",
                listOf(ChapterInformation(single.remoteId, "First"), ChapterInformation("novel/2", "Second"))))))
            val image = SourceImage(single, "https://fixture.invalid/illustration.png")
            val png = ByteArrayOutputStream().also {
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            val body = singleChapter.bind(ChapterContent(single.remoteId, "First", ContentBuilder().simpleText("Saved").image(Uri.parse(image.uri)).build()))
            var loader: ImageLoader? = null
            try {
                val aliases = BookAliasStore(db)
                val local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), aliases)
                local.updateBookVolumes(single.bind(raw))
                local.updateUserReadingData(single.storageKey) { it.copyWithUpdatedChapterReadingProgress(singleChapter.storageKey, 0.7f)
                    .copy(lastReadChapterId = singleChapter.storageKey, totalReadTime = 30) }
                val attempt = store.begin(single, 0, "single")
                store.target(attempt, single.bind(raw), "fixture", "")
                store.saveImage(attempt, image.uri, false, png)
                store.saveChapter(attempt, body, "old-signature", listOf(image.uri))
                val information = series.bind(BookInformation(series.remoteId, "Series", author = "Author", description = "",
                    publishingHouse = "", wordCount = WordCount(1), lastUpdated = java.time.LocalDateTime.MIN, isComplete = false))
                store.mergeIdentity(single, series, canonicalVolumes) { aliases.merge(single, series, information, canonicalVolumes) }
                try { store.finish(attempt, true); fail("Old ownership must be retired") } catch (_: CancellationException) { }
                store.clearReadingCache()
                db.close(); db = open(); store = BookDownloadStore(context, db, decoder)
                assertEquals(series, BookAliasStore(db).resolve(single))
                val reading = db.userReadingDataDao().getEntity(series.storageKey)!!
                assertEquals(targetChapter.storageKey, reading.lastReadChapterId)
                assertEquals(0.7f, reading.maxChapterReadingProgressMap[targetChapter.storageKey]!!, 0f)
                assertEquals(body.content, db.chapterContentDao().get(targetChapter.storageKey)!!.content)
                assertNotEquals(BookDownloadPhase.Complete, store.state(series, canonicalVolumes, false).phase)
                assertArrayEquals(png, store.image(image)!!.readBytes())
                loader = ImageLoader.Builder(context).components { add(SourceImageInterceptor(WebSourceRegistry(), context, store)) }.build()
                val decoded = loader.execute(ImageRequest.Builder(context).data(image.copy(book = series)).build())
                assertTrue(decoded.toString(), decoded is SuccessResult)
                assertEquals(2, (decoded as SuccessResult).image.width)
            } finally { loader?.shutdown(); db.close(); root.deleteRecursively() }
        }
    }

    @Test fun downloadedTextAndImagesSurviveCacheClearAndReopen() = runBlocking {
        withTimeout(30_000) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(base.filesDir, "download-fixture-${UUID.randomUUID()}").apply { mkdirs() }
            val context = object : ContextWrapper(base) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            }
            fun openDatabase() = Room.databaseBuilder(context, NextVolDatabase::class.java,
                File(root, "library.db").path).build()
            var db = openDatabase()
            val decoder = ContentJsonDecoder(ContentComponentRegistry())
            var store = BookDownloadStore(context, db, decoder)
            val cache = DiskCache.Builder().directory(File(root, "coil").path.toPath()).maxSizeBytes(1024 * 1024).build()
            fun openImages() = ImageLoader.Builder(context).diskCache(cache).components {
                add(SourceImageInterceptor(WebSourceRegistry(), context, store))
            }.build().also { SingletonImageLoader.setUnsafe(it) }
            var loader = openImages()
            try {
                val book = SourceBookId(Identifier("fixture", "downloads"), "same")
                val chapterId = SourceChapterId(book, "1")
                val volumes = book.bind(BookVolumes("same", listOf(Volume("1", "Volume", listOf(ChapterInformation("1", "Chapter"))))))
                val image = SourceImage(book, "https://fixture.invalid/image.png")
                val body = chapterId.bind(ChapterContent("1", "Chapter",
                    ContentBuilder().simpleText("Offline body").image(Uri.parse(image.uri)).build()))
                val local = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(db))
                local.updateBookVolumes(volumes)
                val attempt = store.begin(book, 0, "first")
                store.target(attempt, volumes, "1", "")
                val png = ByteArrayOutputStream().also {
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                store.saveImage(attempt, image.uri, false, png)
                store.saveChapter(attempt, body, downloadChapterSignature(volumes.volumes.single().chapters, 0, "1"), listOf(image.uri))
                store.finish(attempt, true)
                store.clearReadingCache()
                loader.shutdown(); db.close()
                db = openDatabase()
                store = BookDownloadStore(context, db, decoder)
                loader = openImages()
                assertEquals(body.content, db.chapterContentDao().get(chapterId.storageKey)!!.content)
                assertEquals(BookDownloadState(BookDownloadPhase.Complete, 1, 1), store.state(book, volumes, false))
                assertArrayEquals(png, store.image(image)!!.readBytes())
                val decoded = loader.execute(ImageRequest.Builder(context).data(image).build())
                assertTrue(decoded.toString(), decoded is SuccessResult)
                assertEquals(2, (decoded as SuccessResult).image.width)
                val pending = store.begin(book, 0, "pending")
                store.clearDownloads()
                assertNull(db.chapterContentDao().get(chapterId.storageKey))
                assertNull(store.image(image))
                try { store.finish(pending, true); fail("Cleared download must not be resurrected") }
                catch (_: CancellationException) { }
            } finally {
                loader.shutdown(); cache.shutdown(); SingletonImageLoader.reset(); db.close()
                root.deleteRecursively()
            }
        }
    }
}
