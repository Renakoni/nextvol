package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import androidx.room.Room
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.text.TextProcessingRepository
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.SourceRuntime
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
import indi.renakoni.nextvol.defaultplugin.wenku8.book.Wenku8WebsiteDataSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.util.Cache
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Real Wenku8 parser, repository, identity mapping and file-backed Room; HTTP documents controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8ChapterPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun offlineFourthDoesNotShiftBodiesAfterDatabaseReopen() = runBlocking {
        var offline = false
        val requested = mutableListOf<String>()
        val api = mockk<Wenku8Api>()
        every { api.cache } returns Cache()
        coEvery { api.getWithWenku8Cookie(any()) } answers {
            val url = firstArg<String>()
            requested += url
            if (offline) Err(IOException("fixture offline")) else {
                val id = url.substringAfterLast('/').substringBefore('.')
                if (id == "index") {
                    Ok(Jsoup.parse("<table><tr><td class='vcss' vid='1'>Volume</td></tr>" +
                        (3..5).joinToString("") { "<tr><td><a href='$it.htm'>Chapter $it</a></td></tr>" } +
                        "</table>"))
                } else {
                    Ok(Jsoup.parse("<div id='title'>Chapter $id</div><div id='content'>BODY_$id</div>"))
                }
            }
        }
        val parser = Wenku8WebsiteDataSource("https://fixture.invalid", api)
        assertTrue(parser.getBookVolumes("1234").isOk)
        val runtime = mockk<SourceRuntime> {
            every { metadata } returns mockk { every { revision } returns "1" }
            coEvery { execute<Any?>(any()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
            coEvery { canonicalBookId(any()) } coAnswers { firstArg() }
            coEvery { getChapterContent(any(), any(), any(), any()) } coAnswers {
                parser.getChapterContent(firstArg(), secondArg())
            }
        }
        val registry = mockk<WebSourceRegistry> {
            every { sources } returns MutableStateFlow(emptyList())
            coEvery { awaitSourceRegistration(any()) } returns Unit
            coEvery { resolve(any()) } returns SourceResolution.Ready(runtime)
        }
        val downloads = mockk<BookDownloadStore> {
            every { isReusableReadingContent(any()) } returns true
        }
        val text = mockk<TextProcessingRepository> {
            every { processChapterContent(any(), any()) } answers { secondArg<() -> ChapterContent>()() }
        }
        val path = temporary.root.resolve("chapters.db").absolutePath
        fun database() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java, path)
            .allowMainThreadQueries().build()
        fun local(db: NextVolDatabase) = LocalBookDataSource(db.bookInformationDao(), db.bookVolumesDao(), db.chapterContentDao(), db.userReadingDataDao(), indi.renakoni.nextvol.data.book.BookAliasStore(db))
        fun key(id: String) = BookIdentity.chapter(id, BookIdentity.book("1234")).storageKey

        var db = database()
        try {
            var storage = local(db)
            var repository = ChapterRepository(registry, storage, text, mockk(), downloads, indi.renakoni.nextvol.data.book.BookDirectoryChecks(registry, storage, downloads, org.robolectric.RuntimeEnvironment.getApplication()))
            val third = repository.getChapterContentFlow("3", "1234").last().get()!!
            assertTrue(third.content.toString().contains("BODY_3"))
            offline = true
            assertTrue(repository.getChapterContentFlow("4", "1234").last().isErr)
            assertNull(storage.getChapterContent(key("4")))
            db.close()
            db = database()
            storage = local(db)
            repository = ChapterRepository(registry, storage, text, mockk(), downloads, indi.renakoni.nextvol.data.book.BookDirectoryChecks(registry, storage, downloads, org.robolectric.RuntimeEnvironment.getApplication()))
            offline = false
            for (id in listOf("4", "5")) {
                val chapter = repository.getChapterContentFlow(id, "1234").last().get()!!
                assertEquals(key(id), chapter.id)
                assertTrue(chapter.content.toString().contains("BODY_$id"))
            }
            for (id in listOf("3", "4", "5")) {
                assertTrue(storage.getChapterContent(key(id))!!.content.toString().contains("BODY_$id"))
            }
            assertEquals(listOf("index", "3", "4", "4", "5"), requested.map { it.substringAfterLast('/').substringBefore('.') })
        } finally { db.close() }
    }
}
