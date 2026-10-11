package hnovel.content

import hnovel.execution.ExecutionTask
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SearchBatchParsingTest {
    private val fields = buildJsonObject {
        put("bookList", "-li"); put("name", "h2@text"); put("author", "b@text"); put("kind", "i@text")
        put("wordCount", "em@text"); put("lastChapter", "strong@text"); put("intro", "p@text")
        put("coverUrl", "img@src"); put("updateTime", "time@text"); put("bookUrl", "a@href")
    }
    private fun RuleSourceFixture.respond(body: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(body)
        }
    }

    @Test fun batchesMatchSerialFieldsStatesOrderAndStorageWithFewerWorkerCalls() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.respond((1..17).joinToString("") { """<li><h2>Book $it &amp; more</h2><a href='/book/$it'>read</a>
                <b>Author $it</b><i>One</i><i>Two</i><em>1234</em><strong>Chapter $it</strong><p>Intro $it</p>
                <img src='/cover/$it.jpg, {"headers":{"Referer":"https://example.test/"}}'><time>2026-10-01</time></li>""" } +
                "<li><h2></h2><a href='javascript:void(0)'>blank title skips invalid URL</a></li>")
            var batches = 0
            var fieldsRun = 0
            fixture.beforeRun = { task, _ ->
                if (task is ExecutionTask.SearchBooks) batches++
                if (task is ExecutionTask.Rule && task.location.field in ExecutionTask.SearchBooks.FIELDS.map { "ruleSearch.$it" }) fieldsRun++
            }
            val batched = fixture.source { JsonObject(it + ("ruleSearch" to fields)) }.use { source ->
                source.openSearchPages("query").page(1).books.also { books ->
                    assertEquals(17, books.size)
                    assertEquals("Book 17 & more", books.first().title)
                    assertEquals("Author 17", books.first().author)
                    assertEquals(listOf("One", "Two"), books.first().tags)
                    assertEquals("Intro 17", books.first().description)
                    for (book in books) assertEquals(book, source.cachedInformation(book.id))
                }
            }
            assertEquals(3, batches)
            assertEquals(0, fieldsRun)
            val serial = fixture.source { JsonObject(it + mapOf("ruleSearch" to fields, "jsLib" to JsonPrimitive("var unchanged = true;"))) }
                .use { it.openSearchPages("query").page(1).books }
            assertEquals(batched, serial)
            assertEquals(3, batches)
            assertEquals(17 * 9 + 1, fieldsRun)
        }
    }

    @Test fun jsonOptionalFailuresDuplicatesAndNextPagesMatchTheSerialPath() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.respond("""{"books":[{"name":"First","id":"/one","author":"Writer"},
                {"name":"Second","id":"/two"},{"name":"Duplicate","id":"/one"}],"next":"/more"}""")
            val jsonFields = buildJsonObject {
                put("bookList", "$.books[*]"); put("name", "name"); put("author", "author"); put("bookUrl", "id")
                put("intro", "$.invalid["); put("coverUrl", "$.invalid["); put("nextPageUrl", "$.next")
            }
            val batch = fixture.source { JsonObject(it + ("ruleSearch" to jsonFields)) }.use { it.openSearchPages("q").page(1) }
            val serial = fixture.source { JsonObject(it + mapOf("ruleSearch" to jsonFields, "jsLib" to JsonPrimitive("var unchanged = true;"))) }
                .use { it.openSearchPages("q").page(1) }
            assertEquals(serial.books, batch.books)
            assertEquals(serial.nextPage, batch.nextPage)
            assertEquals(listOf("First", "Second"), batch.books.map { it.title })
        }
    }

    @Test fun statefulFieldsLibrariesLoginHooksAndRequestWritesRetainSerialEvaluation() = runBlocking {
        for (extra in listOf(
            mapOf("ruleSearch" to JsonObject(fields + ("author" to JsonPrimitive("@js:book.name")))),
            mapOf("jsLib" to JsonPrimitive("var unchanged=true;")),
            mapOf("loginCheckJs" to JsonPrimitive("result")),
            mapOf("loginUrl" to JsonPrimitive("/login")),
            mapOf("searchUrl" to JsonPrimitive("@js:cache.putMemory('key','value');'/search'")),
            mapOf("ruleSearch" to JsonObject(fields + ("nextPageUrl" to JsonPrimitive("@js:cache.putMemory('key','value');''"))))
        )) RuleSourceFixture().use { fixture ->
            fixture.beforeRun = { task, _ -> assertFalse(task is ExecutionTask.SearchBooks) }
            fixture.source { JsonObject(it + ("ruleSearch" to fields) + extra) }.use { source ->
                assertEquals(1, source.openSearchPages("query").page(1).books.size)
            }
        }
    }

    @Test fun redirectedDetailsUseTheirOriginalInformationRules() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.beforeRun = { task, _ -> assertFalse(task is ExecutionTask.SearchBooks) }
            fixture.source { JsonObject(it + mapOf("ruleSearch" to fields, "searchUrl" to JsonPrimitive("/book/one"),
                "bookUrlPattern" to JsonPrimitive(".*/book/one"))) }.use { source ->
                assertEquals("Same title", source.openSearchPages("query").page(1).books.single().title)
            }
        }
    }

    @Test fun requiredFieldFailuresKeepTheSameCodeFieldAndDoNotCommitPartialRows() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for (library in listOf(false, true)) {
                val invalid = JsonObject(fields + ("author" to JsonPrimitive("[")))
                fixture.source { JsonObject(it + mapOf("ruleSearch" to invalid) +
                    if (library) mapOf("jsLib" to JsonPrimitive("var unchanged=true;")) else emptyMap()) }.use { source ->
                    try { source.search("q"); fail("Expected author rule failure") }
                    catch (failure: SourceContentException) {
                        assertEquals(ContentError.InvalidRule, failure.code)
                        assertEquals("ruleSearch.author", failure.field)
                    }
                    assertNull(source.cachedInformation(fixture.server.url("/book/one").toString()))
                }
            }
        }
    }
}
