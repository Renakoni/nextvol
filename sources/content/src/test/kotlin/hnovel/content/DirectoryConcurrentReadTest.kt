package hnovel.content

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DirectoryConcurrentReadTest {
    private fun definition(raw: JsonObject, next: String = "a.next@href", header: String? = null) = JsonObject(raw +
        ("ruleToc" to buildJsonObject {
            put("chapterList", "li"); put("chapterName", "a@text")
            put("chapterUrl", "@js:var doc=org.jsoup.Jsoup.parse(result);var link=doc.select('a').first();link.attr('href');")
            put("nextTocUrl", next)
        }) + if (header == null) emptyMap() else mapOf("header" to JsonPrimitive(header)))

    private fun html(page: Int, count: Int = 21, next: String = "/toc/${page + 1}") = buildString {
        append("<li><a href='/c/$page'>Chapter $page</a></li>")
        if (page < count) append("<a class='next' href='$next'>Next</a>")
        if (page == 1) {
            for (i in 2..6) append("<a class='page' href='/toc/$i'>$i</a>")
            append("<a class='last' href='/toc/$count'>Last</a>")
        }
    }

    @Test fun recognizedPaginationOverlapsEightReadsAndMergesInSourceOrder() = runBlocking {
        for (style in listOf("next", "list", "select")) RuleSourceFixture().use { fixture ->
            val entered = CountDownLatch(8)
            val release = CountDownLatch(1)
            val requested = java.util.Collections.synchronizedList(mutableListOf<Int>())
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    requested += page
                    if (page in 2..9) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    val body = when {
                        page == 1 && style == "list" -> html(1) + (2..21).joinToString("") { "<a class='all' href='/toc/$it'>$it</a>" }
                        page == 1 && style == "select" -> html(1).substringBefore("<a class='page'") +
                            "<select>" + (1..21).joinToString("") { "<option value='/toc/$it'>第${it}页</option>" } + "</select>"
                        else -> html(page)
                    }
                    return MockResponse().setBody(body)
                }
            }
            fixture.source { definition(it, if (style == "list") "a.all@href" else "a.next@href") }.use { source ->
                val pending = async { source.directory(fixture.server.url("/book/one").toString()) }
                try {
                    withContext(Dispatchers.IO) { assertTrue("Eight requests must enter before any returns", entered.await(10, TimeUnit.SECONDS)) }
                    assertEquals((1..9).toSet(), requested.toSet())
                } finally { release.countDown() }
                assertEquals((1..21).map { "Chapter $it" }, pending.await().map { it.title })
                assertEquals((1..21).toList(), requested.sorted())
            }
        }
    }

    @Test fun changedContinuationDiscardsSpeculationAndFollowsTheRule() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/changed") return MockResponse().setBody(html(30, 30))
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    return MockResponse().setBody(html(page, next = if (page == 2) "/changed" else "/toc/${page + 1}"))
                }
            }
            fixture.source { definition(it) }.use { source ->
                assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 30"),
                    source.directory(fixture.server.url("/book/one").toString()).map { it.title })
            }
        }
    }

    @Test fun missingPrefetchedPageRetainsOnlyTheVerifiedPrefixAndRetryCanComplete() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            var fail = true
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    return if (fail && page == 4) MockResponse().setResponseCode(503) else MockResponse().setBody(html(page))
                }
            }
            fixture.source { definition(it) }.use { source ->
                val id = fixture.server.url("/book/one").toString()
                val failure = runCatching { source.directory(id) }.exceptionOrNull()
                assertTrue(failure.toString(), failure is PartialDirectoryException)
                assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), (failure as PartialDirectoryException).chapters.map { it.title })
                fail = false
                assertEquals(21, source.directory(id).size)
            }
        }
    }

    @Test fun statefulHeaderKeepsRequestsSequential() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            val prepared = AtomicInteger()
            fixture.beforeRun = { task, _ -> if (task is hnovel.execution.ExecutionTask.Rule && task.location.field == "header") prepared.incrementAndGet() }
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    return MockResponse().setBody(html(page))
                }
            }
            fixture.source { definition(it, header = "@js:JSON.stringify({'X-State':book.getVariable('n')||'first'})") }.use { source ->
                assertEquals(21, source.directory(fixture.server.url("/book/one").toString()).size)
                assertTrue(prepared.get() >= 21)
            }
        }
    }

    @Test fun cancellationStopsTheWindowAndRetryDoesNotReuseCancelledPages() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            val entered = CountDownLatch(8)
            val release = CountDownLatch(1)
            var hold = true
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    if (hold && page in 2..9) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    return MockResponse().setBody(html(page))
                }
            }
            fixture.source { definition(it) }.use { source ->
                val id = fixture.server.url("/book/one").toString()
                val pending = async { source.directory(id) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
                    withTimeout(3000) { pending.cancelAndJoin() }
                } finally { hold = false; release.countDown() }
                assertEquals(21, source.directory(id).size)
            }
        }
    }

    @Test fun changedCookiesStopPrefetchBeforeContinuingTheDirectory() = runBlocking {
        val sequentialReads = AtomicInteger()
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            fixture.beforeRun = { task, _ ->
                if (task is hnovel.execution.ExecutionTask.Rule && task.location.field == "ruleToc.chapterList" &&
                    task.rule.contains("request.prepare")) sequentialReads.incrementAndGet()
            }
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    return MockResponse().setBody(html(page)).also {
                        if (page == 2) it.setHeader("Set-Cookie", "session=changed; Path=/")
                    }
                }
            }
            fixture.source { JsonObject(definition(it) + ("enabledCookieJar" to JsonPrimitive(true))) }.use { source ->
                assertEquals(21, source.directory(fixture.server.url("/book/one").toString()).size)
                assertEquals(20, sequentialReads.get()) // First page and pages 3..21 use the ordinary path.
            }
        }
    }

    @Test fun oversizedPrefetchFallsBackToTheOrdinaryPageBudgetWithoutLosingChapters() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            val largeReads = AtomicInteger()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.path?.removePrefix("/toc/")?.toIntOrNull() ?: return original.dispatch(request)
                    val padding = if (page == 3) { largeReads.incrementAndGet(); "<!--" + "x".repeat(2 * 1024 * 1024) + "-->" } else ""
                    return MockResponse().setBody(html(page) + padding)
                }
            }
            fixture.source { definition(it) }.use { source ->
                assertEquals((1..21).map { "Chapter $it" }, source.directory(fixture.server.url("/book/one").toString()).map { it.title })
                assertEquals(2, largeReads.get())
            }
        }
    }
}
