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
    private val oldBook = "/api/novel/book/directory/list/v1?device_platform=android&parent_enterfrom=novel_channel_search.tab.&aid=1967&book_id=$bookId"
    private val oldChapter = "/api/novel/book/reader/full/v1/?device_platform=android&parent_enterfrom=novel_channel_search.tab.&aid=2329&platform_id=1&group_id=$itemId&item_id=$itemId"

    private fun definition(fixture: RuleSourceFixture): JsonObject {
        val original = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/Platforms.json").readText())
            .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://novel.snssdk.com苏念" }
        var raw = original.toString()
        for (origin in listOf("https://novel.snssdk.com", "https://fanqienovel.com", "https://api-lf.fanqiesdk.com", "http://101.35.133.34:5000"))
            raw = raw.replace(origin, fixture.server.url("/").toString().removeSuffix("/"))
        return Json.parseToJsonElement(raw).jsonObject
    }

    private fun fixture(invalidContent: Boolean = false) = RuleSourceFixture().also { fixture ->
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when (request.requestUrl!!.encodedPath) {
                    "/api/novel/book/directory/list/v1", "/api/novel/book/reader/full/v1/" -> ""
                    "/page/$bookId" -> "<h1>测试书</h1><span class='author-name-text'>作者</span><div class='page-abstract-content'>简介</div>"
                    "/api/reader/directory/detail" -> """{"data":{"chapterListWithVolume":[[{"itemId":"$itemId","title":"中段章节"}]]}}"""
                    "/api/raw_full" -> if (invalidContent) """{"code":503,"data":null}""" else
                        """{"code":200,"data":{"title":"中段章节","content":"<header>网站导航</header><p>这是中段正文。</p><footer>网站版权</footer>","novel_data":{"item_id":"$itemId"}}}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
    }

    @Test fun emptyLegacyResponsesStillOpenSavedBookAndChapterIdentities() = runBlocking {
        fixture().use { fixture ->
            fixture.source { definition(fixture) }.use { source ->
                val id = fixture.server.url(oldBook).toString()
                val chapterId = fixture.server.url(oldChapter).toString()
                val book = source.information(id)
                assertEquals("测试书", book.title)
                assertEquals(id, book.id)
                assertEquals(chapterId, source.directory(id).single().id)
                val content = source.content(id, chapterId)
                val text = content.parts.mapNotNull { it.text }.joinToString()
                assertTrue(text, text.contains("这是中段正文。"))
                assertFalse(text, text.contains("网站导航"))
                assertFalse(text, text.contains("网站版权"))
            }
        }
    }

    @Test fun backendFailureIsNotDisplayedAsChapterText() = runBlocking {
        fixture(invalidContent = true).use { fixture ->
            fixture.source { definition(fixture) }.use { source ->
                val id = fixture.server.url(oldBook).toString()
                source.information(id)
                val chapter = source.directory(id).single()
                val failure = try { source.content(id, chapter.id); null } catch (error: SourceContentException) { error }
                assertNotNull(failure)
                assertEquals("ruleContent.content", failure!!.field)
            }
        }
    }
}
