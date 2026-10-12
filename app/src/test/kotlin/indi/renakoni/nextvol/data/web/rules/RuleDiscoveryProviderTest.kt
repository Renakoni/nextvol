package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import com.github.michaelbull.result.*
import hnovel.content.RuleSourceFixture
import hnovel.content.DiscoveryCatalogFixtures
import hnovel.content.RuleDiscoverySession
import hnovel.content.RuleListPage
import hnovel.content.SourceContentException
import hnovel.content.ContentError
import hnovel.content.SourceVerification
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleDiscoveryProviderTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun declaredRankingsKeepBooksAndMoreLinksWithoutBecomingCategories() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("""[{"title":"玄幻","url":"/search?genre=fantasy"},
                    {"title":"月榜","url":"/search?rank=month","showInCategories":false}]"""),
                "homepageModules" to JsonPrimitive("""[{"key":"month","type":"ranking","title":"月榜","kindTitle":"月榜"}]"""),
                "ruleExplore" to raw.getValue("ruleSearch")
            )) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertEquals(listOf("玄幻"), provider.catalog().get()!!.categories.map { it.title })
                val section = provider.feed().get()!!.single()
                assertEquals("月榜", section.title)
                assertEquals(1, section.books.size)
                assertEquals("/search?rank=month", section.more)
                assertEquals(1, provider.page(DiscoveryRequest(section.more!!)).get()!!.books.size)
            }
        }
    }

    @Test fun feedAndResultPagesRetainCoversForBothBatchedAndDynamicRules() = runBlocking {
        for (dynamic in listOf(false, true)) RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse()
                    .setBody("<li><a href='/book/one'><h2>Book</h2></a><b>Author</b><img src='/cover.png'></li>")
            }
            fixture.source { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("Books::/search"),
                "homepageModules" to JsonPrimitive("""[{"key":"books","type":"card","title":"Books","url":"/search"}]"""),
                "ruleExplore" to buildJsonObject {
                    put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href")
                    put("author", if (dynamic) "@js:java.getString('b@text')" else "b@text")
                    put("coverUrl", "img@src")
                }
            )) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val preview = provider.feed().get()!!.single().books.single()
                val result = provider.page(DiscoveryRequest("/search")).get()!!.books.single()
                for (book in listOf(preview, result)) {
                    assertEquals(fixture.server.url("/cover.png").toString(), book.coverUrl)
                    assertEquals(fixture.server.url("/book/one").toString(), book.remoteId)
                    if (dynamic) assertEquals("Author", book.author)
                }
                assertTrue("At most one list request per entry point", fixture.server.requestCount in 1..2)
                repeat(fixture.server.requestCount) {
                    val request = requireNotNull(fixture.server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals("Covers must not trigger book information requests", "/search", request.requestUrl!!.encodedPath)
                }
            }
        }
    }

    @Test fun httpFailuresStayWithTheirSectionsAndClearAfterPreviewAndPageRecovery() = runBlocking {
        for (hook in listOf("", "result")) RuleSourceFixture().use { fixture ->
            val recovered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    val status = if (path in recovered) 200 else when (path) { "/bad" -> 400; "/empty" -> 403; else -> 200 }
                    val body = when (status) {
                        400 -> """{"error":true,"body":[]}"""
                        403 -> """{"error":"empty","body":[]}"""
                        else -> """{"body":{"page":{"items":"<li><h2>Recovered</h2><a href='/book/one'>Read</a></li>"}}}"""
                    }
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(status).setBody(body)
                }
            }
            val rule = fixture.source { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("Bad::/bad\nEmpty::/empty\nGood::/good"),
                "homepageModules" to JsonPrimitive("""[{"key":"bad","type":"ranking","title":"Bad","url":"/bad"},
                    {"key":"empty","type":"ranking","title":"Empty","url":"/empty"},
                    {"key":"good","type":"ranking","title":"Good","url":"/good"}]"""),
                "loginCheckJs" to JsonPrimitive(hook),
                "ruleExplore" to buildJsonObject {
                    put("bookList", "<js>var data=JSON.parse(result);data.error==='empty' ? [] : data.body.page.items</js>li")
                    put("name", "h2@text"); put("bookUrl", "a@href")
                }
            )) }
            val id = Identifier("rules", rule.definition.sourceId)
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rule), SourceMetadata(WebDataSourceItem(id, "Fixture", "Tests"),
                setOf(SourceCapability.Categories, SourceCapability.Explore)))
            try {
                val discovery = (registry.resolve(id) as SourceResolution.Ready).runtime.discovery!!.forSession("http")
                val sections = discovery.feed().get()!!.associateBy { it.title }
                val bad = sections.getValue("Bad")
                val empty = sections.getValue("Empty")
                assertEquals(DiscoveryError.Network, bad.previewFailure!!.error)
                assertEquals(400, bad.httpStatus)
                assertEquals(hnovel.execution.FailureCode.ScriptRuntime, bad.diagnosticFailure?.code)
                assertEquals(DiscoveryError.Network, empty.previewFailure!!.error)
                assertEquals(403, empty.httpStatus)
                assertNull(empty.diagnosticFailure)
                assertEquals("Recovered", sections.getValue("Good").books.single().title)
                assertNull(sections.getValue("Good").httpStatus)
                assertNull(discovery.httpStatus)

                recovered += "/bad"
                val retried = discovery.preview(bad.id).get()!!
                assertNull(retried.httpStatus)
                assertNull(retried.previewFailure)
                assertNull(retried.diagnosticFailure)
                val pages = discovery.open(empty.more!!)
                assertEquals(DiscoveryError.Network, pages.loadMore().getError())
                assertEquals(403, pages.httpStatus)
                recovered += "/empty"
                assertEquals("Recovered", pages.loadMore().get()!!.books.single().title)
                assertNull(pages.httpStatus)
                assertNull(discovery.preview(empty.id).get()!!.httpStatus)

                val search = indi.renakoni.nextvol.data.explore.searchFailure(
                    SourceContentException(ContentError.Network, "ruleSearch.bookList", httpStatus = 403))
                assertEquals(DiscoveryError.Network, search.error)
                assertEquals(403, search.httpStatus)
            } finally { registry.unregister(id) }
        }
    }

    @Test fun safeCallDiagnosticsFollowTheirPreviewAndResultSessionAndClearAfterRecovery() = runBlocking {
        RuleSourceFixture().use { fixture ->
            var fail = true
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse()
                    .setBody("<li><h2>${if (fail) "fail" else "Recovered"}</h2><a href='/book/one'>Read</a></li>")
            }
            val rule = fixture.source { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("热门推荐::/search"),
                "ruleExplore" to JsonObject(raw.getValue("ruleSearch").jsonObject + ("name" to
                    JsonPrimitive("h2@text@js:if(result==='fail')cookie.getCookie(baseUrl,'PRIVATE_COOKIE');result")))
            )) }
            val id = Identifier("rules", rule.definition.sourceId)
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rule), SourceMetadata(WebDataSourceItem(id, "Fixture", "Tests"),
                setOf(SourceCapability.Categories, SourceCapability.Explore)))
            try {
                val discovery = (registry.resolve(id) as SourceResolution.Ready).runtime.discovery!!.forSession("diagnostics")
                val section = discovery.feed().get()!!.single()
                assertEquals(DiscoveryError.PermissionDenied, section.previewFailure!!.error)
                val detail = section.diagnosticFailure!!
                assertEquals(hnovel.rules.ScriptHostCall("cookie.getCookie", 2,
                    List(2) { hnovel.rules.ScriptArgumentType.String }), detail.hostCall)
                assertEquals("ruleExplore.name", detail.ruleError!!.location.field)
                assertNull(discovery.diagnosticFailure)
                val results = discovery.open(section.more!!)
                assertEquals(DiscoveryError.PermissionDenied, results.loadMore().getError())
                assertEquals(detail, results.diagnosticFailure)
                val search = indi.renakoni.nextvol.data.explore.searchFailure(
                    SourceContentException(ContentError.InvalidRule, "ruleExplore.name", diagnostic = detail))
                assertEquals(detail, search.diagnostic)
                assertFalse(detail.toString().contains("PRIVATE"))
                fail = false
                assertEquals("Recovered", results.loadMore().get()!!.books.single().title)
                assertNull(results.diagnosticFailure)
                val restored = discovery.feed().get()!!.single()
                assertNull(restored.previewFailure)
                assertNull(restored.diagnosticFailure)
                assertEquals("Recovered", restored.books.single().title)
            } finally { registry.unregister(id) }
        }
    }

    @Test fun resolvedGenreOnlyCatalogLeavesDiscoveryButFailuresAndEmptyResponsesKeepItsTab() = runBlocking {
        RuleSourceFixture().use { fixture ->
            var response = "[]"
            var failed = true
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(if (failed) 503 else 200).setBody(if (failed) "[{" else response)
            }
            val rule = fixture.source { raw -> JsonObject(definition(raw) +
                ("exploreUrl" to JsonPrimitive("@js:java.ajax('${fixture.server.url("/catalog")}')"))) }
            val id = Identifier("rules", rule.definition.sourceId)
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rule), SourceMetadata(WebDataSourceItem(id, "Fixture", "Tests"),
                setOf(SourceCapability.Categories, SourceCapability.Explore)))
            fun visible(capability: SourceCapability) = indi.renakoni.nextvol.ui.home.discovery.discoverySources(registry.sources.value, capability)
            try {
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val discovery = runtime.discovery!!.forSession("catalog")
                assertEquals(1, visible(SourceCapability.Explore).size)
                assertNotNull(discovery.homepageCatalog().getError())
                assertEquals(1, visible(SourceCapability.Explore).size)
                failed = false
                assertNotNull(discovery.homepageCatalog(true).get())
                assertEquals(1, visible(SourceCapability.Explore).size)
                assertEquals(DiscoveryError.InvalidResponse, discovery.feed().getError())
                response = """[{"title":"玄幻","url":"/fantasy"},{"title":"言情","url":"/romance"}]"""
                val generation = registry.sources.value.single().generation
                assertEquals(2, discovery.homepageCatalog(true).get()!!.categories.size)
                assertTrue(visible(SourceCapability.Explore).isEmpty())
                assertEquals(1, visible(SourceCapability.Categories).size)
                assertEquals(generation, registry.sources.value.single().generation)
                assertTrue(runtime.isAvailable)
                // A refreshed catalogue can acquire a real homepage without replacing the runtime.
                response = """[{"title":"推荐榜","url":"/ranking"}]"""
                assertNotNull(discovery.catalog(true).get())
                assertEquals(1, visible(SourceCapability.Explore).size)
            } finally { registry.unregister(id) }
        }
    }

    @Test fun emptyHomepagePreviewIsVisibleAsAResponseFailureAndCanRecover() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) + mapOf(
            "exploreUrl" to JsonPrimitive("热门推荐::/search"),
            "ruleExplore" to JsonObject(raw.getValue("ruleSearch").jsonObject + ("bookList" to JsonPrimitive("article")))
        )) }.use { source ->
            val provider = RuleDiscoveryProvider(source)
            val section = provider.feed().get()!!.single()
            assertTrue(provider.hasFeed)
            assertEquals(DiscoveryError.InvalidResponse, section.previewFailure?.error)
            assertEquals("ruleExplore.bookList", section.previewFailure?.field)
            assertNotNull(section.more)
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = okhttp3.mockwebserver.MockResponse()
                    .setBody("<article><h2>Restored</h2><a href='/book/one'>Read</a></article>")
            }
            val restored = provider.feed().get()!!.single()
            assertNull(restored.previewFailure)
            assertEquals("Restored", restored.books.single().title)
        } }
    }

    @Test fun importedSearchOnlySourcesNeverAdvertiseDiscoveryEvenWhenItsDisplaySwitchIsOn() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val authority = ExecutionAuthority()
            val accounts = SourceSessionManager(authority)
            val registry = WebSourceRegistry(authority)
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir() = directory.root
            }
            val imported = ImportedRuleSources(context, registry, authority, accounts, fixture.runner)
            try {
                for ((index, explore) in listOf(null, "", " \n ").withIndex()) {
                    val raw = JsonObject(fixture.raw("search-only-$index") + buildJsonObject {
                        put("enabledExplore", true)
                        explore?.let { put("exploreUrl", it) }
                    })
                    val preview = imported.importer.preview(raw.toString())
                    val committed = imported.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                    val id = imported.activate(committed.items.single().reference!!,
                        listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
                    val listing = registry.sources.value.single { it.metadata.id == id }
                    assertTrue(SourceCapability.Search in listing.metadata.capabilities)
                    assertFalse(SourceCapability.Explore in listing.metadata.capabilities)
                    assertFalse(SourceCapability.Categories in listing.metadata.capabilities)
                    assertTrue(imported.loginTarget(id).rules.search("fixture", 1).isNotEmpty())
                }
                assertTrue(indi.renakoni.nextvol.ui.home.discovery.discoverySources(
                    registry.sources.value, SourceCapability.Explore).isEmpty())
            } finally { imported.stop() }
        }
    }

    @Test fun migratedExclusionSelectorsWorkWithTheApplicationsJsoupVersion() {
        val html = hnovel.rules.RuleValue.Text("<table id='diss' class='book-list-table'><tr><td>Header</td></tr>" +
            "<tr><td>One</td></tr><tr><td>Two</td></tr></table>")
        for (rule in listOf("#diss@tr!0@td", "class.rank-book-list@tag.li||class.book-list-table@tag.tr!0")) {
            val result = hnovel.rules.RuleEvaluator().evaluate(rule, html, hnovel.rules.RuleContext("fixture"), hnovel.rules.OutputKind.Elements)
            assertTrue(result.toString(), result is hnovel.rules.RuleResult.Success)
            val rows = ((result as hnovel.rules.RuleResult.Success).value as hnovel.rules.RuleValue.Items).values
            assertEquals(2, rows.size)
        }
    }

    @Test fun failedPreviewKeepsTheCatalogueAndOtherModulesWithItsOwnDiagnostic() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(definition(raw) + ("homepageModules" to JsonPrimitive("""[
                {"key":"broken","type":"card","title":"Broken","url":"https://ungranted.test/list?secret=hidden"},
                {"key":"working","type":"card","title":"Working","url":"/search"}
            ]"""))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val snapshots = mutableListOf<List<DiscoverySection>>()
                provider.feedUpdates().collect {
                    snapshots += it.get()!!
                    assertNull(provider.failureField)
                    assertNull(provider.permissionFailure)
                }
                assertEquals(listOf(2, 2, 2), snapshots.map { it.size })
                assertTrue(snapshots.first().all { it.previewLoading && it.previewFailure == null })
                assertFalse(snapshots[1].first().previewLoading)
                assertTrue(snapshots[1].last().previewLoading)
                val feed = snapshots.last()
                assertTrue(feed.none { it.previewLoading })
                assertEquals(listOf("Broken", "Working"), feed.map { it.title })
                assertEquals(DiscoveryError.PermissionDenied, feed.first().previewFailure?.error)
                assertEquals(DiscoveryPermission("https://ungranted.test:443", "Document"), feed.first().previewFailure?.permission)
                assertTrue(feed.first().books.isEmpty())
                assertNotNull(feed.first().more)
                assertTrue(feed.last().books.isNotEmpty())
                assertNull(feed.last().previewFailure)
                assertNull(provider.failureField)
                assertNull(provider.permissionFailure)
                assertEquals(1, fixture.documents.get())
            }
        }
    }

    @Test fun legacyBrokenFirstEntryDoesNotHideWorkingLaterEntry() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to
                JsonPrimitive("最近更新::/missing\n人气榜::/search"))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val feed = provider.feed().get()!!
                assertEquals(2, feed.size)
                assertNotNull(feed.first().previewFailure)
                assertNotNull(feed.last().more)
                assertTrue(feed.last().books.isNotEmpty())
                assertTrue(provider.page(DiscoveryRequest(feed.last().more!!)).get()!!.books.isNotEmpty())
                assertEquals(3, fixture.documents.get())
            }
        }
    }

    @Test fun retryFetchesOnlyTheFailedPreviewAndStillUsesTheSixBookLimit() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
            var broken = true
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    paths += request.path!!
                    if (broken && request.path == "/weekly") return okhttp3.mockwebserver.MockResponse().setResponseCode(503)
                    return okhttp3.mockwebserver.MockResponse().setBody((1..30).joinToString("") {
                        "<li><h2>Book $it</h2><a href='/book/$it'>Read</a></li>"
                    })
                }
            }
            fixture.source { raw -> JsonObject(definition(raw) + ("homepageModules" to JsonPrimitive("""[
                {"key":"daily","type":"card","title":"Daily","url":"/daily"},
                {"key":"weekly","type":"card","title":"Weekly","url":"/weekly"},
                {"key":"monthly","type":"card","title":"Monthly","url":"/monthly"}
            ]"""))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val feed = provider.feed().get()!!
                assertNotNull(feed[1].previewFailure)
                assertEquals(listOf(6, 0, 6), feed.map { it.books.size })
                broken = false
                val recovered = provider.preview(feed[1].id).get()!!
                assertEquals(feed[1].more, recovered.more)
                assertNull(recovered.previewFailure)
                assertFalse(recovered.previewLoading)
                assertEquals(6, recovered.books.size)
                assertEquals(4, paths.size)
                assertEquals(listOf("/daily", "/monthly", "/weekly"), paths.take(3).sorted())
                assertEquals("/weekly", paths.last())
                assertEquals(DiscoveryError.InvalidRequest, provider.preview("unknown").getError())
                assertNull(provider.failureField)
                assertNotNull(feed[1].previewFailure)
            }
        }
    }

    @Test fun challengeInSecondModuleWaitsForManualRetryAndDoesNotReloadTheFirst() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
            ("homepageModules" to JsonPrimitive("""[
                {"key":"first","type":"card","title":"First","url":"/search?module=1"},
                {"key":"second","type":"card","title":"Second","url":"/search?module=2"}
            ]"""))) }.use { source ->
            val original = source.openDiscovery("original")
            val catalog = original.catalog(homepage = true)
            val books = original.page("/search", 1, emptyMap())
            var verified = false
            val verification = mockk<SourceVerification> {
                every { kind } returns hnovel.network.BrowserChallengeKind.Cloudflare
                every { certificate } returns null
                every { origin } returns fixture.server.url("/").toString()
                coEvery { complete() } coAnswers { verified = true }
            }
            val session = mockk<RuleDiscoverySession>()
            coEvery { session.catalog(homepage = true) } returns catalog
            coEvery { session.concurrentPreviews(any(), any()) } returns null
            coEvery { session.preview("/search?module=1", any()) } returns RuleListPage(books, "2", 2)
            coEvery { session.preview("/search?module=2", any()) } coAnswers {
                if (!verified) throw SourceContentException(ContentError.BrowserRequired,
                    "ruleExplore", verification = verification)
                RuleListPage(books, "2", 2)
            }
            val owner = VerificationOwner(Identifier("rules", "progressive"), "revision", 0)
            val listings = MutableStateFlow(listOf(SourceListing(SourceMetadata(
                WebDataSourceItem(owner.source, "Fixture", ""), emptySet(), revision = owner.revision), SourceStatus.Ready)))
            val coordinator = SourceVerificationCoordinator(mockk<WebSourceRegistry> { every { sources } returns listings })
            val provider = RuleDiscoveryProvider(source, session, RuleRequestRecovery(coordinator, owner, "Fixture"))
            val snapshots = mutableListOf<List<DiscoverySection>>()
            withContext(ForegroundSourceRequest()) { provider.feedUpdates().collect { snapshots += it.get()!! } }
            assertEquals(listOf(2, 2, 2), snapshots.map { it.size })
            assertNotNull(snapshots.last()[1].previewFailure)
            coVerify(exactly = 0) { verification.complete() }
            val retried = withContext(ForegroundSourceRequest()) { provider.preview(snapshots.last()[1].id).get()!! }
            assertNull(retried.previewFailure)
            assertTrue(retried.books.isNotEmpty())
            coVerify(exactly = 1) { session.preview("/search?module=1", any()) }
            coVerify(exactly = 3) { session.preview("/search?module=2", any()) }
            coVerify(exactly = 1) { verification.complete() }
        } }
    }

    @Test fun firstHomepageSnapshotShowsAllEntriesWithoutStartingPreviewRequests() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
            ("homepageModules" to JsonPrimitive("""[
                {"key":"first","type":"card","title":"First","url":"/search?module=1"},
                {"key":"second","type":"card","title":"Second","url":"/search?module=2"}
            ]"""))) }.use { source ->
            val first = RuleDiscoveryProvider(source).feedUpdates().first().get()!!
            assertEquals(listOf("First", "Second"), first.map { it.title })
            assertEquals(listOf("/search?module=1", "/search?module=2"), first.map { it.more })
            assertTrue(first.all { it.books.isEmpty() && it.previewFailure == null })
            assertTrue(first.all { it.previewLoading })
            assertEquals(0, fixture.documents.get())
        } }
    }

    @Test fun stoppingAStatefulFeedAfterFirstPreviewDoesNotFetchLaterModules() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
            ("header" to JsonPrimitive("@js:JSON.stringify({'X-State':source.get('preview-state')})")) +
            ("homepageModules" to JsonPrimitive("""[
                {"key":"first","type":"card","title":"First","url":"/search?module=1"},
                {"key":"second","type":"card","title":"Second","url":"/search?module=2"}
            ]"""))) }.use { source ->
            val snapshot = RuleDiscoveryProvider(source).feedUpdates().first { result ->
                result.get().orEmpty().any { it.books.isNotEmpty() }
            }.get()!!
            assertEquals(listOf("First", "Second"), snapshot.map { it.title })
            assertFalse(snapshot.first().previewLoading)
            assertTrue(snapshot.last().previewLoading)
            assertEquals(1, fixture.documents.get())
            assertEquals("/search?module=1", fixture.server.takeRequest().path)
        } }
    }

    @Test fun homepageSnapshotsAreImmutableAndLaterFailureKeepsEarlierSuccess() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
            ("header" to JsonPrimitive("@js:JSON.stringify({'X-State':source.get('preview-state')})")) +
            ("homepageModules" to JsonPrimitive("""[
                {"key":"first","type":"card","title":"First","url":"/search?module=1"},
                {"key":"second","type":"card","title":"Second","url":"/search?module=2"},
                {"key":"third","type":"card","title":"Third","url":"/search?module=3"}
            ]"""))) }.use { source ->
            val provider = RuleDiscoveryProvider(source)
            val updates = mutableListOf<Result<List<DiscoverySection>, DiscoveryError>>()
            provider.feedUpdates().collect {
                updates += it
                fixture.status = if (updates.size == 2) 503 else 200
            }
            assertEquals(listOf(3, 3, 3, 3), updates.map { it.get()!!.size })
            assertEquals(listOf("First", "Second", "Third"), updates.first().get()!!.map { it.title })
            assertEquals(listOf(3, 2, 1, 0), updates.map { it.get()!!.count { section -> section.previewLoading } })
            val failed = updates[2].get()!![1]
            assertEquals(DiscoveryError.Network, failed.previewFailure?.error)
            assertEquals("/search?module=2", failed.more)
            assertTrue(failed.books.isEmpty())
            val final = updates.last().get()!!
            assertEquals(listOf("First", "Second", "Third"), final.map { it.title })
            assertEquals(failed, final[1])
            assertTrue(final.first().books.isNotEmpty())
            assertTrue(final.last().books.isNotEmpty())
            assertNull(final.last().previewFailure)
            assertEquals(3, fixture.documents.get())
            val complete = mutableListOf<List<DiscoverySection>>()
            provider.feedUpdates().collect { complete += it.get()!! }
            assertEquals(listOf(3, 3, 3, 3), complete.map { it.size })
            assertTrue(complete.last().all { it.previewFailure == null && it.books.isNotEmpty() })
            assertTrue(updates.first().get()!!.all { it.previewLoading && it.books.isEmpty() })
        } }
    }

    @Test fun scopedResultFiltersStayOutOfHomepageCategoriesAndOtherLists() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) + mapOf(
            "exploreUrl" to JsonPrimitive("""[{"title":"Tag","url":"/tags/fixture?sort={{infoMap.Sort}}"}]"""),
            "exploreScreen" to JsonPrimitive("""[{"title":"Sort","type":"select","chars":["new","popular"],"targetPrefixes":["/search?","/tags/"]}]"""),
            "homepageModules" to JsonPrimitive("""[{"key":"articles","type":"card","title":"Articles","url":"/search?sort={{infoMap.Sort}}"}]""")
        )) }.use { source ->
            val provider = RuleDiscoveryProvider(source)
            assertTrue(provider.homepageCatalog().get()!!.filters.isEmpty())
            assertEquals("new", provider.catalog().get()!!.values["Sort"])
            assertTrue(provider.catalog().get()!!.filters.isEmpty())
            assertTrue(provider.filters("/rank?type=day").isEmpty())
            assertEquals("Sort", provider.filters("/search?sort={{infoMap.Sort}}").single().id)
            assertEquals("Sort", provider.filters("/tags/fixture?sort={{infoMap.Sort}}").single().id)
            assertTrue(provider.page(DiscoveryRequest("/search?sort={{infoMap.Sort}}", filters = mapOf("Sort" to "popular"))).get()!!.books.isNotEmpty())
            assertEquals("/search?sort=popular", fixture.server.takeRequest().path)
        } }
    }

    @Test fun directHomepageControlsAndResultsDoNotEvaluateFailingCategories() = runBlocking {
        RuleSourceFixture().use { fixture -> fixture.source { raw -> JsonObject(definition(raw) + mapOf(
            "exploreUrl" to JsonPrimitive("@js:throw 'category unavailable'"),
            "exploreScreen" to JsonPrimitive("""[{"title":"Sort","type":"select","chars":["new","popular"],"default":"new","action":"java.refreshExplore()"}]"""),
            "homepageModules" to JsonPrimitive("""[{"key":"articles","type":"card","title":"Articles","url":"/search?sort={{infoMap.Sort}}&page={{page}}"}]""")
        )) }.use { source ->
            val provider = RuleDiscoveryProvider(source)
            assertEquals("new", provider.homepageCatalog().get()!!.values["Sort"])
            assertTrue(provider.interact("Sort", "popular").get()!!.refresh)
            assertEquals("popular", provider.homepageCatalog(true).get()!!.values["Sort"])
            val feed = provider.feed().get()!!.single()
            assertEquals("Articles", feed.title)
            assertNull(feed.categoryId)
            assertEquals("/search?sort=popular&page=1", fixture.server.takeRequest().path)
            val result = provider.openSession("result", mapOf("Sort" to "new"), DiscoveryEnvironment())
            result.homepageCatalog()
            assertTrue(result.page(DiscoveryRequest(feed.more!!, filters = mapOf("Sort" to "new"))).get()!!.books.isNotEmpty())
            assertEquals("/search?sort=new&page=1", fixture.server.takeRequest().path)
            assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
        } }
    }

    @Test fun nativeSiteChallengeIsNotReportedAsMissingAccountLogin() = runBlocking {
        for (kind in hnovel.network.BrowserChallengeKind.entries) {
            val browser = hnovel.network.BrowserExecutor { _, request, _, _, _ ->
                hnovel.network.BrokerResult.Failure(hnovel.network.RequestStage.Response, hnovel.network.FailureCode.BrowserRequired,
                    challenge = kind, verificationRequest = request)
            }
            RuleSourceFixture(browser).use { fixture -> fixture.source { raw -> JsonObject(definition(raw) +
                ("browserRead" to JsonPrimitive(true))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val expected = if (kind == hnovel.network.BrowserChallengeKind.Login) DiscoveryError.AuthenticationRequired
                    else DiscoveryError.VerificationRequired
                assertEquals(Err(expected), provider.page(DiscoveryRequest("/search")))
                val runtime = RuleWebBookDataSource(io.nightfish.lightnovelreader.api.identifier.Identifier("rules", "fixture"), source)
                val requestError = runtime.getBookInformation(fixture.server.url("/book/one").toString()).component2()!!
                assertEquals(if (kind == hnovel.network.BrowserChallengeKind.Login)
                    io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.AuthenticationRequired
                    else io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.VerificationRequired, requestError.kind)
            } }
        }
    }

    @Test fun declaredHomepageKeepsNavigationSeparateFromTagCategories() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("""[
                    {"title":"Ranking","url":"/search?kind=rank&page={{page}}"},
                    {"title":"Articles","url":"/search?kind=articles&page={{page}}"},
                    {"title":"Fantasy","url":"/search?tag=fantasy&page={{page}}"},
                    {"title":"Science fiction","url":"/search?tag=scifi&page={{page}}"}
                ]"""),
                "homepageModules" to JsonPrimitive("""[
                    {"key":"rank","type":"ranking","title":"Ranking","kindTitle":"Ranking"},
                    {"key":"articles","type":"card","title":"Articles","kindTitle":"Articles"}
                ]"""),
                "ruleExplore" to raw.getValue("ruleSearch"))) }
            val provider = RuleDiscoveryProvider(source)
            val categories = provider.catalog().get()!!.categories
            assertEquals(listOf("Ranking", "Articles", "Fantasy", "Science fiction"), categories.map { it.title })
            assertEquals(0, fixture.documents.get())
            val feed = provider.feed().get()!!
            assertEquals(listOf("Ranking", "Articles"), feed.map { it.title })
            assertTrue(feed.all { it.books.isNotEmpty() })
            assertEquals("/search?kind=articles&page={{page}}", feed.last().more)
            assertEquals(2, fixture.documents.get())
            assertTrue(provider.page(DiscoveryRequest(categories[2].target)).get()!!.books.isNotEmpty())
            assertEquals(3, fixture.documents.get())
        }
    }

    @Test fun missingHomepageTargetReportsItsSourceField() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(definition(raw) + ("homepageModules" to
                JsonPrimitive("""[{"key":"rank","type":"ranking","title":"Ranking","kindTitle":"Missing"}]"""))) }
            val provider = RuleDiscoveryProvider(source)
            assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
            assertEquals("homepageModules[0].kindTitle", provider.failureField)
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun deniedCategoryPageReportsOnlyItsOriginAndResourcePurpose() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + mapOf(
                "exploreUrl" to JsonPrimitive("""[{"title":"Books","url":"https://api.invalid:8443/private?token=secret"}]"""),
                "ruleExplore" to raw.getValue("ruleSearch"))) }).use { source ->
                val provider = RuleDiscoveryProvider(source)
                val category = provider.catalog().get()!!.categories.single()
                assertEquals(Err(DiscoveryError.PermissionDenied), provider.page(DiscoveryRequest(category.target)))
                assertEquals(DiscoveryPermission("https://api.invalid:8443", "Document"), provider.permissionFailure)
                assertEquals(0, fixture.server.requestCount)
                provider.catalog()
                assertNull(provider.permissionFailure)
            }
        }
    }
    private fun definition(raw: JsonObject) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive("[{\"title\":\"Sort\",\"type\":\"select\",\"chars\":[\"new\",\"popular\"]},{\"title\":\"Recent\",\"url\":\"/search?sort={{infoMap.Sort}}&page={{page}}\"}]"),
        "ruleExplore" to raw.getValue("ruleSearch")))

    @Test fun productionAdapterBindsResultsAndKeepsRetryAndFiltersInTheOwningPage() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rule = fixture.source(customize = ::definition)
            val id = Identifier("rules", rule.definition.sourceId)
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rule), SourceMetadata(WebDataSourceItem(id, "Fixture", "Tests"),
                setOf(SourceCapability.Categories, SourceCapability.Explore)))
            try {
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val a = runtime.discovery!!.forSession("a", mapOf("Sort" to "new"))
                val b = runtime.discovery!!.forSession("b", mapOf("Sort" to "popular"))
                val first = a.catalog().get()!!
                val second = b.catalog().get()!!
                assertEquals(0, fixture.documents.get())
                val aPage = a.open(first.categories.single().target)
                val bPage = b.open(second.categories.single().target)
                aPage.reset(first.values); bPage.reset(second.values)
                val books = aPage.loadMore().get()!!.books
                assertEquals(id, books.single().id.sourceId)
                assertEquals("/search?sort=new&page=1", fixture.server.takeRequest().path)
                bPage.loadMore()
                assertEquals("/search?sort=popular&page=1", fixture.server.takeRequest().path)
                fixture.status = 401
                assertEquals(Err(DiscoveryError.Network), aPage.loadMore())
                assertEquals("/search?sort=new&page=2", fixture.server.takeRequest().path)
                fixture.status = 200
                val repeated = aPage.loadMore().get()!!
                assertEquals("/search?sort=new&page=2", fixture.server.takeRequest().path)
                assertEquals(books, repeated.books)
                assertNull(repeated.nextCursor)
                val requests = fixture.documents.get()
                aPage.loadMore()
                assertEquals(requests, fixture.documents.get())
                assertEquals("popular", second.values["Sort"])
            } finally { registry.unregister(id) }
        }
    }

    @Test fun feedUsesSourceOrderAndLoadsEachRecognizedList() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rule = fixture.source { raw -> definition(raw).let {
                JsonObject(it + ("exploreUrl" to JsonPrimitive("New::/search&&Completed::/search?complete=1&&Popular::/search?hot=1")))
            } }
            val provider = RuleDiscoveryProvider(rule)
            val feed = provider.feed().get()!!
            assertEquals(listOf("New", "Popular"), feed.map { it.title })
            assertEquals(listOf(1, 1), feed.map { it.books.size })
            assertTrue(feed.all { it.more != null })
            assertTrue(provider.hasInteractions)
            val categories = provider.catalog().get()!!.categories
            assertEquals(3, categories.size)
            assertEquals(listOf(categories.first().id, categories.last().id), feed.map { it.categoryId })
            assertEquals(2, fixture.documents.get())
        }
    }

    @Test fun realQidianCatalogReachesTheHostWithoutLosingTargetsOrFetchingBooks() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rows = DiscoveryCatalogFixtures.rows(0)
            fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive(rows.toString()))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                val catalog = provider.catalog().get()!!
                assertEquals(326, catalog.categories.size)
                assertEquals(rows.map { it.jsonObject.getValue("url").jsonPrimitive.content }, catalog.categories.map { it.target })
                assertEquals(catalog.categories, provider.categories().get())
                assertEquals(0, fixture.documents.get())
            }
        }
    }

    @Test fun largeGenreCatalogFetchesNoPreviewAndTheLastCategoryUsesItsOwnTarget() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rows = buildJsonArray { repeat(326) { i -> add(buildJsonObject {
                put("title", "Category $i"); put("url", "/search?category=$i&page={{page}}")
            }) } }
            val rule = fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive(rows.toString()))) }
            val id = Identifier("rules", rule.definition.sourceId)
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rule), SourceMetadata(WebDataSourceItem(id, "Large source", "Test"),
                setOf(SourceCapability.Categories, SourceCapability.Explore)))
            try {
                val source = (registry.resolve(id) as SourceResolution.Ready).runtime.discovery!!
                val categories = source.categories().get()!!
                assertEquals(0, fixture.documents.get())
                assertFalse(source.hasFeed)
                assertEquals(Err(DiscoveryError.Unsupported), source.feed())
                assertEquals(0, fixture.documents.get())
                val last = source.open(categories.last().target).loadMore().get()!!
                assertEquals(id, last.books.single().id.sourceId)
                assertEquals("/search?category=325&page=1", fixture.server.takeRequest().path)
                assertEquals(1, fixture.documents.get())
            } finally { registry.unregister(id) }
        }
    }

    @Test fun catalogLimitAndInvalidControlsHaveDifferentHostErrorsAndLocations() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val large = buildJsonArray { repeat(4097) { i -> add(buildJsonObject { put("title", "Row $i"); put("url", "/$i") }) } }
            for ((rows, error, field) in listOf(
                Triple(large, DiscoveryError.Limit, "exploreUrl"),
                Triple(Json.parseToJsonElement("""[{"title":"","type":"text"}]""").jsonArray,
                    DiscoveryError.InvalidRules, "exploreUrl[0].title"),
            )) fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive(rows.toString()))) }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertEquals(Err(error), provider.catalog())
                assertEquals(field, provider.failureField)
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun pageLimitKeepsTheLastAllowedBooksAndReportsTheNextAttemptWithoutFetching() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val provider = RuleDiscoveryProvider(fixture.source(customize = ::definition))
            val last = provider.page(DiscoveryRequest("/search", "64")).get()!!
            assertEquals(1, last.books.size)
            assertEquals("65", last.nextCursor)
            assertEquals(Err(DiscoveryError.Limit), provider.page(DiscoveryRequest("/search", last.nextCursor)))
            assertEquals("ruleExplore.page", provider.failureField)
            assertEquals(1, fixture.documents.get())
        }
    }

    @Test fun unknownDialectReportsTheOriginalFieldThroughTheHostAdapter() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(definition(raw) +
                ("exploreUrl" to JsonPrimitive("[{\"title\":\"Unsupported\",\"type\":\"nativeView\"}]"))) }
            val provider = RuleDiscoveryProvider(source)
            assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
            assertEquals("exploreUrl[0].type", provider.failureField)
        }
    }

    @Test fun missingDisabledEmptyAndInvalidCataloguesRemainDifferentWithoutFetchingBooks() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val cases = listOf(
                fixture.raw("missing") to false,
                JsonObject(definition(fixture.raw("disabled")) + ("enabledExplore" to JsonPrimitive(false))) to false,
                JsonObject(definition(fixture.raw("empty")) + ("exploreUrl" to JsonPrimitive("[]"))) to false,
                JsonObject(definition(fixture.raw("invalid")) + ("exploreUrl" to JsonPrimitive("[{\"title\":\"Broken\",\"type\":\"nativeView\"}]"))) to true
            )
            for ((index, value) in cases.withIndex()) fixture.source { value.first }.use { source ->
                val provider = RuleDiscoveryProvider(source)
                assertEquals(value.second, provider.hasCategories)
                assertEquals(value.second, provider.hasFeed)
                assertTrue(source.canSearch)
                when (index) {
                    0, 1 -> assertEquals(Err(DiscoveryError.Unsupported), provider.catalog())
                    2 -> assertTrue(provider.catalog().get()!!.categories.isEmpty())
                    3 -> {
                        assertEquals(Err(DiscoveryError.InvalidRules), provider.catalog())
                        assertEquals("exploreUrl[0].type", provider.failureField)
                    }
                }
            }
            assertEquals(0, fixture.documents.get())
        }
    }

    @Test fun formKeysIgnoreViewNamesAndConfigurationRefreshDoesNotNavigate() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source { raw -> JsonObject(definition(raw) + ("exploreUrl" to JsonPrimitive("""[
                {"title":"Query","type":"text","default":"start","viewName":"'Search '+infoMap.Query"},
                {"id":"configure","title":"Configure","type":"button","action":"java.upConfig({category:'new'});"},
                {"id":"settings","title":"Settings","type":"button","action":"java.upConfig();"},
                {"id":"search","title":"Search","type":"button","action":"java.searchBook(infoMap.Query);"}
            ]"""))) }
            val provider = RuleDiscoveryProvider(source)
            val catalog = provider.catalog().get()!!
            assertEquals(DiscoveryFilter.Text("Query", "Search start", "start"), catalog.filters.single())
            assertEquals(mapOf("Query" to "start"), catalog.values)
            val configured = provider.interact("configure").get()!!
            assertTrue(configured.refresh)
            assertTrue(configured.actions.isEmpty())
            val settings = provider.interact("settings").get()!!
            assertEquals(listOf(DiscoveryAction.Settings), settings.actions)
            assertFalse(settings.refresh)
            val search = provider.interact("search").get()!!.actions.single() as DiscoveryAction.Results
            assertEquals(DISCOVERY_SEARCH_PREFIX + "start", search.target)
            assertTrue(provider.filters(search.target).isEmpty())
        }
    }

    @Test fun importedCapabilitiesAndAccountReplacementNeverRemoveTheSelectedTab() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val authority = ExecutionAuthority()
            val accounts = SourceSessionManager(authority)
            val registry = WebSourceRegistry(authority)
            val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir() = directory.root
            }
            val imported = ImportedRuleSources(context, registry, authority, accounts, fixture.runner)
            try {
                val preview = imported.importer.preview(definition(fixture.raw()).toString())
                val committed = imported.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = imported.activate(committed.items.single().reference!!,
                    listOf(NetworkGrant(fixture.server.url("/").toString(), allowPrivateAddresses = true)))
                val initial = registry.sources.value.single()
                assertTrue(initial.metadata.capabilities.containsAll(setOf(SourceCapability.Explore, SourceCapability.Categories, SourceCapability.Search)))
                val snapshots = mutableListOf<List<SourceListing>>()
                val observer = launch(Dispatchers.Unconfined) { registry.sources.collect { snapshots += it } }
                imported.rotateAccount(id)
                observer.cancelAndJoin()
                assertTrue(snapshots.isNotEmpty())
                assertTrue(snapshots.all { list -> list.any { it.metadata.id == id && SourceCapability.Categories in it.metadata.capabilities } })
                assertTrue(registry.sources.value.single().generation > initial.generation)
                assertEquals(1L, registry.sources.value.single().metadata.accountGeneration)
            } finally { imported.stop() }
        }
    }
}
