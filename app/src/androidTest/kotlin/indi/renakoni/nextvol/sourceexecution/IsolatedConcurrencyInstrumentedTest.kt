package indi.renakoni.nextvol.sourceexecution

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.*
import hnovel.execution.FailureCode
import hnovel.network.*
import hnovel.rules.OutputKind
import hnovel.rules.RuleValue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** The barriers are reached by scripts inside real isolated Binder workers, not a mock executor. */
@RunWith(AndroidJUnit4::class)
class IsolatedConcurrencyInstrumentedTest {
    private class BlockingBrowser : BrowserExecutor {
        val entered = Channel<Int>(Channel.UNLIMITED)
        val releases = List(16) { CompletableDeferred<String>() }
        val calls = AtomicInteger()
        private suspend fun barrier(): String {
            val index = calls.getAndIncrement()
            entered.send(index)
            return releases[index].await()
        }
        override suspend fun defaultUserAgent(): String = barrier()
        // Stateful scripts need a fresh host call; read-only scripts only admit
        // UA lookup and use distinct sessions when testing multiple workers.
        override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
            guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
            val body = barrier().toByteArray()
            guard.commit {}
            return BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), body,
                "UTF-8", 0, kind = ResponseKind.BrowserDocument))
        }
    }

    private data class Source(val identity: ExecutionIdentity, val session: SourceSession)
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "isolated-concurrency-${System.nanoTime()}")
        val authority = ExecutionAuthority()
        val browser = BlockingBrowser()
        val sessions = SourceBroker(root.toPath(), browser = browser)
        val bindings = ConcurrentLinkedQueue<ComponentName>()
        val retirements = ConcurrentLinkedQueue<ComponentName>()
        val connections = java.util.concurrent.ConcurrentHashMap<ServiceConnection, ComponentName>()
        val executor = AndroidIsolatedExecutor(object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
                super.bindService(service, conn, flags).also { if (it) {
                    val component = checkNotNull(service.component)
                    bindings.add(component)
                    connections[conn] = component
                } }
            override fun unbindService(conn: ServiceConnection) {
                super.unbindService(conn)
                connections.remove(conn)?.let(retirements::add)
            }
        }, authority)
        fun source(name: String) = Source(authority.issue(name, "legado", "1", "concurrency"),
            sessions.open(SourceScope("concurrency", name, "legado"), listOf(NetworkGrant("https://concurrency.example"))))
        suspend fun execute(source: Source, readOnly: Boolean = true, timeout: Long = 30000,
            rule: String = if (readOnly) "@js:java.getWebViewUA()"
                else "@js:java.webView('', 'https://concurrency.example/', '')"): ExecutionResult {
            val limits = ExecutionLimits(timeoutMillis = timeout)
            return SourceExecutionBroker(source.identity, authority, source.session, limits).use { broker ->
                executor.execute(source.identity, ExecutionTask.Rule(rule, RuleValue.Empty,
                    output = OutputKind.Text, readOnly = readOnly), limits, broker)
            }
        }
    }

    private suspend fun fixture(block: suspend CoroutineScope.(Fixture) -> Unit) = coroutineScope {
        val fixture = Fixture()
        try { block(fixture) }
        finally {
            fixture.browser.releases.forEach { it.complete("released") }
            fixture.executor.close()
            fixture.sessions.close()
            fixture.root.deleteRecursively()
        }
    }

    private fun assertValue(expected: String, result: ExecutionResult) {
        assertTrue(result.toString(), result is ExecutionResult.Success)
        assertEquals(RuleValue.Text(expected), Json.decodeFromString(ExecutedRule.serializer(),
            (result as ExecutionResult.Success).output).value)
    }

    @Test fun independentRuleFinishesWhileSharedWorkerWaitsAndStatefulCallsStayOrdered() = runBlocking {
        fixture { fixture ->
            val source = fixture.source("same-source")
            val first = async { fixture.execute(source, readOnly = false) }
            assertEquals(0, withTimeout(20000) { fixture.browser.entered.receive() })
            val independent = async { fixture.execute(source) }
            assertEquals(1, withTimeout(20000) { fixture.browser.entered.receive() })
            val second = async { fixture.execute(source, readOnly = false) }
            assertNull(withTimeoutOrNull(250) { fixture.browser.entered.receive() })
            fixture.browser.releases[1].complete("independent")
            assertValue("independent", withTimeout(5000) { independent.await() })
            assertFalse(first.isCompleted)
            fixture.browser.releases[0].complete("first")
            assertValue("first", first.await())
            assertEquals(2, withTimeout(5000) { fixture.browser.entered.receive() })
            fixture.browser.releases[2].complete("second")
            assertValue("second", second.await())
        }
    }

    @Test fun poolBoundsExecutionAndCancellationOrRevocationCannotLeakPermits() = runBlocking {
        fixture { fixture ->
            val source = fixture.source("parallel-source")
            val count = fixture.executor.parallelism
            val running = List(count) { index -> async { fixture.execute(fixture.source("parallel-$index")) } }
            withTimeout(25000) { repeat(count) { fixture.browser.entered.receive() } }
            val cancelled = async { fixture.execute(fixture.source("cancelled")) }
            assertNull(withTimeoutOrNull(250) { fixture.browser.entered.receive() })
            cancelled.cancelAndJoin()
            withTimeout(5000) { running.first().cancelAndJoin() }
            assertTrue(running.drop(1).all { !it.isCompleted })
            val replacement = async { fixture.execute(fixture.source("replacement")) }
            assertEquals(count, withTimeout(15000) { fixture.browser.entered.receive() })
            val retiredSource = fixture.source("retired-while-queued")
            val retired = async { fixture.execute(retiredSource) }
            assertNull(withTimeoutOrNull(250) { fixture.browser.entered.receive() })
            fixture.authority.revoke(retiredSource.identity)
            repeat(count) { fixture.browser.releases[it].complete("original") }
            running.drop(1).awaitAll().forEach { assertValue("original", it) }
            assertEquals(ExecutionResult.Failure(FailureCode.Revoked), withTimeout(5000) { retired.await() })
            fixture.browser.releases[count].complete("replacement")
            assertValue("replacement", replacement.await())
            assertEquals(count + 1, fixture.browser.calls.get())
            assertValue("reused", fixture.execute(source, rule = "@js:'reused'"))
        }
    }

    @Test fun deadlineRetiresOnlyTheBlockedWorkerAndAnotherSourceKeepsItsResult() = runBlocking {
        fixture { fixture ->
            assumeTrue(fixture.executor.parallelism >= 2)
            val timedSource = fixture.source("timed")
            val otherSource = fixture.source("healthy")
            val timed = async { fixture.execute(timedSource, timeout = 5000) }
            assertEquals(0, withTimeout(20000) { fixture.browser.entered.receive() })
            val healthy = async { fixture.execute(otherSource) }
            assertEquals(1, withTimeout(20000) { fixture.browser.entered.receive() })
            assertEquals(ExecutionResult.Failure(FailureCode.Timeout), withTimeout(10000) { timed.await() })
            assertFalse(healthy.isCompleted)
            assertValue("next", fixture.execute(timedSource, rule = "@js:'next'"))
            fixture.browser.releases[1].complete("healthy")
            assertValue("healthy", healthy.await())
        }
    }
}
