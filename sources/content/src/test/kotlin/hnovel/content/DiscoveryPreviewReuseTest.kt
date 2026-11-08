package hnovel.content

import hnovel.execution.ExecutionTask
import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPreviewReuseTest {
    private val html = (1..30).joinToString("") { "<li><a href='/book/$it'><h2>Book $it</h2></a><img src='/covers/$it.jpg'></li>" }
    private fun definition(raw: JsonObject, extra: Map<String, JsonElement> = emptyMap()) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive("Books::/search"),
        "ruleExplore" to buildJsonObject {
            put("bookList", "@js:java.getElements('li')"); put("name", "h2@text"); put("bookUrl", "a@href")
            put("coverUrl", "img@src")
        }
    ) + extra)
    private fun pages(fixture: RuleSourceFixture, body: String = html, status: Int = 200) {
        fixture.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(status).setBody(body)
        }
    }
    private fun session(fixture: RuleSourceFixture, source: RuleSource) = fixture.broker.open(
        SourceScope("rules", source.definition.sourceId, source.definition.profile),
        listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))

    @Test fun previewDocumentServesAResumedFeedAndTheEntireListAcrossPageSessions() = runBlocking {
        for (native in listOf(false, true)) RuleSourceFixture(browser = BrowserExecutor { owner, request, _, guard, route ->
            owner.executeHttp(request, guard, route)
        }).use { fixture ->
            pages(fixture)
            var lists = 0
            var batches = 0
            fixture.beforeRun = { task, _ ->
                if (task is ExecutionTask.Rule && task.location.field == "ruleExplore.bookList") lists++
                if (task is ExecutionTask.BookOverviews) batches++
            }
            fixture.source { definition(it, mapOf("browserRead" to JsonPrimitive(native))) }.use { source ->
                val preview = source.openDiscovery("home").preview("/search", emptyMap()).books
                assertEquals(6, preview.size)
                assertEquals((1..6).map { fixture.server.url("/covers/$it.jpg").toString() }, preview.map { it.coverUrl })
                assertEquals(6, source.openDiscovery("resume").preview("/search", emptyMap()).books.size)
                val books = source.openDiscovery("more").openPages("/search", emptyMap()).page(1).books
                assertEquals((1..30).map { "Book $it" }, books.map { it.title })
                assertEquals((1..30).map { fixture.server.url("/book/$it").toString() }, books.map { it.id })
                assertEquals((1..30).map { fixture.server.url("/covers/$it.jpg").toString() }, books.map { it.coverUrl })
                assertEquals("All callers re-evaluate their own rule state", 3, lists)
                assertEquals("Covers share the existing batches of eight rows", 6, batches)
                assertEquals("A complete document, not six parsed books, is reused", 1, fixture.server.requestCount)
            }
        }
    }

    @Test fun jsonCoverOptionsSurviveWhileMissingCoversRemainOptional() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val options = ", {\"headers\":{\"Referer\":\"https://www.pixiv.net/\"}}"
            pages(fixture, buildJsonArray {
                add(buildJsonObject { put("name", "With cover"); put("id", "/one"); put("cover", "/cover.jpg$options") })
                add(buildJsonObject { put("name", "Without cover"); put("id", "/two"); put("cover", "") })
                add(buildJsonObject { put("name", "Invalid cover"); put("id", "/three"); put("cover", "javascript:void(0)") })
            }.toString())
            fixture.source { definition(it, mapOf("ruleExplore" to buildJsonObject {
                put("bookList", "$.*"); put("name", "name"); put("bookUrl", "id"); put("coverUrl", "cover")
            })) }.use { source ->
                val books = source.openDiscovery("covers").openPages("/search", emptyMap()).page(1).books
                assertEquals(listOf(fixture.server.url("/cover.jpg").toString() + options, "", ""), books.map { it.coverUrl })
                assertEquals("Listing covers must not load book details", 1, fixture.server.requestCount)
            }
        }
    }

    @Test fun jsonResponsesRetainAllRowsAndNormalFirstListsDoNotPopulateTheHandoff() = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture, buildJsonArray { repeat(30) { i -> add(buildJsonObject { put("id", "/book/$i"); put("name", "Book $i") }) } }.toString())
            fixture.source { definition(it, mapOf("ruleExplore" to buildJsonObject {
                put("bookList", "$.*"); put("name", "name"); put("bookUrl", "id")
            })) }.use { source ->
                repeat(2) { assertEquals(30, source.openDiscovery("list-$it").openPages("/search", emptyMap()).page(1).books.size) }
                assertEquals(2, fixture.server.requestCount)
                source.openDiscovery("home").preview("/search", emptyMap())
                assertEquals(30, source.openDiscovery("list").openPages("/search", emptyMap()).page(1).books.size)
                assertEquals(3, fixture.server.requestCount)
            }
        }
    }

    @Test fun requestPageFilterAndEnvironmentChangesRequireNewDocuments() = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture)
            fixture.source { definition(it) }.use { source ->
                val first = source.openDiscovery("home", mapOf("mode" to "old"))
                val target = "/search?mode={{infoMap.mode}}&p={{page}}"
                first.preview(target, emptyMap())
                val list = source.openDiscovery("list", mapOf("mode" to "old")).openPages(target, emptyMap())
                list.page(1)
                assertEquals(1, fixture.server.requestCount)
                list.page(2)
                assertEquals(2, fixture.server.requestCount)
                source.openDiscovery("filter", mapOf("mode" to "new")).openPages(target, emptyMap()).page(1)
                source.openDiscovery("theme", mapOf("mode" to "old"), RuleDiscoveryEnvironment(themeMode = "1"))
                    .openPages(target, emptyMap()).page(1)
                source.openDiscovery("request", mapOf("mode" to "old")).openPages("/search?other=1", emptyMap()).page(1)
                assertEquals(5, fixture.server.requestCount)
            }
        }
    }

    @Test fun cookiesLoginHeadersRefreshAndRoutesInvalidateTheDocumentIdentity() = runBlocking {
        var route = SourceNetworkRoute.systemDefault()
        RuleSourceFixture(route = SourceRouteProvider { route }).use { fixture ->
            pages(fixture)
            fixture.source { definition(it) }.use { source ->
                val owner = session(fixture, source)
                suspend fun preview() = source.openDiscovery("home").preview("/search", emptyMap())
                suspend fun full() = source.openDiscovery("more").openPages("/search", emptyMap()).page(1)
                preview(); full(); assertEquals(1, fixture.server.requestCount)
                owner.setCookie(fixture.server.url("/").toString(), "account=next")
                full(); assertEquals(2, fixture.server.requestCount)
                preview()
                owner.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS, "{\"Authorization\":\"Bearer next\"}"))
                full(); assertEquals(4, fixture.server.requestCount)
                preview()
                source.openDiscovery("refresh").catalog(refresh = true, homepage = true)
                full(); assertEquals(6, fixture.server.requestCount)
                preview()
                route = SourceNetworkRoute.systemDefault()
                full(); assertEquals(8, fixture.server.requestCount)
            }
        }
    }

    @Test fun nativeOwnedCookiesInvalidateResponsesWithoutBeingSeededBackIntoChromium() = runBlocking {
        RuleSourceFixture().use { fixture ->
            pages(fixture)
            fixture.source { definition(it) }.use { source ->
                val owner = session(fixture, source)
                val url = fixture.server.url("/").toString()
                source.openDiscovery("home").preview("/search", emptyMap())
                owner.updateNativeBrowserCookies(url, listOf("account=next; Path=/; HttpOnly"))
                assertTrue(owner.nativeBrowserCookies(url).isEmpty())
                assertTrue(owner.responseCookies(url).any { it.startsWith("account=next;") })
                assertEquals(30, source.openDiscovery("full").openPages("/search", emptyMap()).page(1).books.size)
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun accountAndRevisionReplacementCannotInheritPreviewDocuments() = runBlocking {
        for (account in listOf(false, true)) RuleSourceFixture().use { fixture ->
            pages(fixture)
            val old = fixture.source { definition(it) }
            old.openDiscovery("home").preview("/search", emptyMap())
            old.close()
            val next = if (account) {
                val definition = old.definition
                val owner = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile, 1),
                    listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
                val identity = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules", 1)
                createRuleSource(definition, identity, fixture.authority, owner, fixture.runner)
            } else fixture.source { definition(it, mapOf("bookSourceComment" to JsonPrimitive("new revision"))) }
            next.use { it.openDiscovery("more").openPages("/search", emptyMap()).page(1) }
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test fun emptyFailedOversizePostAndLoginCheckedResponsesAreNotReused() = runBlocking {
        for (case in listOf("empty", "status", "oversize", "post", "login")) RuleSourceFixture().use { fixture ->
            pages(fixture, when (case) { "empty" -> "<ul></ul>"; "oversize" -> html + " ".repeat(256 * 1024); else -> html },
                if (case == "status") 500 else 200)
            fixture.source { definition(it, if (case == "login") mapOf("loginCheckJs" to JsonPrimitive("result")) else emptyMap()) }.use { source ->
                val target = if (case == "post") "/search,{\"method\":\"POST\",\"body\":\"x=1\"}" else "/search"
                source.openDiscovery("home").preview(target, emptyMap())
                source.openDiscovery("more").openPages(target, emptyMap()).page(1)
                assertEquals(case, 2, fixture.server.requestCount)
            }
        }
    }

    @Test fun redirectsAndCustomBrowserExtractionAreNotReused() = runBlocking {
        for (redirect in listOf(false, true)) RuleSourceFixture(browser = BrowserExecutor { owner, request, _, guard, route ->
            owner.executeHttp(request, guard, route)
        }).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (redirect && request.path == "/search")
                    MockResponse().setResponseCode(302).setHeader("Location", "/final") else MockResponse().setBody(html)
            }
            fixture.source { definition(it) }.use { source ->
                val target = if (redirect) "/search" else "/search,{\"webView\":true,\"webJs\":\"document.body.innerHTML\"}"
                source.openDiscovery("home").preview(target, emptyMap())
                source.openDiscovery("more").openPages(target, emptyMap()).page(1)
                assertEquals(if (redirect) 4 else 2, fixture.server.requestCount)
            }
        }
    }

    @Test fun cancellationAndParserCookieWritesCannotPublishReusableDocuments() = runBlocking {
        for (cancel in listOf(false, true)) RuleSourceFixture().use { fixture ->
            pages(fixture)
            fixture.source { definition(it) }.use { source ->
                fixture.afterRun = { task -> if (task is ExecutionTask.BookOverviews) {
                    if (cancel) currentCoroutineContext().cancel()
                    else session(fixture, source).setCookie(fixture.server.url("/").toString(), "account=changed")
                } }
                val pending = async { source.openDiscovery("home").preview("/search", emptyMap()) }
                pending.join()
                assertEquals(cancel, pending.isCancelled)
                fixture.afterRun = {}
                source.openDiscovery("more").openPages("/search", emptyMap()).page(1)
                assertEquals(2, fixture.server.requestCount)
            }
        }
    }

    @Test fun ttlCapacityAndRouteRetirementBoundHandoffMemory() {
        val cache = DiscoveryPreviewDocuments(DiscoveryPreviewDocuments.Pool())
        val route = SourceNetworkRoute.systemDefault()
        fun key(i: Int) = DiscoveryPreviewDocuments.Key(BrokerRequest("content", "https://fixture.test/$i"), route.reference,
            emptyList(), emptyMap(), null)
        val document = PageDocument(html, "https://fixture.test/list", successfulResponse = true)
        repeat(5) { cache.put(key(it), document, null, generation = 0, now = 0) }
        assertNull(cache.get(key(0), now = 1))
        assertNotNull(cache.get(key(1), now = 59_999_999_999))
        assertNull(cache.get(key(1), now = 60_000_000_000))
        cache.put(key(5), document.copy(body = "x".repeat(256 * 1024 + 1)), null, generation = 0, now = 0)
        assertNull(cache.get(key(5), now = 1))
        route.invalidate()
        assertNull(cache.get(key(2), now = 1))
        cache.put(key(6), document, null, generation = 0, now = 1)
        assertNull(cache.get(key(6), now = 2))
        assertFalse("Ephemeral response eligibility must not change stored book records",
            Json.encodeToString(PageDocument.serializer(), document).contains("successfulResponse"))
    }
}
