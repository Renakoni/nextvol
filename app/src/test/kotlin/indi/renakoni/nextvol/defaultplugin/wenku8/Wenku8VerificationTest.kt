package indi.renakoni.nextvol.defaultplugin.wenku8

import android.content.ContextWrapper
import com.github.michaelbull.result.getOrElse
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import hnovel.network.*
import hnovel.network.SourceSession
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.SourceVerificationCoordinator
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Wenku8VerificationTest {
    @get:Rule val directory = TemporaryFolder()
    private val source = Wenku8Api.METADATA.id
    private val url = "https://www.wenku8.cc/modules/article/articlelist.php?page=1"
    private val target = url.replace("wenku8.cc", "wenku8.net")
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

    @Test fun httpChallengeIsNotRetriedOrReportedAsAnEmptyFeed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setBody(challenge))
            server.start()
            Wenku8Api { SourceNetworkRoute.systemDefault() }.use { api ->
                val provider = Wenku8Discovery(server.url("/").toString()) { address ->
                    api.getWithWenku8Cookie(address).getOrElse { throw it }
                }
                val result = withTimeout(3000) { provider.feed() }
                assertEquals(DiscoveryError.VerificationRequired, result.component2())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun successfulChallengeAndLoginDocumentsAreRejectedButAnEmptyDocumentIsValid() {
        fun response(body: String, address: String = target, status: Int = 200) =
            BrokerResponse(status, address, emptyMap(), body.toByteArray(), "UTF-8", 0)
        assertEquals(BrowserChallengeKind.Cloudflare,
            (runCatching { wenku8Document(response(challenge)) }.exceptionOrNull() as Wenku8Challenge).kind)
        assertEquals(BrowserChallengeKind.Login,
            (runCatching { wenku8Document(response("<input type=password>", "https://www.wenku8.net/login.php")) }
                .exceptionOrNull() as Wenku8Challenge).kind)
        assertEquals(503, (runCatching { wenku8Document(response("<title>Unavailable</title>", status = 503)) }
            .exceptionOrNull() as SourceContentException).httpStatus)
        assertTrue(wenku8Document(response("<html></html>")).select("a[href*=/book/]").isEmpty())
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
                assertEquals(target, request.url)
                assertNull(request.headers["Cookie"])
                assertEquals("jieqiUserInfo=fixture", session.cookie(target))
                if (!options.interactive && !verified) return blocked()
                if (options.interactive) { interactiveCalls++; verified = true }
                return page(target, "<p>文库 • ・ 〜</p>")
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

    @Test fun backgroundVerificationOnlyOpensAfterTheActionAndResumesItsOwner() = runBlocking {
        var interactiveCalls = 0
        var verified = false
        var resumes = 0
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Fixture browser"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                if (options.interactive) { interactiveCalls++; verified = true }
                return if (verified) page(target, "<p>ready</p>") else blocked()
            }
        }
        Fixture(browser).use { fixture ->
            val background = BackgroundSourceRequest { { _, _ -> resumes++ } }
            val failure = withContext(background) { runCatching {
                fixture.recovery.request(source, url, seeds) { nativeChallenge() }
            }.exceptionOrNull() as SourceContentException }
            assertEquals(ContentError.BrowserRequired, failure.code)
            assertEquals(0, interactiveCalls)
            val prompt = fixture.coordinator.prompts.value.single()
            assertFalse(prompt.foreground)
            assertEquals(BrowserChallengeKind.Cloudflare, prompt.kind)
            fixture.coordinator.verifyBackground(prompt.id)
            assertEquals(1, interactiveCalls)
            assertEquals(1, resumes)
            assertTrue(fixture.coordinator.prompts.value.isEmpty())
        }
    }

    @Test fun recreatingTheAdapterDoesNotOverwriteVerifiedCookiesWithBuiltInSeeds() = runBlocking {
        var visits = 0
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Fixture browser"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                assertEquals(if (visits++ == 0) "jieqiUserInfo=fixture" else "jieqiUserInfo=verified", session.cookie(target))
                session.updateNativeBrowserCookies(target,
                    listOf("jieqiUserInfo=verified; Path=/; Secure; HttpOnly; Max-Age=3600"))
                return page(target, "<p>ready</p>")
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
        var verified = false
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Fixture browser"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                if (!options.interactive && !verified) {
                    synchronized(bothBlocked) { if (++blockedCalls == 2) bothBlocked.complete(Unit) }
                    bothBlocked.await()
                    return blocked()
                }
                if (options.interactive) { interactiveCalls++; verified = true }
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

    @Test fun leavingTheForegroundCancelsTheBrowserAndDoesNotResumeHttp() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "Fixture browser"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                if (!options.interactive) return blocked()
                opened.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        Fixture(browser).use { fixture ->
            val ui = ForegroundSourceRequest()
            val pending = async(ui) { fixture.recovery.request(source, url, seeds) { nativeChallenge() } }
            withTimeout(3000) { opened.await() }
            ui.setActive(false)
            withTimeout(3000) { pending.join(); cancelled.await() }
            assertTrue(pending.isCancelled)
            assertFalse(ui.verifying)
            assertTrue(fixture.coordinator.prompts.value.isEmpty())
        }
    }
}
