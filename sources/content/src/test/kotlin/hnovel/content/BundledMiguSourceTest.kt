package hnovel.content

import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class BundledMiguSourceTest {
    @Test fun readsAcrossHtmlAndJsonDirectoryPagesAndEncodedChapters() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val origin = fixture.server.url("/").toString().removeSuffix("/")
            val chapter = """{"reader":{"pluginCode":"reading_page","data":{"content":{"bookChapterInfo":{"content":"<p>测试章节正文。</p>"}}}}}"""
            val standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
            val alphabet = "ABCDEFGHIJKLMabcdefghijklmNOPQRSTUVWXYZnopqrstuvwxyz0123456789+/="
            val encoded = Base64.getEncoder().encodeToString(chapter.toByteArray())
                .map { alphabet[standard.indexOf(it)] }.joinToString("")
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val body = when (url.encodedPath) {
                        "/nap/1/index.htm" -> """<script>var pageData={
                            "info":{"pluginCode":"bkdetail_content_book","data":{"bookName":"测试书"}},
                            "toc":{"pluginCode":"bkdetail_catalog_book","data":{"bookId":"1"}}
                            },pageConfig={};</script>"""
                        "/nap/p/catalogForWap.jsp" -> {
                            val second = url.queryParameter("start") == "2"
                            val data = """{"toc":{"pluginCode":"bkdetail_catalog","data":{"chapter":{
                                "nextPageUrl":"${if (second) "" else "/nap/p/catalogForWap.jsp?bid=1&start=2"}",
                                "volumeInfo":[{"chapterInfo":[{"chapterName":"${if (second) "第二章" else "第一章"}",
                                "chapterUrl":"$origin/nap/1/${if (second) 12 else 11}.htm","feeStatus":0}]}]}}}}"""
                            if (second) data else "<script>var pageData=$data,pageConfig={};</script>"
                        }
                        "/nap/1/11.htm" -> """<script>var pageData={"wpnrt":"$encoded","wksign":"fixture"},pageConfig={};</script>"""
                        "/nap/1/12.htm" -> """<script>var pageData={"reader":{"pluginCode":"reading_page","data":{}}},pageConfig={};</script>"""
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            val raw = Json.parseToJsonElement(File("../../app/src/main/assets/source-catalog/Platforms.json").readText())
                .jsonArray.map { it.jsonObject }.single { it["bookSourceUrl"]?.jsonPrimitive?.content == "https://wap.cmread.com/" }
            val local = raw.toString().replace("https://wap.cmread.com", origin).replace("https://n.cmread.com", origin)
            fixture.source { Json.parseToJsonElement(local).jsonObject }.use { source ->
                val id = "$origin/nap/1/index.htm"
                assertEquals("测试书", source.information(id, false).title)
                val chapters = source.directory(id)
                assertEquals(listOf("第一章", "第二章"), chapters.map { it.title })
                val content = source.content(id, chapters.first().id)
                assertTrue(content.parts.any { it.text?.contains("测试章节正文。") == true })
                val failure = try { source.content(id, chapters.last().id); null } catch (e: SourceContentException) { e }
                assertEquals(ContentError.EmptyContent, failure?.code)
            }
        }
    }
}
