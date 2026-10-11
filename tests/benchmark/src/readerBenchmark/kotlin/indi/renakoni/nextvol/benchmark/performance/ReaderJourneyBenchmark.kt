package indi.renakoni.nextvol.benchmark.performance

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Report-only timings; correctness failures are failures, noisy frame tails are not budgets. */
@LargeTest
@OptIn(androidx.benchmark.macro.ExperimentalMetricApi::class)
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class ReaderJourneyBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val iterations = InstrumentationRegistry.getArguments().getString("readerIterations", "5").toInt()
    private var idleTimeout = 0L

    @Before fun configure() {
        require(iterations > 0)
        idleTimeout = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().waitForIdleTimeout = 0
    }
    @After fun restore() { Configurator.getInstance().waitForIdleTimeout = idleTimeout }

    @Test fun coldContinueReading() = startup(StartupMode.COLD)
    @Test fun warmContinueReading() = startup(StartupMode.WARM)
    @Test fun longChapterFirstTextAndFrames() = startup(StartupMode.COLD, "long")
    @Test fun oversizedParagraphFirstTextAndFrames() = startup(StartupMode.COLD, "paragraph")
    @Test fun mixedContentFirstTextAndFrames() = startup(StartupMode.COLD, "mixed")
    @Test fun thousandComponentsFirstTextAndFrames() = startup(StartupMode.COLD, "components")
    @Test fun shortComponentsFirstTextAndFrames() = startup(StartupMode.COLD, "components-short")

    private fun startup(mode: StartupMode, profile: String = "short") = measure(
        profile = profile, startup = mode,
    ) {
        openReader()
        click("Ping")
        assertTrue(device.wait(Until.hasObject(By.textContains("ping=1")), TIMEOUT))
        readable("1", "startup")
        val state = state()
        assertEquals("Wrong original starting character", "1:0:0", state.getString("anchor"))
        assertEquals("Trusted cache must not request remote text", 0, state.getInt("requests"))
        // The controls are live independently of readiness. The acknowledgement is not TTFD.
        repeat(if (profile.startsWith("components")) 8 else 1) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 3, 24)
        }
        if (profile == "components") {
            assertTrue("Scrolled source components must remain visible", device.hasObject(By.textContains("R1_P")))
            assertFalse("The chapter must advance beyond its first component", device.hasObject(By.textContains("R1_P0000")))
        }
        if (profile == "mixed") {
            var scrolls = 0
            while (!device.hasObject(By.textContains("R1_AFTER_IMAGE")) && scrolls++ < 20) {
                device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                    device.displayWidth / 2, device.displayHeight / 3, 24)
            }
            assertTrue("Content after the image was lost", device.hasObject(By.textContains("R1_AFTER_IMAGE")))
        }
        report(state())
    }

    @Test fun trustedAdjacentWhileBackgroundWorks() = measure {
        click("Busy")
        awaitState { it.getInt("active") == 1 }
        assertTrue("Current chapter disappeared", device.hasObject(By.textContains("R1_P")))
        click("Next")
        readable("2", "chapter2")
        assertEquals("Cached neighbor caused a remote request", 1, state().getInt("requests"))
        awaitState { it.getInt("active") == 0 && it.getInt("completed") == 1 }
        report(state())
    }

    @Test fun cancellationReleasesNativeSourceWork() = measure {
        click("Busy")
        awaitState { it.getInt("active") == 1 }
        click("Cancel")
        val state = awaitState { it.getInt("active") == 0 && it.getInt("cancelled") == 1 }
        assertEquals(1, state.getInt("requests"))
        click("Next")
        readable("2", "chapter2")
        report(state())
    }

    @Test fun legacyCacheAndExplicitRefreshUseProductionDecisions() = measure(cache = "legacy") {
        val before = awaitState { it.getInt("requests") > 0 && it.getInt("active") == 0 }
        assertTrue("Cached directory skipped its normal refresh", before.getInt("directories") > 0)
        click("Refresh")
        val after = awaitState { it.getInt("requests") > before.getInt("requests") && it.getInt("active") == 0 }
        assertEquals("Explicit refresh was skipped or duplicated", before.getInt("requests") + 1, after.getInt("requests"))
        assertTrue("Refresh hid current text", device.hasObject(By.textContains("R1_P")))
        report(after)
    }

    @Test fun fontModeAndActivityRecreationReportOriginalAnchor() = measure {
        click("Bigger")
        readable("1", "font")
        report(state())
        click("Mode")
        readable("1", "mode")
        report(state())
        click("Recreate")
        readable("1", "startup")
        report(state())
        // Nonzero cross-mode/saved-state precision belongs to the position contract owner.
        // Report exact glyph preservation rather than treating equal percentages as proof.
    }

    private fun measure(profile: String = "short", cache: String = "trusted",
        startup: StartupMode? = null, action: MacrobenchmarkScope.() -> Unit) {
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = buildList {
                add(FrameTimingMetric())
                add(MemoryUsageMetric(MemoryUsageMetric.Mode.Max))
                add(TraceSectionMetric("reader.prepare", TraceSectionMetric.Mode.Sum))
                add(TraceSectionMetric("reader.layout", TraceSectionMetric.Mode.Sum))
                add(TraceSectionMetric("reader.source.execute", TraceSectionMetric.Mode.Sum))
                add(TraceSectionMetric("reader.background.request", TraceSectionMetric.Mode.Sum))
                add(TraceSectionMetric("reader.refresh.request", TraceSectionMetric.Mode.Sum))
                add(TraceSectionMetric("reader.to_readable", TraceSectionMetric.Mode.First))
                if (startup != null) {
                    add(StartupTimingMetric())
                    add(TraceSectionMetric("reader.to_interactive", TraceSectionMetric.Mode.First))
                }
            },
            compilationMode = CompilationMode.Full(), startupMode = startup, iterations = iterations,
            setupBlock = {
                device.executeShellCommand("am force-stop $PACKAGE")
                val seeded = device.executeShellCommand("am broadcast -W -n $RECEIVER -a seed --es profile $profile --es cache $cache")
                check(seeded.contains("result=-1")) { seeded }
                pressHome()
                if (startup == null) { openReader(); readable("1", "startup") }
            },
            measureBlock = action,
        )
    }

    private fun MacrobenchmarkScope.openReader() = startActivityAndWait(Intent().apply {
        component = ComponentName(PACKAGE, "indi.renakoni.nextvol.benchmark.ReaderPerformanceActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    })
    private fun click(text: String) {
        val button = device.wait(Until.findObject(By.text(text)), TIMEOUT)
        assertNotNull("Missing control $text", button)
        button.click()
    }
    private fun readable(chapter: String, action: String) {
        val ready = device.wait(Until.hasObject(By.desc("readable:$chapter:$action")), CONTENT_TIMEOUT)
        assertTrue("No drawn original text for $chapter/$action: ${state()}",
            ready)
        assertTrue("Missing actual reader text", device.hasObject(By.textContains("R${chapter}_P")))
    }
    private fun state(): JSONObject {
        val output = device.executeShellCommand("am broadcast -W -n $RECEIVER -a state")
        val result = output.substringAfter("Broadcast completed:")
        check(result.contains("result=-1")) { output }
        return JSONObject(result.substring(result.indexOf('{'), result.lastIndexOf('}') + 1))
    }
    private fun awaitState(predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        do {
            val state = state()
            if (predicate(state)) return state
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Fixture condition did not complete: ${state()}")
    }
    private fun report(state: JSONObject) {
        assertFalse(state.getBoolean("benchmarkShortcuts"))
        assertNotEquals("Pass -PreaderBenchmarkSha=<git SHA>", "UNSPECIFIED", state.getString("sha"))
        assertEquals("", state.getString("error"))
        assertTrue("Complete original chapter was not verified", state.getBoolean("contentVerified"))
        state.put("model", Build.MODEL).put("fingerprint", Build.FINGERPRINT).put("api", Build.VERSION.SDK_INT)
            .put("iterations", iterations).put("compilationMode", "Full").put("minified", true)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "READER_SAMPLE $state\n") })
    }

    companion object {
        private const val PACKAGE = "indi.renakoni.nextvol.readerbenchmark"
        private const val RECEIVER = "$PACKAGE/indi.renakoni.nextvol.benchmark.ReaderPerformanceReceiver"
        private const val TIMEOUT = 30_000L
        private const val CONTENT_TIMEOUT = 120_000L
    }
}
