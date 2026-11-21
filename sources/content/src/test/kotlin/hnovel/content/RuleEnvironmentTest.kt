package hnovel.content

import hnovel.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleEnvironmentTest {
    @Test fun browserDefaultIsDeviceProvidedWhileHttpQueriesReflectSourceConfiguration() = runBlocking {
        val browserUa = "Device WebView-UA"
        val seen = mutableListOf<String?>()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = browserUa
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                seen += request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                return BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), "<li><a href='/book'><h2>Book</h2></a><b>Author</b></li>".toByteArray(),
                    "UTF-8", 0, kind = ResponseKind.BrowserDocument))
            }
        }
        RuleSourceFixture(browser).use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "browserRead" to JsonPrimitive(true), "header" to JsonPrimitive("{\"User-Agent\":\"Browser-Override\"}"),
                "loginCheckJs" to JsonPrimitive("""
                    if(java.getWebViewUA()!==${JsonPrimitive(browserUa)} || java.getUserAgent()!=='Browser-Override') throw 'UA'; result;
                """.trimIndent())
            )) }.use { source ->
                assertEquals(1, source.search("test").size)
                assertEquals(listOf("Browser-Override"), seen)
                assertEquals(0, fixture.server.requestCount)
            }
        }
    }

    @Test fun queryingResponseUserAgentDoesNotExecuteTheHeaderScriptAgain() = runBlocking {
        RuleSourceFixture().use { fixture ->
            fixture.source { raw -> JsonObject(raw + mapOf(
                "header" to JsonPrimitive("""@js:
                    var count=Number(source.get('headerCalls')||0)+1; source.put('headerCalls',String(count));
                    JSON.stringify({'User-Agent':'Header-'+count});
                """.trimIndent()),
                "loginCheckJs" to JsonPrimitive("""
                    if(java.getUserAgent()!=='Header-1' || java.getUserAgent()!=='Header-1' || source.get('headerCalls')!=='1') throw 'header ran again'; result;
                """.trimIndent())
            )) }.use { source ->
                assertEquals(1, source.search("key").size)
                assertEquals("Header-1", fixture.server.takeRequest().getHeader("User-Agent"))
                assertEquals(1, fixture.server.requestCount)
            }
        }
    }

}
