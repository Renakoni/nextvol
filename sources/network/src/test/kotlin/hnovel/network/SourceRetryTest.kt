package hnovel.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRetryTest {
    @get:Rule val directory = TemporaryFolder()

    private class RetryClock {
        val scheduler = TestCoroutineScheduler()
        val waits = Channel<Long>(Channel.UNLIMITED)
        private val epoch = Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
        val policy = HttpRetryPolicy({ epoch + scheduler.currentTime }, { it }) { millis ->
            withContext(UnconfinedTestDispatcher(scheduler)) {
                coroutineScope {
                    // Register the timer before publishing the observation to the test thread.
                    val timer = async(start = CoroutineStart.UNDISPATCHED) { delay(millis) }
                    waits.send(millis)
                    timer.await()
                }
            }
        }
        suspend fun nextWait() = withTimeout(3000) { waits.receive() }
        fun advance(millis: Long) { scheduler.advanceTimeBy(millis); scheduler.runCurrent() }
    }

    private suspend fun fixture(dns: Dns = Dns.SYSTEM, browser: BrowserExecutor? = null,
        limits: BrokerLimits = BrokerLimits(concurrency = 1),
        block: suspend (MockWebServer, SourceSession, RetryClock) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            val clock = RetryClock()
            val route = SourceNetworkRoute(SourceNetworkMode.SystemDefault, dns)
            SourceSession(SourceScope("retry-tests", "source", "legado"),
                listOf(NetworkGrant(server.url("/").toString(), true)), directory.newFolder().toPath(),
                dns, limits, browser = browser, routes = SourceRouteProvider { route },
                retryPolicy = clock.policy).use { session -> block(server, session, clock) }
        }
    }

    private fun request(server: MockWebServer, retry: Int = 1) = BrokerRequest("r", server.url("/chapter").toString(), retry = retry)
    private fun response(result: BrokerResult): BrokerResponse {
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response
    }
    private suspend fun completed(pending: Deferred<BrokerResult>) = withTimeout(3000) { pending.await() }
    private fun path(server: MockWebServer) = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS)).path

    @Test(timeout = 15000) fun taskOwnedReadsDoNotNestHttpOrConnectionRetries() = runBlocking {
        fixture { server, session, clock ->
            val events = java.util.Collections.synchronizedList(mutableListOf<RequestDiagnostic>())
            session.traceRequests { events += it }
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            val owner = RequestRetryContext()
            assertEquals(503, response(withContext(owner) { session.execute(request(server, 3)) }).status)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val failed = withContext(owner) { session.execute(request(server, 3)) } as BrokerResult.Failure
            assertTrue(failed.retryable)
            assertEquals(0, failed.attempt)
            assertEquals(2, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
            assertTrue(owner.replaySafe)
            server.enqueue(MockResponse().setResponseCode(503))
            withContext(owner) { session.execute(request(server, 3).copy(method = "POST", body = "action")) }
            assertFalse(owner.replaySafe)
            assertEquals(3, server.requestCount)
            assertEquals(3, events.count { it.evidence == RequestEvidence.Completed })
            assertEquals(listOf(0, 0, 0), events.filter { it.evidence == RequestEvidence.TransportHeaders }.map { it.attempt })
        }
    }

    @Test(timeout = 10000) fun taskBodyTimeoutsRespectReplaySafetyWithoutRepeatingTheSocketCall() = runBlocking {
        fixture { server, session, clock ->
            // Initialize the client outside the deadline under test.
            server.enqueue(MockResponse().setBody("ready"))
            response(session.execute(request(server, 0).copy(url = server.url("/warmup").toString())))
            for ((index, streaming) in listOf(false, true).withIndex()) {
                // Each read makes progress; only the total budget should stop the response.
                server.enqueue(MockResponse().setBody("a".repeat(32 * 1024))
                    .throttleBody(1024, 100, TimeUnit.MILLISECONDS))
                val received = AtomicInteger()
                val failure = withContext(RequestRetryContext()) {
                    val request = request(server, 3).copy(timeoutMillis = 1000)
                    if (streaming) session.stream(request, RequestCommitGuard { it() }) { _, _, size ->
                        received.addAndGet(size)
                    } else session.execute(request)
                } as BrokerResult.Failure
                assertEquals(failure.toString(), RequestStage.Connect, failure.stage)
                assertEquals(FailureCode.Timeout, failure.code)
                assertEquals(failure.toString(), !streaming, failure.retryable)
                if (streaming) assertTrue("Stream must deliver bytes before timing out", received.get() > 0)
                assertEquals(index + 2, server.requestCount)
                assertTrue(clock.waits.tryReceive().isFailure)
            }
        }
    }

    @Test(timeout = 15000) fun retryAfterWaitReleasesTheNetworkSlotAndDoesNotRunEarly() = runBlocking {
        for (status in listOf(429, 502, 503, 504)) fixture { server, session, clock ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "2"))
            server.enqueue(MockResponse().setBody("other"))
            server.enqueue(MockResponse().setBody("chapter"))
            val pending = async { session.execute(request(server)) }
            assertEquals(2000L, clock.nextWait())
            assertEquals(1, server.requestCount)
            assertEquals("other", response(withTimeout(3000) {
                session.execute(request(server, 0).copy(url = server.url("/other").toString()))
            }).text())
            clock.advance(1999)
            assertEquals(2, server.requestCount)
            clock.advance(1)
            assertEquals("chapter", response(completed(pending)).text())
            assertEquals(listOf("/chapter", "/other", "/chapter"), (1..3).map { path(server) })
            assertEquals(2000L, clock.scheduler.currentTime)
        }
    }

    @Test(timeout = 15000) fun dateAndMalformedRetryAfterUseTheSameControlledRequestPath() = runBlocking {
        for ((header, wait) in listOf("Mon, 28 Sep 2026 00:00:03 GMT" to 3000L, "invalid" to 500L, "0" to 500L)) {
            fixture { server, session, clock ->
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", header))
                server.enqueue(MockResponse().setBody("chapter"))
                val pending = async { session.execute(request(server)) }
                assertEquals(wait, clock.nextWait())
                assertEquals(1, server.requestCount)
                clock.advance(wait)
                assertEquals("chapter", response(completed(pending)).text())
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test(timeout = 10000) fun okhttp503FollowUpsCannotMultiplyTheBrokerBudget() = runBlocking { fixture { server, session, clock ->
        val events = java.util.Collections.synchronizedList(mutableListOf<RequestDiagnostic>())
        session.traceRequests { events += it }
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0")) }
        val pending = async { session.execute(request(server, 3)) }
        for ((index, wait) in listOf(500L, 1000L, 2000L).withIndex()) {
            assertEquals(wait, clock.nextWait())
            assertEquals(index + 1, server.requestCount)
            clock.advance(wait)
        }
        val final = response(completed(pending))
        assertEquals(503, final.status)
        assertEquals(listOf("0"), final.headers.entries.single { it.key.equals("Retry-After", true) }.value)
        assertEquals(4, server.requestCount)
        assertEquals(3500L, clock.scheduler.currentTime)
        assertTrue(clock.waits.tryReceive().isFailure)
        assertEquals(listOf(0, 1, 2, 3), events.filter { it.evidence == RequestEvidence.TransportHeaders }.map { it.attempt })
        assertEquals(1, events.map { it.requestId }.distinct().size)
        assertEquals(1, events.count { it.evidence == RequestEvidence.Completed })
    } }

    @Test(timeout = 10000) fun zeroHttpBudgetDoesNotSpendTheConnectionRecoveryCreditOn503() = runBlocking { fixture { server, session, clock ->
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        assertEquals(503, response(session.execute(request(server, 0))).status)
        assertEquals(1, server.requestCount)
        assertTrue(clock.waits.tryReceive().isFailure)
    } }

    @Test(timeout = 10000) fun theHostCanDisableEvenSafeConnectionRecovery() = runBlocking {
        fixture(limits = BrokerLimits(concurrency = 1, maxRetry = 0)) { server, session, clock ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val failure = session.execute(request(server, 0)) as BrokerResult.Failure
            assertEquals(FailureCode.Network, failure.code)
            assertEquals(0, failure.attempt)
            assertEquals(1, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
    }

    @Test(timeout = 15000) fun longOrOverflowingCooldownsRemainVisibleWithoutAnEarlyRequest() = runBlocking {
        for (header in listOf("6", "99999999999999999999999999", "Tue, 29 Sep 2026 00:00:00 GMT")) {
            fixture { server, session, clock ->
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", header))
                val final = response(session.execute(request(server, 3)))
                assertEquals(503, final.status)
                assertEquals(listOf(header), final.headers.entries.single { it.key.equals("Retry-After", true) }.value)
                assertEquals(1, server.requestCount)
                assertTrue(clock.waits.tryReceive().isFailure)
            }
        }
    }

    @Test(timeout = 15000) fun aPostIsNotReplayedEvenWithAnExplicitRetryBudget() = runBlocking {
        for (status in listOf(429, 503)) fixture { server, session, clock ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
            val final = response(session.execute(request(server, 3).copy(method = "POST", body = "value=1")))
            assertEquals(status, final.status)
            assertEquals(1, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
        fixture { server, session, clock ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertTrue(session.execute(request(server, 3).copy(method = "POST", body = "value=1")) is BrokerResult.Failure)
            assertEquals(1, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
    }

    @Test(timeout = 15000) fun safeConnectionRecoveryConsumesTheSameBudgetAsHttpRetries() = runBlocking {
        for (budget in listOf(1, 2)) fixture { server, session, clock ->
            server.enqueue(MockResponse().setBody("warmup"))
            response(session.execute(request(server, 0)))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("chapter"))
            val pending = async { session.execute(request(server, budget)) }
            assertEquals(500L, clock.nextWait())
            assertEquals(2, server.requestCount)
            clock.advance(500)
            if (budget == 2) {
                assertEquals(1000L, clock.nextWait())
                assertEquals(3, server.requestCount)
                clock.advance(1000)
            }
            assertEquals(if (budget == 1) 503 else 200, response(completed(pending)).status)
            assertEquals(budget + 2, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
    }

    @Test(timeout = 15000) fun permanentResponsesDoNotRetry() = runBlocking {
        for (status in listOf(404, 408, 500)) fixture { server, session, clock ->
            server.enqueue(MockResponse().setResponseCode(status))
            assertEquals(status, response(session.execute(request(server, 3))).status)
            assertEquals(1, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
    }

    @Test(timeout = 15000) fun dnsRecoveryAndExhaustionHaveOneCountedBudget() = runBlocking {
        for (recovers in listOf(true, false)) {
            val lookups = AtomicInteger()
            val dns = Dns {
                if (lookups.incrementAndGet() == 1 || !recovers) throw UnknownHostException("fixture")
                listOf(InetAddress.getByName("127.0.0.1"))
            }
            fixture(dns) { server, session, clock ->
                server.enqueue(MockResponse().setBody("chapter"))
                val pending = async { session.execute(request(server, 2)) }
                assertEquals(500L, clock.nextWait())
                assertEquals(1, lookups.get())
                assertEquals(0, server.requestCount)
                clock.advance(500)
                if (recovers) {
                    assertEquals("chapter", response(completed(pending)).text())
                    assertEquals(2, lookups.get())
                    assertEquals(1, server.requestCount)
                } else {
                    assertEquals(1000L, clock.nextWait())
                    assertEquals(2, lookups.get())
                    clock.advance(1000)
                    val failure = completed(pending) as BrokerResult.Failure
                    assertEquals(FailureCode.Dns, failure.code)
                    assertEquals(2, failure.attempt)
                    assertEquals(3, lookups.get())
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }

    @Test(timeout = 15000) fun documentsAndImagesUseTheSameShortRetryBudget() = runBlocking {
        for (kind in listOf(ResourceKind.Document, ResourceKind.Image)) fixture { server, session, clock ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("content"))
            val pending = async {
                val request = request(server).copy(kind = kind)
                if (kind == ResourceKind.Image) session.loadImage(request) else session.execute(request)
            }
            assertEquals(500L, clock.nextWait())
            assertEquals(1, server.requestCount)
            clock.advance(500)
            assertEquals("content", response(completed(pending)).text())
            assertEquals(2, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
    }

    @Test(timeout = 10000) fun browserActionsDoNotInheritHttpRetries() = runBlocking {
        val calls = AtomicInteger()
        val browser = BrowserExecutor { _, _, _, _, _ ->
            calls.incrementAndGet()
            BrokerResult.Failure(RequestStage.Response, FailureCode.Timeout)
        }
        fixture(browser = browser) { server, session, clock ->
            val result = session.execute(request(server, 3).copy(browser = BrowserOptions(script = "submit()")))
            assertEquals(FailureCode.Timeout, (result as BrokerResult.Failure).code)
            assertEquals(1, calls.get())
            assertEquals(0, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
    }

    @Test(timeout = 10000) fun restored503HeadersStillPassThroughTheResponseSizeLimit() = runBlocking { fixture { server, session, clock ->
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "9".repeat(65536)))
        assertEquals(FailureCode.ResponseTooLarge, (session.execute(request(server, 3)) as BrokerResult.Failure).code)
        assertEquals(1, server.requestCount)
        assertTrue(clock.waits.tryReceive().isFailure)
    } }

    @Test(timeout = 15000) fun cancellationRetirementAndRevokedOwnershipStopTheNextAttempt() = runBlocking {
        for (action in listOf("cancel", "retire", "revoke")) fixture { server, session, clock ->
            val live = AtomicBoolean(true)
            val guard = RequestCommitGuard { commit ->
                if (!live.get()) throw CancellationException("Owner revoked")
                commit()
            }
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "2"))
            val pending = async { session.execute(request(server, 3), guard) }
            assertEquals(2000L, clock.nextWait())
            when (action) {
                "cancel" -> pending.cancelAndJoin()
                "retire" -> session.close()
                "revoke" -> live.set(false)
            }
            clock.advance(5000)
            assertTrue(action, runCatching { completed(pending) }.exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
            assertTrue(clock.waits.tryReceive().isFailure)
        }
    }
}
