package indi.renakoni.nextvol.benchmark.performance

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupProcessIsolationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "indi.renakoni.nextvol"

    @Test fun onlyTheHostProcessInitializesHostDependencies() {
        device.executeShellCommand("am force-stop $pkg")
        val main = probe("BenchmarkFixtureReceiver")
        assertEquals("10/10", main["injected"])
        assertEquals(pkg, main["process"])
        assertEquals(main["appUid"], main["uid"])
        for ((receiver, suffix) in listOf(
            "StartupBrowserProbeReceiver" to ":source_browser",
            "StartupNativeBrowserProbeReceiver" to ":source_browser_native",
        )) {
            val browser = probe(receiver)
            assertEquals("0/10", browser["injected"])
            assertEquals(pkg + suffix, browser["process"])
            assertEquals(main["uid"], browser["uid"])
        }
        val isolated = probe("BenchmarkFixtureReceiver", "--ez isolated true")
        assertEquals("0/10", isolated["injected"])
        // Android may append the isolated service's component name to this prefix.
        val prefix = pkg + ":startup_probe_isolated"
        val process = requireNotNull(isolated["process"])
        assertTrue(process, process == prefix || process.startsWith("$prefix:"))
        assertEquals(main["uid"], isolated["appUid"])
        assertNotEquals(main["uid"], isolated["uid"])
    }

    private fun probe(receiver: String, extra: String = ""): Map<String, String> {
        val result = device.executeShellCommand("am broadcast -W -n $pkg/.benchmark.$receiver " +
            "-a $pkg.benchmark.STARTUP_PROCESS $extra")
        assertTrue(result, result.contains("startup-probe process="))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$result\n") })
        return Regex("([A-Za-z]+)=([^\\s\"]+)").findAll(result)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
