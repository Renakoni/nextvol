package hnovel.network

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class BrowserIdentityTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun automaticIdentityIsResolvedOnceAndSharedByConcurrentHttpAndBrowserRequests() = runBlocking {
        val calls = AtomicInteger()
        val seen = mutableListOf<String?>()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent(): String { delay(20); return "actual-provider-${calls.incrementAndGet()}" }
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                seen += request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                return BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), byteArrayOf(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
            }
        }
        MockWebServer().use { server ->
            server.start(); repeat(4) { server.enqueue(MockResponse()) }
            val base = server.url("/").toString()
            SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
                val session = broker.open(SourceScope("test", "identity", "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
                coroutineScope { (1..4).map { async { session.execute(BrokerRequest("http-$it", base)) } }.awaitAll() }
                repeat(4) { assertEquals("actual-provider-1", server.takeRequest().getHeader("User-Agent")) }
                repeat(2) { session.execute(BrokerRequest("browser-$it", base, browser = BrowserOptions())) }
                session.execute(BrokerRequest("explicit", base, headers = mapOf("User-Agent" to "compatible-source"), browser = BrowserOptions()))
                assertEquals(listOf("actual-provider-1", "actual-provider-1", "compatible-source"), seen)
                assertEquals("actual-provider-1", session.requestUserAgent(base))
                assertEquals("actual-provider-1", session.webViewUserAgent())
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun redirectFromAnExplicitOriginResolvesTheDestinationDefaultIdentity() = runBlocking {
        val calls = AtomicInteger()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent(): String { calls.incrementAndGet(); return "actual-provider" }
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("No navigation expected")
        }
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            val base = first.url("/").toString()
            val destination = second.url("/").toString()
            for (account in listOf(false, true)) SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
                val session = broker.open(SourceScope("test", "redirect-$account", "legado"), listOf(
                    NetworkGrant(base, true, headers = if (account) emptyMap() else mapOf("User-Agent" to "origin")),
                    NetworkGrant(destination, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
                if (account) session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS,
                    """{"User-Agent":"account"}"""))
                first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination))
                second.enqueue(MockResponse())
                assertTrue(session.execute(BrokerRequest("redirect", base)) is BrokerResult.Success)
                assertEquals(if (account) "account" else "origin", first.takeRequest().getHeader("User-Agent"))
                assertEquals("actual-provider", second.takeRequest().getHeader("User-Agent"))
                assertEquals("actual-provider", session.requestUserAgent(destination))
            }
            assertEquals(2, calls.get())
        } }
    }

    @Test fun originAndLoginIdentitiesDoNotRequireTheDefaultBrowserProvider() = runBlocking {
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent(): String = error("An explicit identity must be independent of the provider")
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("No navigation expected")
        }
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            for (account in listOf(false, true)) SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
                val session = broker.open(SourceScope("test", "explicit-$account", "legado"),
                    listOf(NetworkGrant(base, true, headers = if (account) emptyMap() else mapOf("User-Agent" to "origin"))))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
                if (account) session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS,
                    """{"User-Agent":"account"}"""))
                val expected = if (account) "account" else "origin"
                assertEquals(expected, session.requestUserAgent(base))
                server.enqueue(MockResponse())
                assertTrue(session.execute(BrokerRequest("http", base)) is BrokerResult.Success)
                assertEquals(expected, server.takeRequest().getHeader("User-Agent"))
            }
        }
    }
}
