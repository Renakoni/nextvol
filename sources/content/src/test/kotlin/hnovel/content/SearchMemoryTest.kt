package hnovel.content

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SearchMemoryTest {
    // Mirrors the sample search: a JSON string in memory removes a series already shown on an earlier page.
    private val dedupe = "h2@text@js:if(result.startsWith('series-')){" +
        "var seen=JSON.parse(cache.getFromMemory('series')||'[]');" +
        "if(seen.indexOf(result)>=0) result=''; else {seen.push(result);cache.putMemory('series',JSON.stringify(seen));}}" +
        "result"

    private fun RuleSourceFixture.searchSource(): RuleSource {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val page = request.requestUrl!!.queryParameter("page")
                return MockResponse().setBody("<li><a href='/book/s$page'><h2>series-A</h2></a></li>" +
                    "<li><a href='/book/$page'><h2>solo-$page</h2></a></li>")
            }
        }
        return source { raw -> JsonObject(raw + ("ruleSearch" to JsonObject(raw.getValue("ruleSearch").jsonObject +
            ("name" to JsonPrimitive(dedupe))))) }
    }

    private suspend fun RuleSource.titles(keyword: String, page: Int, query: String?) =
        search(keyword, page, query).map { it.title }

    @Test fun pagesOfOneQueryShareMemoryAndANewQueryStartsAgain() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.searchSource().use { source ->
                assertEquals(listOf("series-A", "solo-1"), source.titles("key", 1, "q1"))
                assertEquals(listOf("solo-2"), source.titles("key", 2, "q1"))
                assertEquals(listOf("series-A", "solo-1"), source.titles("key", 1, "q2"))
                assertEquals(listOf("solo-3"), source.titles("key", 3, "q1"))
                assertEquals(listOf("solo-2"), source.titles("key", 2, "q2"))
            }
        }
    }

    @Test fun keywordsAndCallsWithoutAQueryAreIsolated() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.searchSource().use { source ->
                assertEquals(listOf("series-A", "solo-1"), source.titles("one", 1, "q"))
                assertEquals(listOf("series-A", "solo-1"), source.titles("two", 1, "q"))
                assertEquals(listOf("series-A", "solo-2"), source.titles("one", 2, null))
                assertEquals(listOf("solo-2"), source.titles("one", 2, "q"))
            }
            // A rebuilt source (another account generation or revision) and a new process start empty.
            fixture.searchSource().use { source ->
                assertEquals(listOf("series-A", "solo-2"), source.titles("one", 2, "q"))
            }
        }
    }
}
