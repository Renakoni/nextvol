package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed pages derived from BookContent/BookChapterList and replayed in the reference App. */
class RuleSourceParityTest {
    @Test fun malformedSourceHeadersKeepDefaultIdentityForPagesAndNestedRequests() = runBlocking {
        val malformed = "{\"X-Partial\":\"must-not-send\",\"User-Agent\":\"unfinished}"
        for (header in listOf(malformed, "@js:${JsonPrimitive(malformed)}", "Mozilla/5.0", "@js:throw new Error('optional header')")) RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive(header),
                "ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject +
                    ("bookList" to JsonPrimitive("@js:java.setContent(java.ajax('/search'));java.getElements('li')")))
            )) }).use { source ->
                assertEquals("Same title", source.search("title").single().title)
                repeat(2) {
                    val request = fixture.server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
                    assertTrue(request.getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
                    assertNull(request.getHeader("X-Partial"))
                }
            }
        }
    }

    @Test fun chapterMetadataKeepsRawUrlsAndTheCatalogueBaseDuringAndAfterExtraction() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "ruleToc" to JsonObject(raw.getValue("ruleToc").jsonObject + mapOf(
                    "chapterList" to JsonPrimitive("li:has(a[href])"),
                    "chapterName" to JsonPrimitive("""a@text<js>
                        if(chapter.baseUrl!==baseUrl || chapter.bookUrl!==book.bookUrl)throw 'missing catalogue context';result
                    </js>""".trimIndent()),
                    "updateTime" to JsonPrimitive("@js:chapter.url"),
                    "isVolume" to JsonPrimitive("@js:if(chapter.tag!==chapter.url)throw 'missing tag';false")
                )),
                "ruleContent" to buildJsonObject {
                    put("content", "@js:[chapter.url,chapter.getAbsoluteURL(),chapter.baseUrl,chapter.tag].join('|')")
                }
            )) }).use { source ->
                val book = source.search("title").single()
                val chapters = source.directory(book.id)
                assertEquals(fixture.server.url("/c/1").toString(), chapters.first().id)
                assertEquals(listOf("/c/1|${fixture.server.url("/c/1")}|${fixture.server.url("/toc/1")}|/c/1"),
                    source.content(book.id, chapters.first().id).parts.map { it.text })
            }
        }
    }

    @Test fun changingContentUrlsStillRespectThePageLimit() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            var contentRequests = 0
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path!!.startsWith("/c/")) {
                    contentRequests++
                    MockResponse().setBody("<article>Page $contentRequests</article>")
                } else normal.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", "article@html"); put("nextContentUrl", "@js:'/c/page'+(page+1)")
            })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                val error = runCatching { source.content(book.id, chapter.id) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Limit, error.code)
                assertEquals(64, contentRequests)
            }
        }
    }

    @Test fun initialContentPageListIsExpandedOnceInOrder() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            val requested = mutableListOf<String>()
            val nav = "<nav><a href='/c/1b'>2</a><a href='/c/1c'>3</a></nav>"
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val number = mapOf("/c/1" to "one", "/c/1b" to "two", "/c/1c" to "three")[request.path]
                        ?: return normal.dispatch(request)
                    requested += request.path!!
                    return MockResponse().setBody("<article><p>Page $number</p></article>$nav")
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", "article@html"); put("nextContentUrl", "nav@a@href")
            })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf("Page one", "Page two", "Page three"),
                    source.content(book.id, chapter.id).parts.map { it.text })
                assertEquals(listOf("/c/1", "/c/1b", "/c/1c"), requested)
            }
        }
    }

    @Test fun contentChainStopsAtAnAlreadyReadPage() = runBlocking {
        for (lastLink in listOf("/c/1b", "/c/1")) RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/c/1b")
                    MockResponse().setBody("<article>Last page</article><a class='next' href='$lastLink'>end</a>")
                else normal.dispatch(request)
            }
            fixture.source().use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(1, source.content(book.id, chapter.id).parts.count { it.text == "Last page" })
            }
        }
    }

    @Test fun optionalTitleAndCatalogueFormattingFailuresKeepReadableChapters() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "ruleToc" to JsonObject(raw.getValue("ruleToc").jsonObject +
                    ("formatJs" to JsonPrimitive("throw new Error('optional format')"))),
                "ruleContent" to JsonObject(raw.getValue("ruleContent").jsonObject +
                    ("title" to JsonPrimitive("@js:throw new Error('optional title')")))
            )) }).use { source ->
                val book = source.search("title").single()
                val chapters = source.directory(book.id)
                assertEquals(listOf("Volume one", "One", "Two"), chapters.map { it.title })
                val content = source.content(book.id, chapters[1].id)
                assertEquals("One", content.title)
                assertTrue(content.parts.any { it.text == "last replaced" })
            }
        }
    }

    @Test fun contentTitleAndReplacementUseFirstPageAfterAllContentVariablesAreWritten() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/c/1" -> MockResponse().setBody("<h1>First page</h1><article>One</article><a class='next' href='/c/1b'>Next</a>")
                    "/c/1b" -> MockResponse().setBody("<h1>Last page</h1><article>Two</article>")
                    else -> normal.dispatch(request)
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", "@js:java.put('seen',Number(java.get('seen')||0)+1);java.getString('article@html')")
                put("nextContentUrl", "a.next@href")
                put("title", "@js:java.getString('h1@text')+' '+java.get('seen')")
                put("replaceRegex", "@js:result+'\\n'+baseUrl")
            })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id).first { !it.isVolume }
                val content = source.content(book.id, chapter.id)
                assertEquals("First page 2", content.title)
                assertEquals(listOf("One", "Two", fixture.server.url("/c/1").toString()), content.parts.map { it.text })
            }
        }
    }

}
