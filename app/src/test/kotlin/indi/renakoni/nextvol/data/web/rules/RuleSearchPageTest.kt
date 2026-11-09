package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.explore.ExploreRepository
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class RuleSearchPageTest {
    @Test fun automaticSearchContinuesPastSixtyFourAndBoundsItsEmptyTail() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val page = request.requestUrl!!.queryParameter("page")!!.toInt()
                    val books = if (page <= 70) "<li><a href='/book/$page'><h2>Book $page</h2></a></li>" else ""
                    return okhttp3.mockwebserver.MockResponse().setBody(books + "<a class='next' href='/search?page=${page + 1}'>Next</a>")
                }
            }
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to JsonObject(
                raw.getValue("ruleSearch").jsonObject + ("nextPageUrl" to JsonPrimitive("a.next@href"))))) }).use { rules ->
                val provider = RuleWebBookDataSource(Identifier("search-rule", "pages"), rules).searchProvider
                val type = provider.searchTypes.first()
                val results = provider.search(type, "title").toList()
                assertEquals(70, results.filterIsInstance<SearchResult.MultipleBook>().size)
                assertTrue(results.last() is SearchResult.Error)
                assertEquals(134, fixture.server.requestCount)
            }
        }
    }

    @Test fun sourceBoundSearchUsesOneRulePageAndCarriesItsPreviewWithoutFetchingDetails() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val rules = fixture.source()
            val id = Identifier("search-rule", "a")
            val registry = WebSourceRegistry()
            registry.register(RuleWebBookDataSource(id, rules), SourceMetadata(
                WebDataSourceItem(id, "Rules", "fixture"), setOf(SourceCapability.Search)))
            try {
                val session = ExploreRepository(registry).open(id).get()!!
                assertTrue(session.hasPages)
                val page = session.page(session.types.first(), "title", 1).first()
                assertEquals(1, fixture.documents.get())
                assertEquals(2, page.nextPage)
                val result = page.books.single()
                assertEquals("Same title", result.information!!.title)
                assertEquals(result.bookId, result.information!!.id)
                assertEquals(id, SourceBookId.fromStorageKey(result.bookId).sourceId)
                assertTrue(fixture.server.takeRequest().path!!.contains("page=1"))
                session.page(session.types.first(), "title", 2).first()
                assertEquals(2, fixture.documents.get())
                assertTrue(fixture.server.takeRequest().path!!.contains("page=2"))
            } finally { registry.unregister(id) }
        }
    }
}
