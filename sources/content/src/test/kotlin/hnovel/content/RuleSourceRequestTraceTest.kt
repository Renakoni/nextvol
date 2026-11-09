package hnovel.content

import hnovel.network.RequestEvidence
import hnovel.network.UserAgentSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class RuleSourceRequestTraceTest {
    @Test fun compiledAndScriptRequestsReachTheExistingTraceWithoutSensitiveValues() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<ContentTraceEvent>())
        RuleSourceFixture(trace = ContentTrace { events += it }).use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to JsonObject(
                raw.getValue("ruleSearch").jsonObject + ("bookList" to JsonPrimitive("<js>java.ajax('/search')</js>li")))))
            }).use { source ->
                assertEquals("Same title", source.search("private-keyword").single().title)
                val diagnostics = events.mapNotNull { it.requestDiagnostic }
                val transport = diagnostics.filter { it.evidence == RequestEvidence.TransportHeaders }
                assertEquals(2, transport.size)
                assertEquals(2, transport.map { it.requestId }.distinct().size)
                transport.forEach { sent ->
                    assertTrue(diagnostics.any { it.requestId == sent.requestId && it.evidence == RequestEvidence.Completed })
                    assertTrue(diagnostics.any { it.requestId == sent.requestId && UserAgentSource.SessionDefault in it.userAgentSources })
                }
                val encoded = Json.encodeToString(events.toList())
                listOf("private-keyword", "java.ajax", "Same title", fixture.server.url("/").toString()).forEach {
                    assertFalse(it, encoded.contains(it))
                }
                assertEquals(events.toList(), Json.decodeFromString<List<ContentTraceEvent>>(encoded))
            }
        }
    }

    @Test fun anObserverThatRejectsNewDiagnosticsCannotBreakContentLoading() = runBlocking {
        RuleSourceFixture(trace = ContentTrace { if (it.requestDiagnostic != null) error("diagnostic unavailable") }).use { fixture ->
            fixture.source().use { source -> assertEquals("Same title", source.search("fixture").single().title) }
        }
    }

    @Test fun reportsWrittenBeforeRequestDiagnosticsStillDecode() {
        val event = Json.decodeFromString<ContentTraceEvent>(
            """{"kind":"network","field":"searchUrl","elapsedMillis":4,"result":"HTTP_200"}""")
        assertNull(event.requestDiagnostic)
        assertEquals("HTTP_200", event.result)
    }
}
