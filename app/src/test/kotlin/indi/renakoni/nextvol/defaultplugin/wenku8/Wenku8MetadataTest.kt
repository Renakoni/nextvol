package indi.renakoni.nextvol.defaultplugin.wenku8

import android.app.Application
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import indi.renakoni.nextvol.data.book.UNKNOWN_BOOK_UPDATE_TIME
import indi.renakoni.nextvol.defaultplugin.wenku8.book.Wenku8WebsiteDataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class Wenku8MetadataTest {
    private suspend fun information(cells: String): io.nightfish.lightnovelreader.api.book.BookInformation {
        val api = mockk<Wenku8Api>()
        val page = Jsoup.parse("""
            <div id='content'><div><table>
              <tr><td><table><tr><td><span><b>Book</b></span></td></tr></table></td></tr>
              <tr><td>文库分类：Publisher</td><td>小说作者：Author</td><td>连载中</td>$cells</tr>
            </table><table><tr><td><img src='https://example.test/cover.jpg'></td><td>
              <span><b>作品Tags：tag</b></span><span></span><span></span><span></span><span></span><span>Description</span>
            </td></tr></table></div></div>
        """)
        coEvery { api.getWithWenku8Cookie("https://example.test/book/1.htm") } returns Ok(page)
        val information = Wenku8WebsiteDataSource("https://example.test", api).getBookInformation("1")
            .getOrElse { error(it.toString()) }
        coVerify(exactly = 1) { api.getWithWenku8Cookie(any()) }
        return information
    }

    @Test fun sourceDateAndFormattedWordCountArePreserved(): Unit = runBlocking {
        val book = information("<td>最后更新：2026-09-14</td><td>全文长度：12,345字</td>")
        assertEquals(12345, book.wordCount.count)
        assertEquals(LocalDateTime.of(2026, 9, 14, 0, 0), book.lastUpdated)
    }

    @Test fun missingOrInvalidOptionalMetadataDoesNotDiscardTheBook(): Unit = runBlocking {
        for (cells in listOf("", "<td></td><td></td>",
            "<td>最后更新：2026-02-30</td><td>全文长度：未知</td>")) {
            val book = information(cells)
            assertEquals("Book", book.title)
            assertEquals("Author", book.author)
            assertEquals(0, book.wordCount.count)
            assertEquals(UNKNOWN_BOOK_UPDATE_TIME, book.lastUpdated)
        }
    }
}
