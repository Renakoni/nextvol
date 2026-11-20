package hnovel.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class SourceContentCodingTest {
    @get:Rule val directory = TemporaryFolder()
    private val legacyHeaders = mapOf("accept-encoding" to "gzip, deflate, br, zstd")
    private fun compressed(text: String): MockResponse {
        val bytes = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        return MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
            .setHeader("Content-Encoding", "gzip").setBody(Buffer().write(bytes))
    }

    @Test fun sourceCompressionHeadersStillProduceDecodedRuleText() = runBlocking {
        MockWebServer().use { server -> SourceBroker(directory.root.toPath()).use { broker ->
            server.start()
            val url = server.url("/").toString()
            val session = broker.open(SourceScope("test", "coding", "legado"), listOf(NetworkGrant(url, true)))
            val text = """{"body":"fixture text"}"""
            server.enqueue(compressed(text))
            val result = session.execute(BrokerRequest("explicit", url, headers = legacyHeaders)) as BrokerResult.Success
            assertEquals(text, result.response.text())
            assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
            assertFalse(result.response.headers.keys.any { it.equals("Content-Encoding", true) })
        } }
    }

    @Test fun bodyLimitAppliesToExpandedBytesEvenWithSourceCompressionHeaders() = runBlocking {
        MockWebServer().use { server -> SourceBroker(directory.root.toPath()).use { broker ->
            server.start()
            val url = server.url("/").toString()
            val session = broker.open(SourceScope("test", "limit", "legado"), listOf(NetworkGrant(url, true)))
            server.enqueue(compressed("x".repeat(4096)))
            val result = session.execute(BrokerRequest("limited", url, headers = legacyHeaders, maxResponseBytes = 128))
            assertTrue(result is BrokerResult.Failure)
            assertEquals(FailureCode.ResponseTooLarge, (result as BrokerResult.Failure).code)
        } }
    }
}
