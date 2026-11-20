package indi.renakoni.nextvol.data.web

import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.*
import org.junit.Test

class SourceSessionManagerTest {
    private val source = Identifier("test", "source-a")

    @Test fun loginAndLogoutRevokeExecutionButStaleLogoutCannotRevokeTheNewAccount() {
        val authority = hnovel.execution.ExecutionAuthority()
        val manager = SourceSessionManager(authority)
        val before = authority.issue(source.id, "legado", "1", source.namespace)
        val first = manager.begin(source)
        assertFalse(authority.accepts(before))
        val loggedIn = authority.issue(source.id, "legado", "1", source.namespace, first.generation)
        val next = manager.logout(first)
        assertFalse(authority.accepts(loggedIn))
        val fresh = authority.issue(source.id, "legado", "1", source.namespace, next.generation)
        manager.logout(first)
        assertTrue(authority.accepts(fresh))
    }

    @Test fun sessionsAreSourceScopedAndLogoutInvalidatesLateResults() {
        val manager = SourceSessionManager()
        val a = manager.begin(source)
        val b = manager.begin(Identifier("test", "source-b"))
        assertTrue(manager.setCookies(a, mapOf("sid" to "a")))
        assertEquals(mapOf("sid" to "a"), manager.current(source).cookies)
        assertTrue(manager.accepts(a))
        val next = manager.logout(a)
        assertFalse(manager.accepts(a))
        assertFalse(manager.setCookies(a, mapOf("sid" to "late")))
        assertTrue(manager.accepts(next))
        assertTrue(manager.accepts(b))
        assertNotEquals(a.generation, next.generation)
        assertTrue(next.cookies.isEmpty())
        val invalidLogout = manager.logout(a)
        assertFalse(invalidLogout.active)
        assertTrue(invalidLogout.cookies.isEmpty())
        assertFalse(manager.accepts(invalidLogout))
    }

    @Test fun logsRedactCredentialLikeQueryValues() {
        val redacted = SourceSessionManager().redact("https://x.test/login?token=secret&ok=yes password=hunter2")
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("hunter2"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test fun sessionsFromAnotherManagerCannotWriteCookies() {
        val first = SourceSessionManager()
        val second = SourceSessionManager()
        val foreign = second.begin(source)
        assertFalse(first.setCookies(foreign, mapOf("sid" to "forged")))
        assertTrue(first.current(source).cookies.isEmpty())
    }
}
