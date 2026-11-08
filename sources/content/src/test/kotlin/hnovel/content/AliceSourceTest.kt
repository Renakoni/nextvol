package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AliceSourceTest {
    private fun definition(fixture: RuleSourceFixture): JsonObject {
        val original = Json.parseToJsonElement(File(
            "../../app/src/main/assets/source-catalog/Adult.json").readText()).jsonArray
            .map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.alicesw.com" }
        return Json.parseToJsonElement(original.toString().replace(
            "https://www.alicesw.com", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
    }

    @Test fun bundledSourceExposesLiveCategoriesAndHomepageListsWithoutChangingTheirPaging() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val ranks = listOf("日榜", "周榜", "月榜", "总榜")
            val navigation = "<nav id='nav'>" +
                (1..29).joinToString("") { "<a href='/lists/$it.html'>分类$it</a>" } +
                "<a href='/all/order/update_time+desc.html'>最新</a><a href='/original.html'>原创</a>" +
                ranks.mapIndexed { i, title -> "<a href='/other/rank_hits/order/$i.html'>$title</a>" }.joinToString("") +
                "<a href='/lists/1.html?duplicate=1'>重复</a><a href='/login.html'>登录</a></nav>"
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    if (request.requestUrl!!.encodedPath == "/") navigation else
                        "<div class='rec_rullist'><ul><li class='two'><a href='/novel/1.html'>Fixture全文阅读</a></li></ul></div>")
            }
            fixture.source { definition(fixture) }.use { source ->
                assertTrue(source.canDiscover)
                assertTrue(source.canCategorize)
                assertTrue(source.canFeed)
                assertEquals(0, fixture.server.requestCount)
                val catalog = source.openDiscovery("alice").catalog()
                assertEquals(35, catalog.rows.size)
                assertEquals(29, catalog.rows.count { it.showInCategories })
                assertTrue(catalog.rows.filter { it.showInCategories }.all { it.url.contains("/lists/") })
                assertEquals(listOf("最新") + ranks, RuleDiscoveryClassifier.feed(catalog).map { it.title })
                val navigationRequest = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("/", navigationRequest.path)
                assertTrue(navigationRequest.getHeader("User-Agent")!!.contains("Windows"))
                for (title in listOf("分类1", "最新", "原创", "日榜")) {
                    val row = catalog.rows.single { it.title == title }
                    assertEquals("Fixture", source.discovery(row.url, 65).single().title)
                    val request = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals(if (title == "日榜") null else "65", request.requestUrl!!.queryParameter("page"))
                    assertTrue(request.getHeader("User-Agent")!!.contains("Windows"))
                }
            }
        }
    }

    @Test fun searchAndReadingUseTheirOwnPageLayoutsAndPreserveBookMetadata() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val cover = ByteArray(64) { it.toByte() }.apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte() }
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val html = when (request.requestUrl!!.encodedPath) {
                        "/search.html" -> """<div class='list-group'><div class='list-group-item'>
                            <h5><a href='/novel/1.html'>1. Fixture全文阅读</a></h5>
                            <p class='mb-1'><a href='/search.html?f=author'>Author</a> 字数：1.23万</p>
                            <div class='content-txt'>Summary</div></div></div>"""
                        "/novel/1.html" -> """<meta name='og:novel:update_time' content=' 2026-09-14 08:00:00 '>
                            <div class='detail-box'><div class='top'><p class='xs-title'>Fixture</p>
                            <a href='/search.html?f=author'>Author</a><p class='xs-show'>字数： 1.23万 · 连载</p>
                            <a href='/book/1/2.html'>Two</a></div>
                            <div class='imgbox'><img data-src='/cover.png' src='/placeholder.png'></div></div>
                            <div class='jianjie'>Description</div><div class='tags'><a href='/search.html?f=tag'># Fantasy</a></div>
                            <a href='/other/chapters/id/1.html'>目录</a>"""
                        "/other/chapters/id/1.html" -> """<div class='section-list'>
                            <a href='/book/1/1.html'>One</a><a href='/book/1/2.html'>Two</a></div>"""
                        "/book/1/1.html" -> """<article id='chapterContent'><h3>One</h3>
                            <div class='content_txt'><p>First chapter.</p></div></article>
                            <a id='j_chapterNext' href='/book/1/2.html'>下一章</a>"""
                        "/book/1/2.html" -> """<article id='chapterContent'><h3>Two</h3>
                            <div class='content_txt'><p>Second chapter.</p></div></article>"""
                        "/cover.png" -> return MockResponse().setBody(okio.Buffer().write(cover))
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html)
                }
            }
            fixture.source { definition(fixture) }.use { source ->
                val keyword = "书 源&测试"
                val book = source.search(keyword, 2).single()
                assertEquals("Fixture", book.title)
                val search = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals(keyword, search.requestUrl!!.queryParameter("q"))
                assertEquals("2", search.requestUrl!!.queryParameter("p"))
                assertTrue(search.getHeader("User-Agent")!!.contains("Windows"))
                val info = source.information(book.id)
                assertEquals("Author", info.author)
                assertEquals("Description", info.description)
                assertEquals(listOf("Fantasy"), info.tags)
                assertEquals("1.23万", info.wordCount)
                assertEquals("Two", info.latestChapter)
                assertEquals("2026-09-14 08:00:00", info.updateTime)
                assertTrue(info.coverUrl.endsWith("/cover.png"))
                val chapters = source.directory(book.id)
                assertEquals(listOf("One", "Two"), chapters.map { it.title })
                val first = source.content(book.id, chapters[0].id).parts.mapNotNull { it.text }.joinToString("")
                assertTrue(first.contains("First chapter."))
                assertFalse(first.contains("Second chapter."))
                assertTrue(source.content(book.id, chapters[1].id).parts.any { it.text?.contains("Second chapter.") == true })
                assertArrayEquals(cover, source.image(book.id, info.coverUrl, true))
                repeat(fixture.server.requestCount - 1) {
                    val request = requireNotNull(fixture.server.takeRequest(1, TimeUnit.SECONDS))
                    assertTrue(request.getHeader("User-Agent")!!.contains("Android"))
                }
            }
        }
    }

    @Test fun missingSiteNavigationReportsARuleFailureInsteadOfAnEmptyCatalogue() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html><p>No navigation</p></html>")
            }
            fixture.source { definition(fixture) }.use { source ->
                try {
                    source.openDiscovery("alice").catalog()
                    fail("Missing navigation must remain a visible discovery error")
                } catch (failure: SourceContentException) {
                    assertEquals("exploreUrl", failure.field)
                }
            }
        }
    }
}
