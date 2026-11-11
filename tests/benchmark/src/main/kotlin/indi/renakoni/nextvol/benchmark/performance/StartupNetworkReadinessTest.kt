package indi.renakoni.nextvol.benchmark.performance

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.waitForStableInActiveWindow
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Clears target data. Use only the benchmark APK on a disposable device. */
@RunWith(AndroidJUnit4::class)
class StartupNetworkReadinessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "indi.renakoni.nextvol"
    private val body = "Startup network paragraph"

    @Test fun sourceReadinessDoesNotPretendPendingOrFailedContentIsReadable() {
        val arguments = InstrumentationRegistry.getArguments()
        val scenario = arguments.getString("scenario") ?: "delayed"
        val samples = arguments.getString("samples")?.toInt() ?: 3
        require(scenario in listOf("trusted", "delayed", "failed", "unavailable"))
        require(samples in 1..10)
        val config = Configurator.getInstance()
        val previous = config.waitForIdleTimeout
        config.waitForIdleTimeout = 100
        try { repeat(samples) { measure(scenario, it) } }
        finally { config.waitForIdleTimeout = previous }
    }

    private fun measure(scenario: String, sample: Int) {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fail = AtomicBoolean(scenario == "failed")
        val requestNs = AtomicLong()
        val requests = ConcurrentLinkedQueue<String>()
        val dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests.add(path)
                val metadata = when (path) {
                    "/book" -> "<h1>Benchmark Sample Novel</h1><a href='/toc'>Contents</a>"
                    "/toc" -> "<li><a href='/benchmark-chapter-1'>Benchmark Chapter One</a></li>" +
                        "<li><a href='/benchmark-chapter-2'>Benchmark Chapter Two</a></li>"
                    else -> null
                }
                if (metadata != null) return MockResponse().setBody(metadata)
                if (path !in listOf("/benchmark-chapter-1", "/benchmark-chapter-2"))
                    return MockResponse().setResponseCode(404)
                requestNs.compareAndSet(0, now())
                received.countDown()
                if (fail.get()) return MockResponse().setResponseCode(503).setBody("Controlled failure")
                if (scenario == "trusted" || scenario == "delayed")
                    check(release.await(30, TimeUnit.SECONDS)) { "Response gate was not released" }
                return MockResponse().setHeader("Content-Type", "text/html")
                    .setBody((1..30).joinToString("", "<article>", "</article>") {
                        "<p>$body $it. Controlled local response.</p>"
                    })
            }
        }
        fun startServer(port: Int = 0) = MockWebServer().apply {
            this.dispatcher = dispatcher
            start(InetAddress.getByName("127.0.0.1"), port)
        }
        var server = startServer()
        try {
            device.pressHome()
            assertTrue(shell("pm clear $pkg").contains("Success"))
            if (Build.VERSION.SDK_INT >= 33) {
                shell("cmd locale set-app-locales $pkg --user 0 --locales en-US")
                shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            }
            shell("settings put secure immersive_mode_confirmations confirmed")
            val trusted = scenario == "trusted"
            val seeded = shell("am broadcast -W -n $pkg/.benchmark.BenchmarkFixtureReceiver " +
                "-a $pkg.benchmark.STARTUP_NETWORK --ei port ${server.port} --ez cached $trusted")
            assertTrue(seeded, seeded.contains("startup-network=SUCCEEDED cached=$trusted"))
            shell("am force-stop $pkg")
            shell("logcat -c")
            val startNs = now()
            shell("am start -W -n $pkg/.MainActivity")
            await(By.text("Resume Last Reading"))
            device.waitForStableInActiveWindow()
            val phases = shell("logcat -d -s NextVolStartup:I '*:S'")
            val snapshot = Regex("""snapshot timeNs=(\d+) count=3 importedFailed=false zlibraryFailed=false""")
                .find(phases)
            assertNotNull(phases, snapshot)
            val snapshotNs = requireNotNull(snapshot).groupValues[1].toLong()
            assertTrue(snapshotNs >= startNs)
            assertEquals("Restoring a source must not fetch its chapter", 0, server.requestCount)
            val port = server.port
            if (scenario == "unavailable") server.shutdown()
            val actionNs = now()
            assertTrue(snapshotNs < actionNs)
            click(By.text("Resume Last Reading"))
            var releaseNs: Long? = null
            var errorNs: Long? = null
            var retryNs: Long? = null
            when (scenario) {
                "trusted" -> {
                    await(By.textContains("Benchmark paragraph"))
                    assertEquals("Trusted chapter and prefetch must not need HTTP", 0, server.requestCount)
                }
                "delayed" -> {
                    assertTrue("Chapter request never reached the local server", received.await(20, TimeUnit.SECONDS))
                    assertFalse("Uncached content appeared before the response", device.hasObject(By.textContains(body)))
                    releaseNs = now()
                    release.countDown()
                    await(By.textContains(body))
                }
                else -> {
                    await(By.text("Retry"))
                    errorNs = now()
                    assertFalse(device.hasObject(By.textContains(body)))
                    if (scenario == "failed") {
                        assertTrue(server.requestCount > 0)
                        fail.set(false)
                    } else server = startServer(port)
                    retryNs = now()
                    click(By.text("Retry"))
                    await(By.textContains(body))
                    assertTrue(server.requestCount > 0)
                }
            }
            val readableNs = now()
            val completedPhases = shell("logcat -d -s NextVolStartup:I '*:S'")
            val runtime = Regex("""end phase=source-runtime source=.* status=Ready timeNs=(\d+) durationNs=(\d+)""").find(completedPhases)
            report("StartupNetworkSample scenario=$scenario sample=$sample startNs=$startNs snapshotNs=$snapshotNs " +
                "runtimeReadyNs=${runtime?.groupValues?.get(1)} runtimeDurationNs=${runtime?.groupValues?.get(2)} " +
                "actionNs=$actionNs requestNs=${requestNs.get().takeIf { it > 0 }} releaseNs=$releaseNs " +
                "errorNs=$errorNs retryNs=$retryNs readableNs=$readableNs serverRequests=${server.requestCount}")
            report("StartupNetworkPhases scenario=$scenario sample=$sample\n$completedPhases")
            report("StartupNetworkRequests scenario=$scenario sample=$sample paths=$requests")
        } catch (error: Throwable) {
            report("StartupNetworkFailure scenario=$scenario sample=$sample paths=$requests")
            val hierarchy = ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            report(hierarchy.toString("UTF-8"))
            throw error
        } finally {
            release.countDown()
            shell("am force-stop $pkg")
            server.shutdown()
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun shell(command: String) = device.executeShellCommand(command)
    private fun report(value: String) = instrumentation.sendStatus(0,
        Bundle().apply { putString("stream", "$value\n") })
    private fun await(selector: BySelector): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), 30_000)) { "Missing endpoint: $selector" }
    private fun click(selector: BySelector) {
        val bounds = try { await(selector).visibleBounds }
        catch (_: StaleObjectException) { await(selector).visibleBounds }
        device.click(bounds.centerX(), bounds.centerY())
    }
}
