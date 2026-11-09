package indi.renakoni.nextvol.data.download

import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.content.ContentComponentRegistry
import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import io.mockk.*
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookDownloadImageCommitTest {
    @Test fun preparingImagesDoesNotBlockOtherBooksAndClearedOrReplacedVersionsCannotPublish() = runBlocking {
        for (clear in listOf(true, false)) {
            val root = java.nio.file.Files.createTempDirectory("image-commit").toFile()
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
            val db = Room.inMemoryDatabaseBuilder(context, NextVolDatabase::class.java).allowMainThreadQueries().build()
            val prepared = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            var pause = false
            mockkConstructor(BookDownloadFiles::class)
            every { anyConstructed<BookDownloadFiles>().prepareImage(any(), any()) } answers {
                callOriginal().also {
                    if (pause) {
                        prepared.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            val store = BookDownloadStore(context, db, ContentJsonDecoder(ContentComponentRegistry()))
            val book = SourceBookId(Identifier("fixture", root.name), "writing")
            val other = book.copy(remoteId = "reading")
            val image = SourceImage(book, "https://fixture.invalid/image")
            try {
                val readAttempt = store.begin(other, store.generation(), "reader")
                store.saveImage(readAttempt, image.uri, false, byteArrayOf(1))
                val attempt = store.begin(book, store.generation(), "writer")
                val chapter = SourceChapterId(book, "chapter").bind(ChapterContent("chapter", "Chapter",
                    ContentBuilder().image(Uri.parse(image.uri)).build()))
                val old = store.saveCandidate(attempt, chapter, "signature")
                pause = true
                val writing = async(Dispatchers.IO) {
                    runCatching { store.saveImage(attempt, image.uri, false, byteArrayOf(2), old.resourceVersion) }
                }
                withTimeout(3000) { prepared.await() }
                try {
                    assertNotNull(withTimeout(1000) { store.image(image.copy(book = other)) })
                    if (clear) withTimeout(1000) { store.clearDownloads() }
                    else store.saveCandidate(attempt, chapter, "replacement")
                } finally { release.countDown() }
                assertTrue(withTimeout(3000) { writing.await() }.exceptionOrNull() is CancellationException)
                assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
                if (clear) assertNull(store.image(image)) else assertNull(store.checkpointImage(attempt, old, image.uri))
            } finally {
                release.countDown(); unmockkConstructor(BookDownloadFiles::class); db.close(); root.deleteRecursively()
            }
        }
    }

    @Test fun overlappingPreparationsPreserveTheImageUntilPublishedAndRenameFailuresAreReported() {
        val root = java.nio.file.Files.createTempDirectory("image-files").toFile()
        val files = BookDownloadFiles(RuntimeEnvironment.getApplication())
        val target = File(root, "image").apply { writeBytes(byteArrayOf(1)) }
        try {
            val first = files.prepareImage(target, byteArrayOf(2))
            val second = files.prepareImage(target, byteArrayOf(3))
            assertArrayEquals(byteArrayOf(1), target.readBytes())
            files.publishImage(first, target)
            val version = target.lastModified()
            files.publishImage(second, target)
            assertTrue(target.lastModified() > version)
            assertArrayEquals(byteArrayOf(3), target.readBytes())
            val blocked = File(root, "blocked").apply { mkdirs(); resolve("keep").writeText("existing") }
            val pending = files.prepareImage(target, byteArrayOf(4))
            assertThrows(java.io.IOException::class.java) { files.publishImage(pending, blocked) }
            assertEquals("existing", blocked.resolve("keep").readText())
        } finally { root.deleteRecursively() }
    }
}
