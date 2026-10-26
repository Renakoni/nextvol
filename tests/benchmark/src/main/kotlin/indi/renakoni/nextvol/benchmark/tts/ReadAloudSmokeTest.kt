package indi.renakoni.nextvol.benchmark.tts

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
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
        // The entry is now the first setting. Await navigation before any scrolling.
        clickText("Voices")
        assertDescription("Library options")
        scrollSpeechListTo("Imported test voice")
        clickText("Imported test voice")
        device.pressBack()
        assertText("Imported test voice")
        clickScrolledText("Preview voice")
        clickText("Finished")
        clickText("Stop")
        assertServiceStopped()
        scrollSpeechListTo("Voices")
        clickText("Voices")
        assertDescription("Library options")
        scrollSpeechListTo("Imported test voice")
        clickDescription("Voice options")
        clickText("Remove voice")
        assertTrue(device.wait(Until.gone(By.text("Imported test voice")), TIMEOUT))
        device.pressBack()
        assertText("System speech")
    }

    private fun scrollSpeechListTo(text: String) {
        // Compact settings may fit on screen and expose no scrollable container.
        if (!device.hasObject(By.text(text))) {
            assertTrue("Speech list did not contain: $text",
                UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text))
        }
        assertText(text)
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
