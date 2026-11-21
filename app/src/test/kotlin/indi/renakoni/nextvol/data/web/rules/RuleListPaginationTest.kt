package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import com.github.michaelbull.result.Err
import hnovel.content.RuleSourceFixture
import hnovel.content.ContentTrace
import hnovel.content.ContentTraceEvent
import indi.renakoni.nextvol.data.explore.PagedSearchProvider
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryRequest
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleListPaginationTest {
    private fun definition(raw: JsonObject) = JsonObject(raw + mapOf(
        "exploreUrl" to JsonPrimitive("Latest::/search"),
        "ruleSearch" to buildJsonObject {
            put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href")
            put("nextPageUrl", "a.next@href")
        }
    ))

    private fun RuleSourceFixture.pages() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                if (request.requestUrl!!.queryParameter("after") == "remote-token")
                    "<li><a href='/book/final'><h2>Final</h2></a></li>"
                else "<li><a href='/book/filtered'><h2></h2></a></li><a class='next' href='/search?after=remote-token'>next</a>"
            )
        }
    }

    @Test fun discoveryFollowsAnExplicitNextUrlAcrossFilteredEmptyPagesAndStopsAtTheRealEnd() = runBlocking {
        val events = mutableListOf<ContentTraceEvent>()
        RuleSourceFixture(trace = ContentTrace { events += it }).use { fixture ->
            fixture.pages()
            fixture.source(customize = ::definition).use { source ->
                val provider = RuleDiscoveryProvider(source)
                val first = provider.page(DiscoveryRequest("/search")).get()!!
                assertTrue(first.books.isEmpty())
                assertNotNull("A filtered page still has a remote continuation", first.nextCursor)
                val last = provider.page(DiscoveryRequest("/search", first.nextCursor)).get()!!
                assertEquals(listOf("Final"), last.books.map { it.title })
                assertNull(last.nextCursor)
                assertEquals(2, fixture.server.requestCount)
                assertEquals(listOf("FilteredEmpty", "End"), events.filter { it.kind == "pagination" }.map { it.result })
            }
        }
    }

    @Test fun nullAndBlankScriptContinuationsEndWithoutInventingTheCurrentUrl() = runBlocking {
        for (rule in listOf("@js:null", "@js:''", "@js:'   '")) {
            val events = mutableListOf<ContentTraceEvent>()
            RuleSourceFixture(trace = ContentTrace { events += it }).use { fixture ->
                fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to
                    JsonObject(raw.getValue("ruleSearch").jsonObject + ("nextPageUrl" to JsonPrimitive(rule))))) }).use { source ->
                    val page = source.openSearchPages("key").page()
                    assertEquals(1, page.books.size)
                    assertNull(page.nextCursor)
                    assertEquals("End", events.last { it.kind == "pagination" }.result)
                    assertEquals(1, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun filtersRefreshAndSeparateNavigationSessionsDoNotShareContinuationState() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.pages()
            fixture.source(customize = ::definition).use { source ->
                val provider = RuleDiscoveryProvider(source)
                val first = provider.page(DiscoveryRequest("/search", filters = mapOf("Sort" to "new"))).get()!!
                val other = provider.page(DiscoveryRequest("/search", filters = mapOf("Sort" to "popular"))).get()!!
                for ((sort, cursor) in listOf("new" to first.nextCursor, "popular" to other.nextCursor)) {
                    assertEquals(listOf("Final"), provider.page(DiscoveryRequest("/search", cursor, mapOf("Sort" to sort))).get()!!.books.map { it.title })
                }
                val refreshed = provider.page(DiscoveryRequest("/search", filters = mapOf("Sort" to "new"))).get()!!
                assertTrue(refreshed.books.isEmpty())
                assertNotNull(refreshed.nextCursor)
                val restored = RuleDiscoveryProvider(source)
                assertEquals(Err(DiscoveryError.InvalidRules), restored.page(DiscoveryRequest("/search", first.nextCursor)))
                assertNotNull(restored.page(DiscoveryRequest("/search")).get()!!.nextCursor)
                assertEquals(6, fixture.server.requestCount)
            }
        }
    }

    @Test fun parallelQueriesAndLegacyStreamsRetainTheirOwnRemoteContinuations() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.pages()
            fixture.source(customize = ::definition).use { source ->
                val adapter = RuleWebBookDataSource(Identifier("rules", "fixture"), source).searchProvider
                val provider = adapter as PagedSearchProvider
                val type = adapter.searchTypes.single()
                val first = async { provider.searchPage(type, "same", 1, "one") }
                val second = async { provider.searchPage(type, "same", 1, "two") }
                assertEquals(2, first.await().nextPage)
                assertEquals(2, second.await().nextPage)
                assertEquals(1, provider.searchPage(type, "same", 2, "one").books.size)
                assertEquals(1, provider.searchPage(type, "same", 2, "two").books.size)
                val events = adapter.search(type, "same").toList()
                assertEquals(1, events.filterIsInstance<SearchResult.MultipleBook>().size)
                assertTrue(events.last() is SearchResult.End)
                assertTrue(events.none { it is SearchResult.Empty || it is SearchResult.Error })
                assertEquals(6, fixture.server.requestCount)
            }
        }
    }

    @Test fun cachedPagesCannotOutliveTheirSourceAuthority() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val source = fixture.source()
            val page = source.openSearchPages("key")
            page.page(1)
            source.close()
            try { page.page(1); fail("A cached page cannot bypass retirement") }
            catch (failure: hnovel.content.SourceContentException) {
                assertEquals(hnovel.content.ContentError.Unavailable, failure.code)
            }
            assertEquals(1, fixture.server.requestCount)
        }
    }
}
