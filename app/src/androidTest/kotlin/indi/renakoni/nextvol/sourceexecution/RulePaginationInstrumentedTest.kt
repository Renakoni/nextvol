package indi.renakoni.nextvol.sourceexecution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.content.*
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RulePaginationInstrumentedTest {
    @Test fun filteredEmptyRemoteContinuationCrossesBinderAndEndsOnANonemptyLastPage() = runBlocking { verify(true) }
    @Test fun ordinaryIntegerSourcesKeepTheirOriginalRequestsAndEmptyPageTermination() = runBlocking { verify(false) }

    private suspend fun verify(explicit: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "paging-${System.nanoTime()}")
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        try { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val page = request.requestUrl!!.queryParameter("page")
                    val after = request.requestUrl!!.queryParameter("after")
                    val body = when {
                        page == "3" -> ""
                        page == "2" || after == "remote" -> "<li><a href='/book/final'><h2>Final</h2></a></li>"
                        explicit -> "<li><h2></h2></li><a class='next' href='/list?after=remote'>next</a>"
                        else -> "<li><a href='/book/first'><h2>First</h2></a></li>"
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val raw = buildJsonObject {
                put("bookSourceUrl", server.url("/").toString()); put("bookSourceName", "Pagination fixture")
                put("bookSourceType", 0); put("searchUrl", "/list?page={{page}}")
                put("ruleSearch", buildJsonObject {
                    put("bookList", "li"); put("name", "h2@text"); put("bookUrl", "a@href")
                    if (explicit) put("nextPageUrl", "a.next@href")
                })
            }
            val definitions = SourceDefinitionStore(File(root, "definitions").toPath())
            val importer = SourceDefinitionImporter(definitions)
            val preview = importer.preview(raw.toString(), LEGADO_PROFILE)
            assertTrue(preview.issues.toString(), preview.issues.isEmpty())
            assertNull(importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
            val definition = definitions.list().single()
            SourceBroker(File(root, "broker").toPath()).use { broker ->
                val session = broker.open(SourceScope("paging", definition.sourceId, definition.profile),
                    listOf(NetworkGrant(server.url("/").toString(), allowPrivateAddresses = true)))
                val identity = authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "paging")
                val runner = RuleTaskRunner { owner, task, limits, bridge -> executor.execute(owner, task, limits, bridge) }
                val events = mutableListOf<ContentTraceEvent>()
                createRuleSource(definition, identity, authority, session, runner, ContentTrace { events += it }).use { source ->
                    val pages = source.openSearchPages("fixture")
                    val first = pages.page()
                    assertEquals(explicit, first.books.isEmpty())
                    assertNotNull(first.nextCursor)
                    val second = pages.page(first.nextCursor)
                    assertEquals(listOf("Final"), second.books.map { it.title })
                    if (explicit) assertNull(second.nextCursor) else {
                        assertEquals("3", second.nextCursor)
                        assertNull(pages.page(second.nextCursor).nextCursor)
                    }
                    assertEquals(if (explicit) 2 else 3, server.requestCount)
                    assertEquals("End", events.last { it.kind == "pagination" }.result)
                }
            }
        } } finally { executor.close(); root.deleteRecursively() }
    }
}
