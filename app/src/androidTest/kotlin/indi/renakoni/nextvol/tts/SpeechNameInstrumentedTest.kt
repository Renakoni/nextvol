package indi.renakoni.nextvol.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.tts.names.SpeechNameRecognizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechNameInstrumentedTest {
    @Test fun bundledModelRecognizesNamesWithOriginalTextRanges(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recognizer = SpeechNameRecognizer(context)
        assumeTrue(recognizer.available)
        val text = "  😀小明告诉小红，明天一起去学校。  "
        val names = recognizer.recognize(text)
        assertEquals(listOf("😀小明", "小红"), names.map { it.name })
        assertEquals(listOf(2, 8), names.map { it.start })
        names.forEach { assertEquals(it.name, text.substring(it.start, it.end)) }
        assertTrue(recognizer.recognize("今天下雨了，街道上没有行人。").isEmpty())
    }
}
