package hnovel.content

import hnovel.execution.*
import hnovel.imports.*
import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.file.Files

class NetworkFailureTest {
    @Test fun searchCanEnrichATwentySixResultBatch() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { raw -> JsonObject(raw + ("ruleSearch" to JsonObject(
                raw.getValue("ruleSearch").jsonObject + ("bookList" to JsonPrimitive(
                    "<js>java.ajaxAll(Array(26).fill(baseUrl));result</js>li"))))) }).use { source ->
                assertEquals(1, source.search("title").size)
                assertEquals(27, fixture.server.requestCount)
            }
        }
    }

    @Test fun repeatedSettingsReadsDoNotBlockSearch() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { JsonObject(it + ("searchUrl" to JsonPrimitive(
                "@js:for(var i=0;i<128;i++)source.get('fixture');'/search'"))) }).use { source ->
                assertEquals(1, source.search("title").size)
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

    @Test fun exhaustedHostCallsAreReportedAsLimitsWithoutRequestingLogin() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source(customize = { JsonObject(it + ("searchUrl" to JsonPrimitive(
                "@js:for(var i=0;i<65;i++)source.get('fixture'+i);'/search'"))) }).use { source ->
                val failure = runCatching { source.search("title") }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Limit, failure.code)
                assertEquals("searchUrl", failure.field)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    private suspend fun checkFailure(dns: Dns, expected: String, script: Boolean, target: String = "/search") {
        RuleSourceFixture().use { fixture ->
            val store = SourceDefinitionStore(Files.createTempDirectory("network-definitions"))
            val importer = SourceDefinitionImporter(store)
            val rule = if (script) "@js:java.ajax(${JsonPrimitive(target)})" else target
            val raw = JsonObject(fixture.raw() + mapOf("bookSourceUrl" to JsonPrimitive("https://source.invalid/"),
                "searchUrl" to JsonPrimitive(rule)))
            val preview = importer.preview(raw.toString(), LEGADO_PROFILE)
            assertNull(importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
            val definition = store.list().single()
            SourceBroker(Files.createTempDirectory("network-broker"), dns).use { broker ->
                val session = broker.open(SourceScope("rules", definition.sourceId, definition.profile),
                    listOf(NetworkGrant("https://source.invalid/")))
                val identity = fixture.authority.issue(definition.sourceId, definition.profile, definition.contentDigest, "rules")
                createRuleSource(definition, identity, fixture.authority, session, fixture.runner).use { source ->
                    val failure = runCatching { source.search("title") }.exceptionOrNull() as SourceContentException
                    assertEquals("script=$script", expected, failure.code.name)
                    assertEquals("searchUrl", failure.field)
                    assertEquals(0, fixture.server.requestCount)
                }
            }
        }
    }

    @Test fun directAndScriptRequestsPreserveFakeIpDenial() = runBlocking {
        for (script in listOf(false, true)) checkFailure(Dns { listOf(InetAddress.getByName("198.18.0.7")) }, "AddressDenied", script)
    }

    @Test fun directAndScriptRequestsPreserveDnsFailure() = runBlocking {
        for (script in listOf(false, true)) checkFailure(Dns { throw UnknownHostException() }, "Dns", script)
    }

    @Test fun missingOriginIsNotReportedAsDnsOrAddressFailure() = runBlocking {
        for (script in listOf(false, true)) checkFailure(Dns { error("Unapproved origin must not resolve") },
            "PermissionDenied", script, "https://unapproved.invalid/search")
    }

    @Test fun aHandledNetworkFailureDoesNotReplaceALaterRuleOrBridgeError() = runBlocking {
        RuleSourceFixture().use { fixture ->
            for ((tail, expected) in listOf("throw 'source failure'" to ContentError.InvalidRule,
                "host.call('unknown.operation')" to ContentError.PermissionDenied)) {
                fixture.source(customize = { JsonObject(it + ("searchUrl" to JsonPrimitive(
                    "@js:try { java.ajax('https://unapproved.invalid') } catch(e) {};$tail"))) }).use { source ->
                    val failure = runCatching { source.search("title") }.exceptionOrNull() as SourceContentException
                    assertEquals(expected, failure.code)
                }
            }
        }
    }
}
