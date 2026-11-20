package indi.renakoni.nextvol.defaultplugin.wenku8

import android.app.Application
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.defaultplugin.wenku8.book.Wenku8WebsiteDataSource
import indi.renakoni.nextvol.data.web.SourceRequestOwner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8DirectoryCacheTest {
    private val api = mockk<Wenku8Api>()
    private val source = Wenku8WebsiteDataSource("https://wenku8.test", api)
    private val directoryUrl = "https://wenku8.test/novel/0/123/index.htm"
    private var suffix = ""

    private fun fixture() {
        every { api.cache } returns Cache()
        coEvery { api.getWithWenku8Cookie(directoryUrl) } answers {
            Ok(Jsoup.parse("<table>" + (1..4).joinToString("") {
                "<tr><td class='vcss' vid='v$it'>Volume $it</td></tr>" +
                    "<tr><td><a href='$it.htm'>Chapter $it$suffix</a></td></tr>"
            } + "</table>"))
        }
        for (id in 1..4) coEvery { api.getWithWenku8Cookie("https://wenku8.test/novel/0/123/$id.htm") } answers {
            Ok(Jsoup.parse("<div id='title'>Page title</div><div id='content'>Body</div>"))
        }
    }

    @Test fun aFreshDirectoryIsReusedByConcurrentChaptersAcrossAllVolumes() = runBlocking {
        fixture()
        assertTrue(source.getBookVolumes("123").isOk)
        val chapters = (1..4).map { id -> async(Dispatchers.Default) {
            source.getChapterContent(id.toString(), "123").get()!!
        } }.awaitAll()
        assertEquals((1..4).map { "Chapter $it" }, chapters.map { it.title })
        coVerify(exactly = 1) { api.getWithWenku8Cookie(directoryUrl) }
        coVerify(exactly = 5) { api.getWithWenku8Cookie(any()) }
        suffix = " updated"
        assertTrue(source.getBookVolumes("123").isOk)
        assertEquals("Chapter 4 updated", source.getChapterContent("4", "123").get()!!.title)
        coVerify(exactly = 2) { api.getWithWenku8Cookie(directoryUrl) }
    }

    @Test fun cachedDirectoryTitlesDoNotCrossRuntimeOwners() = runBlocking {
        fixture()
        val first = SourceRequestOwner(Identifier("fixture", "first"))
        val second = SourceRequestOwner(Identifier("fixture", "second"))
        withContext(first) { source.getBookVolumes("123") }
        suffix = " from another route"
        withContext(second) { source.getBookVolumes("123") }
        assertEquals("Chapter 4", withContext(first) { source.getChapterContent("4", "123").get()!!.title })
        assertEquals("Chapter 4 from another route", withContext(second) { source.getChapterContent("4", "123").get()!!.title })
        coVerify(exactly = 2) { api.getWithWenku8Cookie(directoryUrl) }
    }
}
