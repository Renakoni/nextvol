package hnovel.content

import hnovel.imports.*
import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FanqieSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = checkNotNull(javaClass.getResourceAsStream("/known-sources/fanqie-taijiwang.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun novelSearchPagesLeadToCompleteVolumesAndTextForDifferentBooks() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val local = fixture.server.url("/").toString().removeSuffix("/")
            val definition = Json.parseToJsonElement(original.replace("https://fq.taijiwang.top", local)).jsonObject
            val offsets = mutableListOf<String?>()
            val queries = mutableListOf<String?>()
            val bookIds = listOf("9007199254740993101", "9007199254740993202")
            fun book(id: String) = buildJsonObject {
                put("book_id", id); put("book_name", "Novel $id"); put("author", "Writer")
                put("abstract", "Description"); put("tags", "仙侠,小说"); put("word_number", "123456")
                put("last_chapter_title", "Last"); put("last_publish_time", "1789257600")
            }
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl!!
                    val data = when (url.encodedPath) {
                        "/api/search" -> {
                            offsets += url.queryParameter("offset")
                            queries += url.queryParameter("key")
                            assertEquals("3", url.queryParameter("tab_type"))
                            val ids = if (url.queryParameter("offset") == "0") bookIds else listOf("page-${url.queryParameter("offset")}")
                            buildJsonObject {
                                put("code", 0)
                                put("search_tabs", buildJsonArray {
                                    // The novel tab is selected by type, not by its position among other media.
                                    add(buildJsonObject {
                                        put("tab_type", 8)
                                        put("data", buildJsonArray { add(buildJsonObject { put("book_data", buildJsonArray { add(book("comic")) }) }) })
                                    })
                                    add(buildJsonObject {
                                        put("tab_type", 3); put("has_more", true); put("next_offset", 10)
                                        put("data", JsonArray(ids.map { id -> buildJsonObject { put("book_data", buildJsonArray { add(book(id)) }) } }))
                                    })
                                })
                            }
                        }
                        "/api/detail" -> buildJsonObject { put("data", book(url.queryParameter("book_id")!!)) }
                        "/api/book" -> {
                            val id = url.queryParameter("book_id")!!
                            val count = if (id == bookIds.first()) 1347 else 5
                            val chapters = (0 until count).map { index -> buildJsonObject {
                                put("itemId", "$id-$index"); put("title", "第 $index 章 山川异域，风月同天"); put("needPay", 0)
                                put("firstPassTime", "1753416301"); put("realChapterOrder", (index + 1).toString())
                                put("isChapterLock", false); put("isPaidPublication", false); put("isPaidStory", false)
                                put("volume_name", if (index < count / 2) "First volume" else "Second volume")
                            } }
                            buildJsonObject { put("data", buildJsonObject {
                                put("allItemIds", JsonArray(chapters.map { it.getValue("itemId") }))
                                put("volumeNameList", buildJsonArray { add("First volume"); add("Second volume") })
                                put("chapterListWithVolume", buildJsonArray {
                                    add(JsonArray(chapters.take(count / 2))); add(JsonArray(chapters.drop(count / 2)))
                                })
                            }) }
                        }
                        "/api/content" -> {
                            assertEquals("小说", url.queryParameter("tab"))
                            buildJsonObject { put("content", "第一段 ${url.queryParameter("item_id")}\n\n空行后的正文\n最后一段") }
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody(buildJsonObject { put("code", 200); put("data", data) }.toString())
                }
            }
            fixture.source(customize = { definition }).use { source ->
                val books = source.search("仙侠 & 修仙", 1)
                assertEquals(2, books.size)
                assertNotEquals(source.search("仙侠 & 修仙", 2).single().id, source.search("仙侠 & 修仙", 3).single().id)
                assertEquals(listOf("0", "10", "20"), offsets)
                assertEquals(List(3) { "仙侠 & 修仙" }, queries)
                for ((index, book) in books.withIndex()) {
                    val info = source.information(book.id)
                    assertEquals("Writer", info.author)
                    assertEquals("Description", info.description)
                    val directory = source.directory(book.id)
                    assertEquals(listOf("First volume", "Second volume"), directory.filter { it.isVolume }.map { it.title })
                    val chapters = directory.filterNot { it.isVolume }
                    assertEquals(if (index == 0) 1347 else 5, chapters.size)
                    assertEquals(chapters.size, chapters.map { it.id }.distinct().size)
                    for (chapterIndex in listOf(0, chapters.size / 2, chapters.lastIndex)) {
                        val content = source.content(book.id, chapters[chapterIndex].id)
                        val text = content.parts.mapNotNull { it.text }.joinToString("\n")
                        assertTrue(text.contains("${bookIds[index]}-$chapterIndex"))
                        assertTrue(text.contains("空行后的正文"))
                        assertTrue(text.contains("最后一段"))
                        assertTrue(content.parts.count { it.text != null } >= 3)
                        assertEquals(chapters.getOrNull(chapterIndex - 1)?.id, content.previous)
                        assertEquals(chapters.getOrNull(chapterIndex + 1)?.id, content.next)
                    }
                }
            }
        }
    }
}
