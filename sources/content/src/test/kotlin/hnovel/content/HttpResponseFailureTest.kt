package hnovel.content

import hnovel.execution.ExecutionTask
import hnovel.execution.FailureCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class HttpResponseFailureTest {
    private fun rules(raw: JsonObject, script: String, hook: String = "") = JsonObject(raw + mapOf(
        "searchUrl" to JsonPrimitive("/list"),
        "exploreUrl" to JsonPrimitive("Books::/list"),
        "loginCheckJs" to JsonPrimitive(hook),
        "ruleSearch" to fields(script), "ruleExplore" to fields(script)
    ))

    private fun fields(script: String) = buildJsonObject {
        put("bookList", script); put("name", "h2@text"); put("bookUrl", "a@href")
    }

    private fun respond(fixture: RuleSourceFixture, status: Int, body: String = """{"error":true,"body":[]}""") {
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(status).setBody(body)
        }
    }

    @Test fun anEmptyRejectedResponseDoesNotEndPaginationAndCanBeRetried() = runBlocking {
        RuleSourceFixture().use { fixture ->
            respond(fixture, 403)
            fixture.source { rules(it, "<js>try { JSON.parse(result).body.page.items } catch(e) { result.charAt(0)==='<' ? result : [] }</js>li") }.use { source ->
                val pages = source.openDiscovery("http").openPages("/list", emptyMap())
                val failure = runCatching { pages.page(1) }.exceptionOrNull() as? SourceContentException
                assertEquals(ContentError.Network, failure?.code)
                assertEquals(403, failure?.httpStatus)
                respond(fixture, 200, "<li><a href='/book/1'><h2>Recovered</h2></a></li>")
                // The same pager must keep page 1 available after the rejected request.
                assertEquals("Recovered", pages.page(1).books.single().title)
                respond(fixture, 200, "")
                val end = pages.page(2)
                assertTrue(end.books.isEmpty())
                assertNull(end.nextCursor)
            }
        }
    }

    @Test fun rejectedEmptyPagesKeepTheirStatusWhenTheContinuationParserFails() = runBlocking {
        for (search in listOf(false, true)) RuleSourceFixture().use { fixture ->
            respond(fixture, 403)
            fixture.source { raw ->
                val paged = JsonObject(fields("@js:[]") +
                    ("nextPageUrl" to JsonPrimitive("@js:JSON.parse(result).body.page.next")))
                JsonObject(rules(raw, "@js:[]") + mapOf("ruleSearch" to paged, "ruleExplore" to paged))
            }.use { source ->
                val pages = if (search) source.openSearchPages("book")
                    else source.openDiscovery("rejected-continuation").openPages("/list", emptyMap())
                val failure = runCatching { pages.page(1) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Network, failure.code)
                assertEquals(403, failure.httpStatus)
                assertEquals(if (search) "ruleSearch.nextPageUrl" else "ruleExplore.nextPageUrl", failure.field)
                assertEquals(FailureCode.ScriptRuntime, failure.diagnostic?.code)
                respond(fixture, 200, """{"body":{"page":{"next":""}}}""")
                val recovered = pages.page(1)
                assertTrue(recovered.books.isEmpty())
                assertNull(recovered.nextCursor)
            }
        }
    }

    @Test fun rejectedEmptyPagesCannotEndPaginationWithRepeatedContinuations() = runBlocking {
        for (previousPage in listOf(false, true)) RuleSourceFixture().use { fixture ->
            val rejected = java.util.concurrent.atomic.AtomicBoolean(true)
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val first = previousPage && request.path == "/list"
                    val next = if (first) "/second" else if (rejected.get()) "/list" else ""
                    return MockResponse().setResponseCode(if (first || !rejected.get()) 200 else 403)
                        .setBody("""{"next":"$next"}""")
                }
            }
            fixture.source { raw -> JsonObject(rules(raw, "@js:[]") +
                ("ruleExplore" to JsonObject(fields("@js:[]") +
                    ("nextPageUrl" to JsonPrimitive("@js:JSON.parse(result).next")))))
            }.use { source ->
                val pages = source.openDiscovery("rejected-cycle").openPages("/list", emptyMap())
                val cursor = if (previousPage) pages.page().nextCursor.also { assertNotNull(it) } else null
                val failure = runCatching { pages.page(cursor) }.exceptionOrNull() as? SourceContentException
                assertEquals(ContentError.Network, failure?.code)
                assertEquals(403, failure?.httpStatus)
                rejected.set(false)
                val recovered = pages.page(cursor)
                assertTrue(recovered.books.isEmpty())
                assertNull(recovered.nextCursor)
            }
        }
    }

    @Test fun responseHookRetriesUseTheFinalResponseStatus() = runBlocking {
        for (retryStatus in listOf(200, 403)) RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val status = if (request.path == "/retry") retryStatus else if (retryStatus == 200) 403 else 200
                    return MockResponse().setResponseCode(status).setBody(if (status == 200)
                        "<li><a href='/book/1'><h2>Recovered</h2></a></li>" else "denied")
                }
            }
            fixture.source { rules(it, "@js:java.getElements('li')", "java.connect('/retry')") }.use { source ->
                val result = runCatching { source.openSearchPages("book").page(1) }
                if (retryStatus == 200) assertEquals("Recovered", result.getOrThrow().books.single().title)
                else {
                    val failure = result.exceptionOrNull() as SourceContentException
                    assertEquals(ContentError.Network, failure.code)
                    assertEquals(403, failure.httpStatus)
                }
            }
        }
    }

    @Test fun anExplicitContinuationCanRecoverAnEmptyErrorResponse() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/list")
                    MockResponse().setResponseCode(404).setBody("placeholder") else original.dispatch(request)
            }
            fixture.source { raw -> JsonObject(rules(raw, "@js:java.getElements('li')") +
                ("ruleExplore" to JsonObject(fields("@js:java.getElements('li')") +
                    ("nextPageUrl" to JsonPrimitive("@js:result==='placeholder' ? '/search' : ''")))))
            }.use { source ->
                val pages = source.openDiscovery("continuation").openPages("/list", emptyMap())
                val first = pages.page(1)
                assertTrue(first.books.isEmpty())
                assertNotNull(first.nextCursor)
                assertEquals("Same title", pages.page(first.nextCursor).books.single().title)
            }
        }
    }

    @Test fun rejectedResponsesDoNotReplaceSyntaxPermissionOrCancellationFailures() = runBlocking {
        for ((script, expected) in listOf("@js:var = ;" to ContentError.InvalidRule,
            "@js:java.ajax('https://unapproved.invalid/list')" to ContentError.PermissionDenied)) {
            RuleSourceFixture().use { fixture ->
                respond(fixture, 403)
                fixture.source { rules(it, script) }.use { source ->
                    val failure = runCatching { source.search("book") }.exceptionOrNull() as SourceContentException
                    assertEquals(expected, failure.code)
                    assertNull(failure.httpStatus)
                }
            }
        }
        RuleSourceFixture().use { fixture ->
            respond(fixture, 403)
            fixture.beforeRun = { task, _ ->
                if ((task as? ExecutionTask.Rule)?.location?.field == "ruleSearch.bookList") throw CancellationException("cancelled")
            }
            fixture.source { rules(it, "@js:[]") }.use { source ->
                assertTrue(runCatching { source.search("book") }.exceptionOrNull() is CancellationException)
            }
        }
    }

    @Test fun recoveredListsDoNotHideLaterBookFieldErrors() = runBlocking {
        for (field in listOf("name", "nextPageUrl")) RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/list")
                    MockResponse().setResponseCode(404).setBody("placeholder") else original.dispatch(request)
            }
            fixture.source { raw -> JsonObject(rules(raw, "<js>java.ajax('/search')</js>li") +
                ("ruleSearch" to JsonObject(fields("<js>java.ajax('/search')</js>li") +
                    (field to JsonPrimitive("@js:throw 'broken rule'")))))
            }.use { source ->
                val failure = runCatching { source.openSearchPages("book").page(1) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.InvalidRule, failure.code)
                assertEquals("ruleSearch.$field", failure.field)
                assertNull(failure.httpStatus)
            }
        }
    }
}
