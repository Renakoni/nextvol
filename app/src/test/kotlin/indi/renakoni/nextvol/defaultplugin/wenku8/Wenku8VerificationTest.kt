package indi.renakoni.nextvol.defaultplugin.wenku8

import android.content.ContextWrapper
import com.github.michaelbull.result.getOrElse
import hnovel.network.*
import hnovel.network.SourceSession
import indi.renakoni.nextvol.data.explore.searchFailure
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator
import indi.renakoni.nextvol.defaultplugin.wenku8.book.Wenku8WebsiteDataSource
import indi.renakoni.nextvol.defaultplugin.wenku8.explore.Wenku8Discovery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Wenku8VerificationTest {
    @get:Rule val directory = TemporaryFolder()
    private val source = Wenku8Api.METADATA.id
    private val url = "https://www.wenku8.net/modules/article/articlelist.php?page=1"
    private val challenge = "<title>Just a moment...</title><script>var _cf_chl_opt = {};</script>"
    private val seeds = mapOf("jieqiUserInfo" to "fixture")

    private inner class Fixture(val browser: BrowserExecutor) : AutoCloseable {
        val listings = MutableStateFlow(listOf(SourceListing(Wenku8Api.METADATA, SourceStatus.Ready)))
        val registry = mockk<WebSourceRegistry> { every { sources } returns listings }
        val coordinator = SourceVerificationCoordinator(registry)
        val route = SourceNetworkRoute.systemDefault()
        val networks = mockk<SourceNetworkSettings> { every { forSource(any()) } returns SourceRouteProvider { route } }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = directory.root
        }
        val recovery = Wenku8Verification(context, registry, coordinator, browser, StorageCipher.Plain, networks)
        override fun close() = recovery.close()
    }

    private fun page(url: String, body: String) = BrokerResult.Success(BrokerResponse(0, url, emptyMap(),
        body.toByteArray(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
    private fun blocked() = BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired,
        challenge = BrowserChallengeKind.Cloudflare)
    private fun nativeChallenge(): Nothing = throw Wenku8Challenge(BrowserChallengeKind.Cloudflare, 403)

    @Test fun httpErrorsRetainTheirClassificationWithoutRetries() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody(challenge))
            server.enqueue(MockResponse().setResponseCode(404).setBody("<title>Not found</title>"))
            server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody(challenge))
            server.start()
            Wenku8Api { SourceNetworkRoute.systemDefault() }.use { api ->
                val provider = Wenku8Discovery(server.url("/").toString()) { address ->
                    api.getWithWenku8Cookie(address).getOrElse { throw it }
                }
                val result = withTimeout(3000) { provider.feed() }
                assertEquals(DiscoveryError.VerificationRequired, result.component2())
                assertEquals(1, server.requestCount)
                assertEquals(DiscoveryError.Network, withTimeout(3000) { provider.feed() }.component2())
                assertEquals(2, server.requestCount)
                val search = Wenku8WebsiteDataSource(server.url("/").toString().trimEnd('/'), api)
                val failure = runCatching { withTimeout(3000) { search.searchPage("articlename", "fixture", 1) } }
                    .exceptionOrNull()
                assertEquals(DiscoveryError.VerificationRequired, searchFailure(checkNotNull(failure)).error)
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun foregroundVerificationConsumesItsDocumentAndRetainsTheBrowserSession() = runBlocking {
        var verified = false
        var interactiveCalls = 0
        var browserCalls = 0
        var httpCalls = 0
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Fixture browser"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                browserCalls++
                assertEquals(url, request.url)
                assertNull(request.headers["Cookie"])
                assertEquals("jieqiUserInfo=fixture", session.cookie(url))
                if (!options.interactive && !verified) return blocked()
                if (options.interactive) { interactiveCalls++; verified = true }
                return page(url, "<p>文库 • ・ 〜</p>")
            }
        }
        Fixture(browser).use { fixture ->
            val ui = ForegroundSourceRequest()
            val document = withContext(ui) { fixture.recovery.request(source, url, seeds) { httpCalls++; nativeChallenge() } }
            assertEquals("文库 • ・ 〜", document.text())
            assertEquals(2, browserCalls) // Quiet detection, then the exact interactive response; no refetch.
            assertEquals(1, interactiveCalls)
            assertEquals(1, httpCalls)
            assertFalse(ui.verifying)
            assertTrue(fixture.coordinator.prompts.value.isEmpty())
            assertEquals("文库 • ・ 〜", fixture.recovery.request(source, url, seeds) { error("Must retain browser reads") }.text())
            assertEquals(3, browserCalls)
        }
    }

    @Test fun recreatingTheAdapterDoesNotOverwriteVerifiedCookiesWithBuiltInSeeds() = runBlocking {
        var visits = 0
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Fixture browser"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                assertEquals(if (visits++ == 0) "jieqiUserInfo=fixture" else "jieqiUserInfo=verified", session.cookie(url))
                session.updateNativeBrowserCookies(url,
                    listOf("jieqiUserInfo=verified; Path=/; Secure; HttpOnly; Max-Age=3600"))
                return page(url, "<p>ready</p>")
            }
        }
        repeat(2) {
            Fixture(browser).use { fixture ->
                assertEquals("ready", fixture.recovery.request(source, url, seeds) { nativeChallenge() }.text())
            }
        }
        assertEquals(2, visits)
    }

    @Test fun concurrentRequestsShareVerificationButKeepTheirOwnDocuments() = runBlocking {
        val bothBlocked = CompletableDeferred<Unit>()
        var blockedCalls = 0
        var interactiveCalls = 0
        val verified = AtomicBoolean()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Fixture browser"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                if (!options.interactive && !verified.get()) {
                    synchronized(bothBlocked) { if (++blockedCalls == 2) bothBlocked.complete(Unit) }
                    bothBlocked.await()
                    return blocked()
                }
                if (options.interactive) { interactiveCalls++; verified.set(true) }
                return page(request.url, "<p>${request.url.substringAfter("page=")}</p>")
            }
        }
        Fixture(browser).use { fixture ->
            withTimeout(5000) {
                val first = async(ForegroundSourceRequest()) { fixture.recovery.request(source, url, seeds) { nativeChallenge() } }
                val second = async(ForegroundSourceRequest()) {
                    fixture.recovery.request(source, url.replace("page=1", "page=2"), seeds) { nativeChallenge() }
                }
                assertEquals("1", first.await().text())
                assertEquals("2", second.await().text())
            }
            assertEquals(1, interactiveCalls)
            assertTrue(fixture.coordinator.prompts.value.isEmpty())
        }
    }
}
