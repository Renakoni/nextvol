package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BundledPlatformContentTest {
    private fun definition(fixture: RuleSourceFixture, host: String): JsonObject {
        val raw = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/Platforms.json").readText())
            .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]!!.jsonPrimitive.content.startsWith(host) }
        return Json.parseToJsonElement(raw.toString().replace(host,
            fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
    }

    @Test fun taduExtractsChapterHtmlWithoutDisplayingTheJsonEnvelope() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                    "/book/1" -> """<a class="bkNm">测试书</a><div class="lf"><ul>
                        <li><a href="/chapter/1">第一章</a></li><li><a href="/chapter/2">第二章</a></li></ul></div>"""
                    "/chapter/1", "/chapter/2" -> """<input id="bookPartResourceUrl" value="/content/${request.requestUrl!!.pathSegments.last()}">"""
                    "/content/1" -> """{"status":200,"data":{"content":"<p>真实章节正文。</p><p>第二段。</p>"}}"""
                    else -> """{"status":500,"data":null}"""
                })
            }
            fixture.source { definition(fixture, "https://www.tadu.com") }.use { source ->
                val id = fixture.server.url("/book/1").toString()
                val chapters = source.directory(id)
                val text = source.content(id, chapters.first().id).parts.mapNotNull { it.text }.joinToString("\n")
                assertEquals("真实章节正文。\n第二段。", text)
                assertTrue(runCatching { source.content(id, chapters.last().id) }.exceptionOrNull() is SourceContentException)
            }
        }
    }

    @Test fun falooKeepsVipFlagsAcrossVolumesAndRejectsTheLoginPanel() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when (request.requestUrl!!.encodedPath) {
                    "/book/1" -> """<h1 id="novelName">测试书</h1><div id="mulu">
                        <div class="DivTable"><div class="DivTr"><div class="DivTd3"><a href="/chapter/1">第一章</a></div></div></div>
                        <div class="DivVip">小说VIP章节部分</div><div class="DivBai"></div>
                        <div class="DivTable"><div class="DivTr"><div class="DivTd"><a href="/chapter/2">第二章</a></div></div></div>
                        <div class="C-Fo-Z-ML-TitleBox">第二卷</div>
                        <div class="DivTable"><div class="DivTr"><div class="DivTd"><a href="/chapter/3">第三章</a></div></div></div></div>"""
                    "/chapter/1" -> """<div class="noveContent"><p>正文里的“升级VIP会员”是角色对话。</p></div>"""
                    else -> """<div class="noveContent"><div class="c_c1">您还没有登录</div>
                        <div class="c_c3"><a>立即登录</a></div><div class="c_c4">升级VIP会员</div></div>"""
                })
            }
            fixture.source { definition(fixture, "https://b.faloo.com") }.use { source ->
                val id = fixture.server.url("/book/1").toString()
                val chapters = source.directory(id)
                assertEquals(listOf(false, true, true), chapters.map { it.isVip })
                assertTrue(source.content(id, chapters.first().id).parts.any { it.text?.contains("角色对话") == true })
                val failure = runCatching { source.content(id, chapters.last().id) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.EmptyContent, failure.code)
            }
        }
    }
}
