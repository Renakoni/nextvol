package hnovel.content

import hnovel.network.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RuleSourceHostTest {
    @Test fun assemblyBindsTheSessionBeforeAnyRuleInvocation() {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val definition = source.definition
            val scope = SourceScope("rules", definition.sourceId, definition.profile)
            val session = fixture.broker.open(scope, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            fixture.beforeRun = { _, _ -> error("Assembly must not execute rules") }
            val ticket = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules")
            createRuleSource(definition, ticket, fixture.authority, session, fixture.runner).use {
                SourceBroker(Files.createTempDirectory("rule-host-other-session")).use { other ->
                    val replacement = other.open(scope, emptyList())
                    assertThrows(IllegalStateException::class.java) {
                        createRuleSource(definition, ticket, fixture.authority, replacement, fixture.runner)
                    }
                    assertEquals("", replacement.sourceUrl)
                    assertFalse(replacement.closed)
                }
            }
            val fresh = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules")
            createRuleSource(definition, fresh, fixture.authority, session, fixture.runner).use {
                assertTrue(fixture.authority.accepts(fresh))
                session.close()
                assertFalse(fixture.authority.accepts(fresh))
            }
        } }
    }

    @Test fun mismatchedDefinitionsAndAccountsAreRejectedBeforeConfiguringTheSession() {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val definition = source.definition
            val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val originalUrl = session.sourceUrl
            fixture.beforeRun = { _, _ -> error("Construction must not execute source code") }
            val revision = fixture.authority.issue(definition.sourceId, definition.profile, "other", "rules")
            val account = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules", 1)
            for (ticket in listOf(revision, account)) {
                assertThrows(IllegalArgumentException::class.java) {
                    createRuleSource(definition, ticket, fixture.authority, session, fixture.runner)
                }
                assertFalse(fixture.authority.accepts(ticket))
            }
            assertEquals(originalUrl, session.sourceUrl)
            assertFalse(session.closed)
        } }
    }

    @Test fun parsingAndClosedSessionFailuresDoNotLeakConstructionTickets() {
        RuleSourceFixture().use { fixture -> fixture.source().use { source ->
            val definition = source.definition
            val session = fixture.broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val malformed = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules")
            assertTrue(runCatching {
                createRuleSource(definition.copy(rawJson = "{"), malformed, fixture.authority, session, fixture.runner)
            }.isFailure)
            assertFalse(fixture.authority.accepts(malformed))
            assertFalse(session.closed)
            session.close()
            val closed = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules")
            assertThrows(IllegalStateException::class.java) {
                createRuleSource(definition, closed, fixture.authority, session, fixture.runner)
            }
            assertFalse(fixture.authority.accepts(closed))
        } }
    }
}
