package indi.renakoni.nextvol.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.tts.names.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechNameClassificationInstrumentedTest {
    @Test fun bundledModelKeepsOriginalLabelsAndPrecedesFallbackRules(): Unit = runBlocking {
        val classifier = SpeechNameClassifier(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeTrue(classifier.available)
        assertEquals(SpeechNameClassification(SpeechNameHint.Female, SpeechNameHintBasis.Model),
            classifier.classify("林黛玉", maleNameRegex = "林黛玉"))
        assertEquals(SpeechNameClassification(SpeechNameHint.Male, SpeechNameHintBasis.Model),
            classifier.classify("王小明", femaleNameRegex = "王小明"))
        assertEquals(SpeechNameClassification(SpeechNameHint.Unknown, SpeechNameHintBasis.None), classifier.classify("qzx"))
    }
}
