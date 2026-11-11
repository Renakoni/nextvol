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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end observations, not a substitute for StartupTimingMetric or CPU profiling.
 * Clears target app data: run only on a disposable device with the benchmark APK.
 */
@RunWith(AndroidJUnit4::class)
class StartupReadinessTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val arguments = InstrumentationRegistry.getArguments()
    private val fixture = arguments.getString("fixture") ?: "cached"
    private val samples = arguments.getString("samples")?.toInt() ?: 5
    private val pkg = "indi.renakoni.nextvol"
    private val local = fixture == "txt" || fixture == "epub"
    private val title = if (local) "Startup ${fixture.uppercase()} Fixture" else "Benchmark Sample Novel"
    private val body = if (local) "Startup paragraph" else "Benchmark paragraph"

    @Test fun measureReadiness() {
        require(fixture in listOf("empty", "cached", "daily", "txt", "epub"))
        require(samples in 1..20)
        device.pressHome()
        assertTrue(shell("pm clear $pkg").contains("Success"))
        if (Build.VERSION.SDK_INT >= 33) {
            shell("cmd locale set-app-locales $pkg --user 0 --locales en-US")
            shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        }
        shell("settings put secure immersive_mode_confirmations confirmed")
        val seeded = shell("am broadcast -W -n $pkg/.benchmark.BenchmarkFixtureReceiver " +
            "-a $pkg.benchmark.STARTUP_FIXTURE --es fixture $fixture")
        assertTrue(seeded, seeded.contains("startup=SUCCEEDED fixture=$fixture"))
        val config = Configurator.getInstance()
        val idle = config.waitForIdleTimeout
        config.waitForIdleTimeout = 100
        try {
            repeat(samples) { index ->
                shell("am force-stop $pkg")
                shell("logcat -c")
                observe("cold", index, reenter = true)
                val phases = shell("logcat -d -s NextVolStartup:I '*:S'")
                val expected = if (fixture == "empty" || fixture == "cached") 2 else 12
                assertTrue(phases, phases.contains("count=$expected importedFailed=false zlibraryFailed=false"))
                report("StartupPhases fixture=$fixture sample=$index\n$phases")
                val pid = shell("pidof $pkg").trim()
                assertTrue(pid.isNotEmpty())
                device.pressHome()
                observe("hot", index, reenter = false)
                assertEquals("Hot sample must retain the host process", pid, shell("pidof $pkg").trim())
            }
        } finally { config.waitForIdleTimeout = idle }
    }

    private fun observe(mode: String, sample: Int, reenter: Boolean) {
        val start = SystemClock.elapsedRealtimeNanos()
        val launch = shell("am start -W -n $pkg/.MainActivity")
        val shelf = await(By.text("Bookshelf"))
        val controls = SystemClock.elapsedRealtimeNanos()
        click(shelf, By.text("Bookshelf"))
        device.waitForStableInActiveWindow()
        await(By.desc("Sort"))
        await(if (fixture == "empty") By.textContains("add the book") else By.text(title))
        val bookshelf = SystemClock.elapsedRealtimeNanos()
        var resumeMs: Double? = null
        var readableMs: Double? = null
        if (fixture != "empty") {
            click(await(By.text("Reading")), By.text("Reading"))
            device.waitForStableInActiveWindow()
            val resume = await(By.text("Resume Last Reading"))
            val action = SystemClock.elapsedRealtimeNanos()
            click(resume, By.text("Resume Last Reading"))
            await(By.textContains(body))
            val readable = SystemClock.elapsedRealtimeNanos()
            resumeMs = ms(readable - action)
            readableMs = ms(readable - start)
            val again = returnToReading()
            if (reenter) {
                val retry = SystemClock.elapsedRealtimeNanos()
                click(again, By.text("Resume Last Reading"))
                await(By.textContains(body))
                report("StartupSample fixture=$fixture mode=reentry sample=$sample resumeMs=${ms(SystemClock.elapsedRealtimeNanos() - retry)}")
                returnToReading()
            }
        }
        val amTime = launch.lineSequence().firstOrNull { it.startsWith("TotalTime:") }?.substringAfter(':')?.trim()
        report("StartupSample fixture=$fixture mode=$mode sample=$sample launchStartNs=$start " +
            "amTotalMs=$amTime controlsMs=${ms(controls - start)} shelfMs=${ms(bookshelf - start)} " +
            "resumeMs=$resumeMs readableMs=$readableMs")
    }

    private fun returnToReading(): UiObject2 {
        device.pressBack()
        device.waitForStableInActiveWindow()
        // The resume route deliberately includes book details in the back stack.
        await(By.desc("Export"))
        device.pressBack()
        device.waitForStableInActiveWindow()
        return await(By.text("Resume Last Reading"))
    }

    private fun shell(command: String) = device.executeShellCommand(command)
    private fun ms(nanos: Long) = nanos / 1_000_000.0
    private fun report(message: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream", "$message\n") })
    private fun await(selector: BySelector): UiObject2 =
        requireNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing readiness endpoint: $selector" }
    private fun click(element: UiObject2, selector: BySelector) {
        val bounds = try { element.visibleBounds } catch (_: StaleObjectException) {
            // Recomposition can invalidate a node before input is injected. Never retry a tap.
            report("StartupProbe fixture=$fixture retry=stale-node")
            await(selector).visibleBounds
        }
        device.click(bounds.centerX(), bounds.centerY())
    }
}
