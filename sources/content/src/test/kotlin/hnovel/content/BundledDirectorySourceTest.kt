package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class BundledDirectorySourceTest {
    @Test fun qinghuayuRestrictedPageIsEmptyContentAndPublicPostStillParses() = runBlocking {
        for (allowed in listOf(false, true)) RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""
                    <meta name="keywords" content="测试书"><div class="plhin">
                    ${if (allowed) "<div class='authi'><a class='xi2'>作者</a><em>发表于今天</em></div><img src='/ico_lz.png'><table><tr><td class='t_f'>真实测试正文。</td></tr></table>" else "<p>抱歉，您尚未登录，没有权限访问该版块</p>"}
                    </div>""")
            }
            fixture.source { definition(fixture, "Female", "https://allcp.net") }.use { source ->
                val id = fixture.server.url("/forum.php?mod=viewthread&tid=42&page=1").toString()
                val chapter = source.directory(id).single()
                if (allowed) assertTrue(source.content(id, chapter.id).parts.any { it.text?.contains("真实测试正文") == true })
                else {
                    val failure = runCatching { source.content(id, chapter.id) }.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.EmptyContent, failure.code)
                }
            }
        }
    }

    @Test fun seventeenKReadsNestedBookMetadataAndKeepsPaidChapterFlags() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when {
                    request.path!!.contains("/split1/merge") -> """{"data":[{"bookTop":{
                        "bookId":42,"bookName":"测试书","authorPenName":"作者","introduction":"简介"}}]}"""
                    request.path!!.contains("/volumes") -> """{"data":{"volumes":[{"chapters":[
                        {"chapterId":71,"chapterName":"第一章","isVIP":{"id":0}},
                        {"chapterId":72,"chapterName":"第二章","isVIP":{"id":1}}]}]}}"""
                    else -> "<html><title>Legacy book page</title></html>"
                })
            }
            fixture.source {
                val raw = definition(fixture, "Platforms", "https://www.17k.com")
                Json.parseToJsonElement(raw.toString().replace("http://api.17k.com",
                    fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
            }.use { source ->
                val id = fixture.server.url("/book/42.html").toString()
                val book = source.information(id, false)
                assertEquals("测试书", book.title)
                assertEquals(id, book.id)
                val chapters = source.directory(id)
                assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
                assertFalse(chapters.first().isVip)
                assertTrue(chapters.last().isVip)
            }
        }
    }

    @Test fun yamiboDirectoryRetainsTheFirstThreeChapters() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""
                    <h3 class='col-md-12'>测试书</h3><div class='panel-body'></div><div class='panel-body'>简介</div>
                    <p class='col-md-4 col-xs-6'><a href='/work/read?id=1'>开始阅读</a></p>
                    ${(1..4).joinToString("") { "<div class='col-md-4 col-xs-6'><a href='/novel/view-chapter?id=$it'>第${it}章</a></div>" }}
                    """)
            }
            fixture.source {
                val raw = definition(fixture, "Anime", "https://www.yamibo.com/site/novel")
                Json.parseToJsonElement(raw.toString().replace("https://www.yamibo.com",
                    fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
            }.use { source ->
                val chapters = source.directory(fixture.server.url("/novel/1").toString())
                assertEquals(listOf("第1章", "第2章", "第3章", "第4章"), chapters.map { it.title })
                assertTrue(chapters.first().id.endsWith("/novel/view-chapter?id=1"))
            }
        }
    }

    @Test fun mirrorReadsTheCurrentDirectoryAndPaidChapterFlags() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    if (request.requestUrl!!.encodedPath == "/api/books/42") """{"data":{
                        "id":42,"name":"测试书","user":{"nickname":"作者"},"intro":"简介",
                        "chapters":{"data":[{"id":71,"name":"第一章","points":0},{"id":72,"name":"第二章","points":10}]}
                    }}""" else "<html><title>Book page</title></html>")
            }
            fixture.source {
                val raw = definition(fixture, "Literature", "https://mirrorfiction.com")
                Json.parseToJsonElement(raw.toString().replace("https://www.mirrorfiction.com",
                    fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
            }.use { source ->
                val id = fixture.server.url("/book/42").toString()
                val chapters = source.directory(id)
                assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
                assertFalse(chapters.first().isVip)
                assertTrue(chapters.last().isVip)
                assertTrue(chapters.first().id.endsWith("/chapter/71"))
            }
        }
    }

    private fun definition(fixture: RuleSourceFixture, category: String, key: String): JsonObject {
        val raw = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/$category.json").readText())
            .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == key }
        return Json.parseToJsonElement(raw.toString().replace(key.removeSuffix("/"),
            fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
    }

    @Test fun neteaseSeparatesVolumesAndRejectsUnavailablePaidContent() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val text = Base64.getEncoder().encodeToString("<h1>第一章</h1><p>免费章节正文。</p>".toByteArray())
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                    if (request.requestUrl!!.encodedPath == "/search/book/data.json")
                        """{"data":{"books":[{"sourceUuid":"fixture","title":"测试书"}]}}"""
                    else when (request.requestUrl!!.queryParameter("content_uuid")) {
                        "free" -> """{"successful":true,"data":{"content":"$text"}}"""
                        "paid" -> """{"successful":false,"code":-1,"data":null}"""
                        else -> """{"data":{"catalog":[
                            {"uuid":"volume","title":"第一卷","leaf":false,"needPay":false},
                            {"uuid":"free","title":"第一章","leaf":true,"needPay":false},
                            {"uuid":"paid","title":"第二章","leaf":true,"needPay":true}]}}"""
                    })
            }
            fixture.source { definition(fixture, "Platforms", "https://m.yuedu.163.com/") }.use { source ->
                val id = source.search("测试书").single().id
                val chapters = source.directory(id)
                assertTrue(chapters.first().isVolume)
                assertEquals(2, chapters.count { !it.isVolume })
                assertTrue(chapters.last().isVip)
                assertTrue(source.content(id, chapters[1].id).parts.any { it.text?.contains("免费章节正文。") == true })
                val failure = try { source.content(id, chapters.last().id); null } catch (e: SourceContentException) { e }
                assertEquals(ContentError.EmptyContent, failure?.code)
            }
        }
    }

    @Test fun linovelDirectoryKeepsVolumesWithoutDependingOnDetachedNodeParents() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("""
                    <h1>测试书</h1><div class='section-list'>
                    <div class='section'><h2>第一卷</h2><div class='chapter'><a href='/book/1/1.html'>第一章</a></div></div>
                    <div class='section'><h2>第二卷</h2><div class='chapter'><a href='/book/1/2.html'>第二章</a></div></div>
                    </div>""")
            }
            fixture.source { definition(fixture, "Anime", "https://www.linovel.net") }.use { source ->
                val chapters = source.directory(fixture.server.url("/book/1.html").toString())
                assertEquals(listOf("第一卷", "第一章", "第二卷", "第二章"), chapters.map { it.title })
                assertEquals(listOf(true, false, true, false), chapters.map { it.isVolume })
                assertTrue(chapters.last().id.endsWith("/book/1/2.html"))
            }
        }
    }
}
