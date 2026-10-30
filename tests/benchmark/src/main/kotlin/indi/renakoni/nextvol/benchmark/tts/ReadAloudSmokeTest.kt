package indi.renakoni.nextvol.benchmark.tts

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import indi.renakoni.nextvol.benchmark.ui.UiAutomatorTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadAloudSmokeTest : UiAutomatorTest() {
    @Test fun importedVoiceCanBeSelectedPlayedAndRemovedWithoutASystemEngine() {
        val result = shell("am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
            "-a $TARGET_PACKAGE.benchmark.SPEECH_SOURCE")
        assertTrue(result, result.contains("speech-source=SUCCEEDED"))
        configureEngine("invalid.nextvol.speech.engine")
        launchApp()
        shell("am start -W -n $TARGET_PACKAGE/.MainActivity -a indi.renakoni.nextvol.OPEN_READ_ALOUD")
        // Await the settings screen before opening the voice library.
        clickText("Voice sources")
        assertDescription("Library options")
        scrollToText("Imported test voice")
        clickText("Imported test voice")
        device.pressBack()
        assertText("Imported test voice")
        clickText("Voice sources")
        assertDescription("Library options")
        // A wrapped voice name can enter the viewport before its action buttons.
        scrollTo(By.desc("Preview Imported test voice"))
        clickDescription("Preview Imported test voice")
        clickText("Finished")
        clickText("Stop")
        assertServiceStopped()
        scrollTo(By.desc("Voice options"))
        clickDescription("Voice options")
        clickText("Remove voice")
        assertTrue(device.wait(Until.gone(By.text("Imported test voice")), TIMEOUT))
        device.pressBack()
        assertTextContains("System speech")
    }

    private fun assertServiceStopped() {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT
        var service: String
        do {
            service = shell("dumpsys activity services $TARGET_PACKAGE/.tts.ReadAloudService")
            if (!service.contains("ServiceRecord{")) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        assertFalse(service, service.contains("ServiceRecord{"))
    }

    private fun configureEngine(engine: String) {
        val result = shell("am broadcast -W -n $TARGET_PACKAGE/.benchmark.BenchmarkFixtureReceiver " +
            "-a $TARGET_PACKAGE.benchmark.SPEECH_ENGINE --es engine $engine")
        assertTrue(result, result.contains("speech=SUCCEEDED"))
    }
}
