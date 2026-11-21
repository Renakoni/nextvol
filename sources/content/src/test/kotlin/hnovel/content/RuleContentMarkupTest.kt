package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RuleContentMarkupTest {
    @Test fun longChineseChapterKeepsEveryParagraphThroughTheWorkerEnvelope() = chapter(980, "文".repeat(55))

    @Test fun jsonChapterRetainsNewlinesAndInlineImagePosition() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setHeader("Content-Type", "application/json").setBody(
                        buildJsonObject { put("content", "\u3000\u3000First &amp; paragraph.\r\n\r\nSecond paragraph.\n" +
                            "<img src='../image.png'>\nThird paragraph.") }.toString())
                    else normal.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to
                buildJsonObject { put("content", "$.content") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf(ContentPart(text = "First & paragraph."), ContentPart(text = "Second paragraph."),
                    ContentPart(image = fixture.server.url("/image.png").toString()), ContentPart(text = "Third paragraph.")),
                    source.content(book.id, chapter.id).parts)
            }
        }
    }

    @Test fun textNodesChapterRetainsParagraphBoundaries() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setBody("<article>First paragraph.<br>Second paragraph.<br>Third paragraph.</article>")
                    else normal.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to
                buildJsonObject { put("content", "article@textNodes") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf("First paragraph.", "Second paragraph.", "Third paragraph."),
                    source.content(book.id, chapter.id).parts.mapNotNull { it.text })
            }
        }
    }

    @Test fun wholeChapterReplacementReceivesDecodedTrimmedLinesAndKeepsImages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/c/1" -> MockResponse().setBody("<article><p> First &amp; paragraph. </p>" +
                        "<p>DROP advertisement</p><img src='../image.png'><p> Join </p></article>" +
                        "<a class='next' href='/c/1b'>next</a>")
                    "/c/1b" -> MockResponse().setBody("<article><p> pages </p><p> Last </p></article>")
                    else -> normal.dispatch(request)
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", "article@html")
                put("nextContentUrl", "a.next@href")
                put("replaceRegex", """@js:
                    if(result.indexOf('First & paragraph.')<0)throw 'entities not decoded';
                    if(result.indexOf('<p>')>=0)throw 'paragraph markup not formatted';
                    result.replace(/^DROP[^\n]*$/gm,'').replace(/Join\n+pages/,'Joined pages')
                """.trimIndent())
            })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf(ContentPart(text = "First & paragraph."),
                    ContentPart(image = fixture.server.url("/image.png").toString()),
                    ContentPart(text = "Joined pages"), ContentPart(text = "Last")),
                    source.content(book.id, chapter.id).parts)
            }
        }
    }

    @Test fun formattingDoesNotTurnHiddenElementsIntoChapterText() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", "@js:'<p>Body</p><script>hiddenScript</script><style>hiddenStyle</style><noscript>hiddenFallback</noscript>'")
            })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf("Body"), source.content(book.id, chapter.id).parts.map { it.text })
            }
        }
    }

    @Test fun decodedLiteralTagsAreNotParsedAsHtmlAgain() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to buildJsonObject {
                put("content", "@js:'<p>&lt;b&gt;literal&lt;/b&gt; &amp;amp;</p><img src=\"../image.png\"><p>Tail</p>'")
            })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                assertEquals(listOf(ContentPart(text = "<b>literal</b> &amp;"),
                    ContentPart(image = fixture.server.url("/image.png").toString()), ContentPart(text = "Tail")),
                    source.content(book.id, chapter.id).parts)
            }
        }
    }

    private fun chapter(count: Int, lineText: String) = runBlocking {
        RuleSourceFixture().use { fixture ->
            val paragraphs = List(count) { "Paragraph $it " + lineText }
            val middle = count / 2
            val html = paragraphs.mapIndexed { index, text ->
                (if (index == middle) "<img src='../image.png'>" else "") + "<p>$text</p>"
            }.joinToString("")
            val normal = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/c/1") MockResponse().setBody("<article>$html</article>")
                    else normal.dispatch(request)
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleContent" to
                buildJsonObject { put("content", "article@html") })) }).use { source ->
                val book = source.search("title").single()
                val chapter = source.directory(book.id)[1]
                val content = source.content(book.id, chapter.id)
                assertEquals(paragraphs, content.parts.mapNotNull { it.text })
                assertEquals(count + 1, content.parts.size)
                assertEquals(ContentPart(image = fixture.server.url("/image.png").toString()), content.parts[middle])
                assertEquals(chapter.id, content.id)
                assertEquals(chapter.title, content.title)
            }
        }
    }
}
