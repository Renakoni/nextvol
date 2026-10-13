package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundledFanqieSourceTest {
    private val bookId = "7143038691944959011"
    private val itemId = "7315802016398180926"
    private val bookPath = "/page/$bookId"
    private val chapterPath = "/api/raw_full?item_id=$itemId"

    private fun definition(fixture: RuleSourceFixture): JsonObject {
        val original = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/Platforms.json").readText())
            .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://novel.snssdk.com苏念" }
        var raw = original.toString()
        for (origin in listOf("https://novel.snssdk.com", "https://fanqienovel.com", "https://api-lf.fanqiesdk.com", "http://101.35.133.34:5000"))
            raw = raw.replace(origin, fixture.server.url("/").toString().removeSuffix("/"))
        return Json.parseToJsonElement(raw).jsonObject
    }

    private fun fixture() = RuleSourceFixture().also { fixture ->
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl!!.encodedPath) {
                    "/api/novel/channel/homepage/search/search/v1/" -> """{"data":{"ret_data":[{"book_id":"$bookId","book_name":"测试书","author":"作者"}]}}"""
                    "/api/novel/channel/homepage/new_category/book_list/v1/" -> """{"data":{"data":[{"book_id":"$bookId","book_name":"测试书"}]}}"""
                    "/page/$bookId" -> "<h1>测试书</h1><span class='author-name-text'>作者</span><div class='page-abstract-content'>简介</div>"
                    "/api/reader/directory/detail" -> """{"data":{"chapterListWithVolume":[[{"itemId":"$itemId","title":"中段章节"}]]}}"""
                    "/api/raw_full" -> """{"code":200,"data":{"title":"中段章节","content":"<header>网站导航</header><p>这是中段正文。</p><footer>网站版权</footer>","novel_data":{"item_id":"$itemId"}}}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
    }

    @Test fun searchAndDiscoveryReadWithoutRequestingRetiredBookOrChapterEndpoints() = runBlocking {
        fixture().use { fixture ->
            fixture.source { definition(fixture) }.use { source ->
                val id = fixture.server.url(bookPath).toString()
                val chapterId = fixture.server.url(chapterPath).toString()
                assertEquals(id, source.search("测试").single().id)
                assertEquals(id, source.discovery(fixture.server.url("/api/novel/channel/homepage/new_category/book_list/v1/").toString(), 1).single().id)
                val book = source.information(id)
                assertEquals("测试书", book.title)
                assertEquals(id, book.id)
                assertEquals(chapterId, source.directory(id).single().id)
                val content = source.content(id, chapterId)
                val text = content.parts.mapNotNull { it.text }.joinToString()
                assertTrue(text, text.contains("这是中段正文。"))
                assertFalse(text, text.contains("网站导航"))
                assertFalse(text, text.contains("网站版权"))
                val paths = (0 until fixture.server.requestCount).map { fixture.server.takeRequest().requestUrl!!.encodedPath }
                assertFalse(paths.toString(), paths.any { it.startsWith("/api/novel/book/") })
                assertEquals(1, paths.count { it == "/api/raw_full" })
            }
        }
    }
}
