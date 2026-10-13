package hnovel.content

import hnovel.network.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class BundledUaaSourceTest {
    @Test fun currentWebsiteProvidesDiscoveryDirectoryAndGuestContent() = runBlocking {
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Test WebView"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult =
                BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(),
                    java.net.URI(request.url).toURL().readBytes(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                    "/novel/list" -> """<a class="cn-lcard" href="/novel/intro?id=42">
                        <h3 class="cn-lcard__t">测试小说</h3><span class="cn-lcard__byname">作者 著</span>
                        <p class="cn-lcard__d">真实简介</p></a>"""
                    "/novel/intro" -> """<meta name="description" content="真实简介"><h1 class="nd-t1">测试小说</h1>
                        <a class="nd-author__lk">作者</a><div id="ndcBody">
                        <a class="ndc-row" href="/novel/chapter?id=1"><span class="ndc-name">第一章</span><span class="ndc-acc">游客</span></a>
                        <a class="ndc-row" href="/novel/chapter?id=2"><span class="ndc-name">第二章</span><span class="ndc-acc">注册会员</span></a>
                        </div>"""
                    "/novel/chapter" -> if (request.requestUrl!!.queryParameter("id") == "1")
                        """<div class="reader-body"><p>真实章节正文。</p></div><div class="reader-cap">登录提示</div>"""
                        else """<div class="reader-cap">请登录后阅读会员章节</div>"""
                    else -> return MockResponse().setResponseCode(404)
                })
            }
            val raw = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/Adult.json").readText())
                .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.uaa.com/" }
            fixture.source { Json.parseToJsonElement(raw.toString().replace("https://www.uaa.com",
                fixture.server.url("/").toString().removeSuffix("/"))).jsonObject }.use { source ->
                val books = source.discovery("/novel/list?page={{page}}", 1)
                assertEquals(listOf("测试小说"), books.map { it.title })
                assertEquals("作者", source.search("测试").single().author)
                val id = books.single().id
                assertEquals("测试小说", source.information(id, false).title)
                val chapters = source.directory(id)
                assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
                assertFalse(chapters.first().isVip)
                assertTrue(chapters.last().isVip)
                assertEquals("真实章节正文。", source.content(id, chapters.first().id).parts.mapNotNull { it.text }.joinToString(""))
                val failure = runCatching { source.content(id, chapters.last().id) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.EmptyContent, failure.code)
            }
        }
    }
}
