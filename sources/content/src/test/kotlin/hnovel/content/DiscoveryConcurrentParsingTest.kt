package hnovel.content

import hnovel.execution.ExecutionTask
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryConcurrentParsingTest {
    private fun definition(raw: JsonObject, extra: Map<String, JsonElement> = emptyMap()) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive("Books::/list"),
        "ruleExplore" to buildJsonObject {
            put("bookList", "@js:java.getElements('li')"); put("name", "h2@text"); put("bookUrl", "a@href")
        }
    ) + extra)

    private fun pages(fixture: RuleSourceFixture) {
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                "<li><h2>${request.path}</h2><a href='/book/one'>Read</a></li>")
        }
    }

    @Test fun slowReadOnlyParsingDoesNotBlockAnotherPreview() = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture)
            val parsing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.bookList" && task.baseUrl.endsWith("/one")) {
                    assertTrue(task.readOnly)
                    parsing.complete(Unit)
                    release.await()
                }
            }
            fixture.source { definition(it) }.use { source ->
                val previews = requireNotNull(source.openDiscovery("parallel-parse")
                    .concurrentPreviews(listOf("/one", "/two"), emptyMap()))
                val slow = async { previews.first().page(1) }
                val fast = try {
                    withTimeout(5000) { parsing.await() }
                    withTimeoutOrNull(3000) { previews.last().page(1) }
                } finally { release.complete(Unit) }
                assertEquals("/one", slow.await().books.single().title)
                assertNotNull("An independent preview must finish while the first parser is blocked", fast)
                assertEquals("/two", fast!!.books.single().title)
            }
        }
    }

    @Test fun statefulAndUnprovenResponseRulesKeepTheWholeParsingTurnInOrder() = runBlocking {
        val extras = listOf(
            mapOf("jsLib" to JsonPrimitive("var shared={n:0}")),
            mapOf("loginCheckJs" to JsonPrimitive("result")),
            mapOf("ruleExplore" to buildJsonObject {
                put("bookList", "@js:source.put('x','y');java.getElements('li')")
                put("name", "h2@text"); put("bookUrl", "a@href")
            }),
            mapOf("ruleExplore" to buildJsonObject {
                put("bookList", "li"); put("name", "h2@text@js:book.putVariable('x','y');result")
                put("bookUrl", "a@href")
            }),
            mapOf("ruleExplore" to buildJsonObject {
                put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href")
                put("nextPageUrl", "@js:source.put('next','saved');''")
            })
        )
        for (extra in extras) RuleSourceFixture().use { fixture ->
            pages(fixture)
            fixture.onPrepareIndependent = { _, _ -> fail("A stateful group must not prepare independent parsers") }
            val firstParsing = CompletableDeferred<Unit>()
            val secondParsing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.bookList") {
                    assertFalse(task.readOnly)
                    if (task.baseUrl.endsWith("/one")) { firstParsing.complete(Unit); release.await() }
                    else secondParsing.complete(Unit)
                }
            }
            fixture.source { definition(it, extra) }.use { source ->
                val previews = requireNotNull(source.openDiscovery("stateful-parse")
                    .concurrentPreviews(listOf("/one", "/two"), emptyMap()))
                val first = async { previews.first().page(1) }
                withTimeout(5000) { firstParsing.await() }
                val second = async { previews.last().page(1) }
                val overlapped = try { withTimeoutOrNull(300) { secondParsing.await() } }
                    finally { release.complete(Unit) }
                assertEquals(listOf("/one", "/two"), awaitAll(first, second).map { it.books.single().title })
                assertNull("Stateful parsing must remain ordered: ${extra.keys}", overlapped)
            }
        }
    }

    @Test fun redirectedDetailsKeepStatefulParsingSerialized() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path in listOf("/one", "/two"))
                    MockResponse().setResponseCode(302).setHeader("Location", "/detail${request.path}")
                else MockResponse().setBody("<h2>${request.path}</h2>")
            }
            val firstParsing = CompletableDeferred<Unit>()
            val secondParsing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleBookInfo.name") {
                    assertFalse(task.readOnly)
                    if (task.baseUrl.endsWith("/one")) { firstParsing.complete(Unit); release.await() }
                    else secondParsing.complete(Unit)
                }
            }
            fixture.source { definition(it, mapOf(
                "bookUrlPattern" to JsonPrimitive(".*/detail/(one|two)"),
                "ruleBookInfo" to buildJsonObject {
                    put("name", "h2@text@js:book.putVariable('field','saved');result")
                }
            )) }.use { source ->
                val previews = requireNotNull(source.openDiscovery("redirected-details")
                    .concurrentPreviews(listOf("/one", "/two"), emptyMap()))
                val first = async { previews.first().page(1) }
                withTimeout(5000) { firstParsing.await() }
                val second = async { previews.last().page(1) }
                val overlapped = try { withTimeoutOrNull(300) { secondParsing.await() } }
                    finally { release.complete(Unit) }
                val completed = awaitAll(first, second)
                assertNull("A redirected detail must retain its whole stateful parsing turn", overlapped)
                assertEquals(listOf("/detail/one", "/detail/two"), completed.map { it.books.single().title })
            }
        }
    }

    @Test fun latePreviewCannotReplaceFullInformationLoadedDuringParsing() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/one")
                    MockResponse().setBody("<li><h2>Preview</h2><a href='/book/one'>Read</a></li>")
                else original.dispatch(request)
            }
            val parsing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.bookList") {
                    parsing.complete(Unit); release.await()
                }
            }
            fixture.source { definition(it) }.use { source ->
                val preview = requireNotNull(source.openDiscovery("preview-information")
                    .concurrentPreviews(listOf("/one", "/two"), emptyMap())).first()
                val pending = async { preview.page(1) }
                val id = fixture.server.url("/book/one").toString()
                try {
                    withTimeout(5000) { parsing.await() }
                    assertEquals("Same title", withTimeout(5000) { source.information(id) }.title)
                } finally { release.complete(Unit) }
                assertEquals("Preview", pending.await().books.single().title)
                val requests = fixture.server.requestCount
                assertEquals(listOf("Volume one", "One", "Two"), source.directory(id).map { it.title })
                assertEquals("Full information and prefetched directory must survive the late preview", requests, fixture.server.requestCount)
            }
        }
    }

    @Test fun retiringSourceDuringIndependentParsingRejectsTheLateResult() = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture)
            val parsing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.afterRun = { task ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.bookList") {
                    parsing.complete(Unit); release.await()
                }
            }
            fixture.source { definition(it) }.use { source ->
                val preview = requireNotNull(source.openDiscovery("retired-parse")
                    .concurrentPreviews(listOf("/one", "/two"), emptyMap())).first()
                val pending = async { runCatching { preview.page(1) } }
                try { withTimeout(5000) { parsing.await() }; source.close() }
                finally { release.complete(Unit) }
                val failure = pending.await().exceptionOrNull()
                assertTrue(failure.toString(), failure is SourceContentException)
                assertEquals(ContentError.Unavailable, (failure as SourceContentException).code)
            }
        }
    }
}
