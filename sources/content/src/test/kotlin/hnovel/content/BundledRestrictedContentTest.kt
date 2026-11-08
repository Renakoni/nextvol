package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundledRestrictedContentTest {
    // A local book and directory isolate the maintained content rules from unrelated site markup.
    private fun definition(fixture: RuleSourceFixture, key: String, category: String = "Adult"): JsonObject {
        val raw = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/$category.json").readText())
            .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]!!.jsonPrimitive.content == key }
        return JsonObject(fixture.raw() + mapOf(
            "bookSourceUrl" to JsonPrimitive(fixture.server.url("/").toString()),
            "ruleContent" to raw.getValue("ruleContent"),
            "ruleToc" to buildJsonObject {
                put("chapterList", "li"); put("chapterName", "a@text"); put("chapterUrl", "a@href")
            },
        ))
    }

    private fun fixture(chapter: () -> String, body: () -> String = { "" }) = RuleSourceFixture().also { fixture ->
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                    "/book/one" -> "<h1>测试书</h1><a class='toc' href='/toc'>目录</a>"
                    "/toc" -> "<li><a href='/chapter?paperid=1'>第一章</a></li>"
                    "/chapter" -> chapter()
                    "/showpapercolor.php" -> body()
                    else -> return MockResponse().setResponseCode(404)
                })
            }
        }
    }

    @Test fun haitangRejectsLoginResponsesAndKeepsActualChapterText() = runBlocking {
        var body = "<a href='login.php'>请登录</a>"
        val page = "<div id='colorpanelwritersay'>作者说：测试附言。</div><div class='uk-placeholder'>附加彩蛋。</div>"
        fixture({ page }, { body }).use { fixture ->
            fixture.source { definition(fixture, "https://www.htlvbooks.com") }.use { source ->
                val id = fixture.server.url("/book/one").toString()
                val chapter = source.directory(id).single().id
                val failure = runCatching { source.content(id, chapter) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.EmptyContent, failure.code)
                body = "<p>真实章节正文。</p>"
                val text = source.content(id, chapter).parts.mapNotNull { it.text }.joinToString("\n")
                assertTrue(text, text.contains("真实章节正文。"))
            }
        }
    }

    @Test fun po18RejectsTheSiteNavigationPageButKeepsTheChapterFragment() = runBlocking {
        var html = "<form id='header-search-form'></form><p>敬告啟示</p><p>請登入後閱讀</p>"
        fixture({ html }).use { fixture ->
            fixture.source { definition(fixture, "https://www.po18.tw") }.use { source ->
                val id = fixture.server.url("/book/one").toString()
                val chapter = source.directory(id).single().id
                val failure = runCatching { source.content(id, chapter) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.EmptyContent, failure.code)
                html = "<p>真实章节正文。</p><p>真正的第二段。</p>"
                val text = source.content(id, chapter).parts.mapNotNull { it.text }.joinToString("\n")
                assertEquals("真实章节正文。\n真正的第二段。", text)
            }
        }
    }

    @Test fun popoPreservesStandaloneAndInlineImagesWithoutCopyingNoiseOrComments() = runBlocking {
        fixture({ """<div class='read-txt'><h2>章节标题</h2><div class='imgStyle1'><img src='/opening.jpg'></div>
            <p>第一段。</p><blockquote><p>站点干扰段落</p><img src='/noise.jpg'></blockquote>
            <p>第二段。<img src='/inline.jpg'></p></div><p>站外评论</p>""" }).use { fixture ->
            fixture.source { definition(fixture, "https://www.popo.tw", "Female") }.use { source ->
                val id = fixture.server.url("/book/one").toString()
                val chapter = source.directory(id).single().id
                val parts = source.content(id, chapter).parts
                assertEquals(listOf("opening.jpg", "inline.jpg"), parts.mapNotNull { it.image?.substringAfterLast('/') })
                assertEquals("第一段。\n第二段。", parts.mapNotNull { it.text }.joinToString("\n"))
            }
        }
    }

    @Test fun popoSearchUsesTheCurrentFormTokenAndEncodesTheQueryAcrossPages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val submissions = mutableListOf<RecordedRequest>()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/index" -> MockResponse().setHeader("Set-Cookie", "csrf=fixture; Path=/")
                        .setBody("<input name='_poporf-tk001' value='form+/=token'>")
                    "/search" -> {
                        submissions += request
                        val page = request.body.clone().readUtf8().substringAfter("&page=")
                        MockResponse().setBody("""<div id='BOOK'><div class='box'><a href='/books/$page'>
                            <span class='bname'>Book $page</span></a></div></div>""")
                    }
                    "/tags/test" -> MockResponse().setBody("""<div class='list-view'><div class='box'>
                        <a href='/books/3'><span class='bname'>Category book</span></a></div></div>""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val raw = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/Female.json").readText())
                .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]!!.jsonPrimitive.content == "https://www.popo.tw" }
            fixture.source { Json.parseToJsonElement(raw.toString().replace("https://www.popo.tw",
                fixture.server.url("/").toString().removeSuffix("/"))).jsonObject }.use { source ->
                for (page in 1..2) assertEquals("Book $page", source.search("A&B +中文", page).single().title)
                assertEquals("Category book", source.discovery("/tags/test", 1).single().title)
                for (request in submissions) {
                    assertEquals("POST", request.method)
                    assertEquals("csrf=fixture", request.getHeader("Cookie"))
                    val fields = request.body.readUtf8().split('&').associate {
                        it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    assertEquals("A&B +中文", fields["name"])
                    assertEquals("form+/=token", fields["_poporf-tk001"])
                }
            }
        }
    }
}
