package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.getOrElse
import hnovel.content.RuleSourceFixture
import indi.renakoni.nextvol.data.web.SourceCatalog
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BundledBookMetadataTest {
    private fun verify(key: String, html: String, count: Int) = runBlocking {
        val catalog = SourceCatalog(RuntimeEnvironment.getApplication())
        val raw = Json.parseToJsonElement(catalog.definitions(setOf(key))).jsonArray.single().jsonObject
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(html)
            }
            fixture.source { raw }.use { source ->
                val adapter = RuleWebBookDataSource(Identifier("rules", "bundled-metadata"), source)
                val id = fixture.server.url("/book/one").toString()
                val information = adapter.getBookInformation(id).getOrElse { error(it.toString()) }
                val display = adapter.informationForDisplay(id, information)
                assertEquals("Fixture", display.title)
                assertEquals(count, display.wordCount.count)
                assertEquals(LocalDateTime.of(2026, 9, 14, 8, 0), display.lastUpdated)
                assertFalse(display.tags.any { it.contains("2026-09-14") })
                assertEquals("Detail and display must use only the existing detail request", 1, fixture.server.requestCount)
                assertEquals(information.lastUpdated, adapter.getBookInformation(id).getOrElse { error(it.toString()) }.lastUpdated)
            }
        }
    }

    @Test fun aliceMetadataReachesTheHostWithoutLosingItsUnitOrDate() = verify("https://www.alicesw.com", """
        <meta name='og:novel:update_time' content='2026-09-14 08:00:00'>
        <div class='detail-box'><div class='top'><p class='xs-title'>Fixture</p>
        <a href='/search.html?f=author'>Author</a><p class='xs-show'>字数： 1.23万 · 连载</p>
        <a href='/book/1/2.html'>Two</a></div></div><div class='jianjie'>Description</div>
    """, 12300)

    @Test fun shuhaigeSeparatesItsDateFromGenreAndStatus() = verify("https://m.shuhaige.net/", """
        <div class='detail'><div class='name'>Fixture</div><p class='author'><a>Author</a></p>
        <p>Description</p><p><a>Fantasy</a><span>连载</span><span>1.23万字</span></p>
        <p class='new'><a href='/chapter'>Two</a></p><p>最后更新：2026-09-14 08:00:00</p></div>
    """, 12300)

    @Test fun x33yqSeparatesItsDateFromGenre() = verify("https://m.x33yq.org/", """
        <div class='name'><strong>Fixture</strong></div><div class='author'><a>Author</a></div>
        <span class='layui-btn-radius'>Fantasy</span><span class='layui-bg-red'>12,345字</span>
        <div class='new'><a href='/chapter'>Two</a></div><p>最后更新：2026-09-14 08:00:00</p>
    """, 12345)

}
