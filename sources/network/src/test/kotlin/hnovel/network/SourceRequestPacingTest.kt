package hnovel.network

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap

class SourceRequestPacingTest {
    @get:Rule val directory = TemporaryFolder()
    private val guard = RequestCommitGuard { it() }

    private suspend fun fixture(browser: BrowserExecutor? = null,
        block: suspend (SourceBroker, MockWebServer, MutableMap<String, Long>) -> Unit) {
        MockWebServer().use { server ->
            val starts = ConcurrentHashMap<String, Long>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    starts[request.path!!] = System.nanoTime() / 1_000_000
                    return MockResponse().setBody("fixture")
                }
            }
            server.start()
            SourceBroker(directory.newFolder().toPath(), browser = browser).use { block(it, server, starts) }
        }
    }

    private fun session(broker: SourceBroker, server: MockWebServer, rate: String, generation: Long = 0,
        id: String = "source") = broker.open(SourceScope("pacing-tests", id, "legado", generation),
        listOf(NetworkGrant(server.url("/").toString(), true))).apply {
            configureSource(server.url("/").toString(), true, concurrentRate = rate)
        }

    private suspend fun read(session: SourceSession, server: MockWebServer, path: String) {
        assertTrue(session.execute(BrokerRequest(path, server.url(path).toString())) is BrokerResult.Success)
    }

    @Test fun concurrentHttpRequestsShareOneAdmissionWindow() = runBlocking { fixture { broker, server, starts ->
        val account = session(broker, server, "1/500")
        (1..3).map { async { read(account, server, "/$it") } }.awaitAll()
        val times = starts.values.sorted()
        assertEquals(3, times.size)
        times.zipWithNext().forEach { (a, b) -> assertTrue("HTTP gap: ${b - a}", b - a >= 400) }
    } }

    @Test fun retriesArePacedWhileRedirectsRemainOneLogicalRequest() = runBlocking { fixture { broker, server, starts ->
        var attempts = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = if (request.path == "/retry") "/retry${++attempts}" else request.path!!
                starts[key] = System.nanoTime() / 1_000_000
                return when (key) {
                    "/retry1" -> MockResponse().setResponseCode(503)
                    "/retry2" -> MockResponse().setResponseCode(302).setHeader("Location", "/final")
                    else -> MockResponse().setBody("fixture")
                }
            }
        }
        val account = session(broker, server, "500")
        assertTrue(account.execute(BrokerRequest("retry", server.url("/retry").toString(), retry = 1)) is BrokerResult.Success)
        assertTrue(starts.getValue("/retry2") - starts.getValue("/retry1") >= 400)
        // A long interval makes an accidental redirect admission fail within the request budget.
        val other = session(broker, server, "60000", id = "redirect")
        attempts = 1
        assertTrue(other.execute(BrokerRequest("redirect", server.url("/retry").toString(), timeoutMillis = 2000)) is BrokerResult.Success)
    } }

    @Test fun cacheDataAndBrowserSubrequestsDoNotConsumeSourceAdmissions() = runBlocking { fixture { broker, server, _ ->
        val account = session(broker, server, "60000")
        val request = BrokerRequest("cached", server.url("/cached").toString(), cache = CacheMode.ReadThrough, timeoutMillis = 1500)
        assertTrue(account.execute(request) is BrokerResult.Success)
        assertTrue((account.execute(request) as BrokerResult.Success).response.fromCache)
        assertTrue(account.execute(request.copy(url = "data:text/plain;base64,Zml4dHVyZQ==", cache = CacheMode.Disabled)) is BrokerResult.Success)
        assertTrue(account.executeHttp(request.copy(url = server.url("/subrequest").toString(), cache = CacheMode.Disabled), guard) is BrokerResult.Success)
        assertTrue(account.loadImage(request.copy(url = server.url("/cover").toString(), kind = ResourceKind.Image)) is BrokerResult.Success)
        assertEquals(3, server.requestCount)
        val failure = account.execute(request.copy(url = server.url("/waiting").toString(),
            timeoutMillis = 100)) as BrokerResult.Failure
        assertEquals(FailureCode.Timeout, failure.code)
        assertEquals(RequestStage.Queue, failure.stage)
        assertFalse(failure.toString(), failure.retryable)
        assertEquals(3, server.requestCount)
    } }

    @Test fun cancellationAndAccountRetirementStopQueuedDispatches() = runBlocking { fixture { broker, server, starts ->
        val account = session(broker, server, "60000")
        read(account, server, "/first")
        val cancelled = launch { read(account, server, "/cancelled") }
        delay(100)
        cancelled.cancelAndJoin()
        val retired = async { runCatching { read(account, server, "/retired") } }
        delay(100)
        val next = session(broker, server, "60000", generation = 1)
        assertTrue(withTimeout(2000) { retired.await() }.exceptionOrNull() is CancellationException)
        withTimeout(2000) { read(next, server, "/next") }
        assertEquals(setOf("/first", "/next"), starts.keys)
    } }

    @Test fun browserWaitDoesNotReserveCreditAndHttpUsesTheSameGate() = runBlocking {
        val serial = Mutex(locked = true)
        val queued = CompletableDeferred<Unit>()
        val browser = BrowserExecutor { account, request, _, commit, _ ->
            queued.complete(Unit)
            serial.withLock {
                account.awaitBrowserAdmission()
                account.executeHttp(request, commit)
            }
        }
        fixture(browser) { broker, server, starts ->
            val account = session(broker, server, "500")
            val pending = (1..2).map { index -> async {
                assertTrue(account.execute(BrokerRequest("browser$index", server.url("/browser$index").toString(),
                    browser = BrowserOptions())) is BrokerResult.Success)
            } }
            queued.await()
            read(account, server, "/http")
            delay(1200) // Stand-in for a long interactive browser occupying the execution slot.
            assertEquals(setOf("/http"), starts.keys)
            serial.unlock()
            pending.awaitAll()
            read(account, server, "/after")
            val browserStarts = starts.filterKeys { it.startsWith("/browser") }.values.sorted()
            assertTrue(browserStarts[1] - browserStarts[0] >= 400)
            assertTrue(starts.getValue("/after") - browserStarts[1] >= 400)
        }
    }
}
