package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExecutionSessionTest {
    @get:Rule val root = TemporaryFolder()

    @Test fun eachInvocationOwnsItsBudgetAndFailureState() = runBlocking {
        val authority = ExecutionAuthority()
        SourceBroker(root.newFolder().toPath()).use { broker ->
            val session = broker.open(SourceScope("fixture", "a", "legado"), emptyList())
            val ticket = authority.issue("a", "legado", "1", "fixture")
            val invocations = SourceInvocations(ticket, authority, session)
            val limits = ExecutionLimits(maxRequests = 1)
            invocations.create(limits).use { first ->
                assertEquals(JsonNull, first.call("cache.get", listOf(JsonPrimitive("missing"))))
                assertTrue(first.missingCacheRead)
                assertTrue(runCatching { first.call("source.getVariable", emptyList()) }.isFailure)
                assertTrue(first.requestLimitExceeded)
            }
            invocations.create(limits).use { next ->
                assertFalse(next.missingCacheRead)
                assertFalse(next.requestLimitExceeded)
                assertEquals(JsonPrimitive(""), next.call("source.getVariable", emptyList()))
            }
            assertFalse(session.closed)
        }
    }

    @Test fun ticketsCannotBeReboundAndClosingASessionPermanentlyRevokesThem() {
        val authority = ExecutionAuthority()
        val scope = SourceScope("fixture", "a", "legado")
        SourceBroker(root.newFolder().toPath()).use { first ->
            SourceBroker(root.newFolder().toPath()).use { second ->
                val a = first.open(scope, emptyList())
                val b = second.open(scope, emptyList())
                val ticket = authority.issue("a", "legado", "1", "fixture")
                val invocations = SourceInvocations(ticket, authority, a)
                invocations.create(ExecutionLimits()).close()
                assertTrue(authority.accepts(ticket))
                assertFalse(a.closed)
                assertThrows(IllegalStateException::class.java) { SourceInvocations(ticket, authority, b).create(ExecutionLimits()) }
                a.close()
                assertFalse(authority.accepts(ticket))
                assertThrows(IllegalStateException::class.java) { invocations.create(ExecutionLimits()) }
                assertThrows(IllegalStateException::class.java) { SourceInvocations(ticket, authority, b).create(ExecutionLimits()) }
                val fresh = authority.issue("a", "legado", "1", "fixture")
                SourceInvocations(fresh, authority, b).create(ExecutionLimits()).close()
                assertTrue(authority.accepts(fresh))
                second.open(scope.copy(accountGeneration = 1), emptyList())
                assertFalse(authority.accepts(fresh))
            }
        }
    }
}
