package indi.renakoni.nextvol.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import indi.renakoni.nextvol.tts.names.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.PatternSyntaxException

@RunWith(AndroidJUnit4::class)
class SpeechNameClassificationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = ExecutionAuthority()
    private val executor = AndroidIsolatedExecutor(context, authority)
    private val classifier = SpeechNameClassifier(context, executor, authority)

    @After fun closeWorkers() = runBlocking { executor.close() }

    @Test fun bundledModelKeepsOriginalLabelsAndPrecedesFallbackRules(): Unit = runBlocking {
        assumeTrue(classifier.available)
        assertEquals(SpeechNameClassification(SpeechNameHint.Female, SpeechNameHintBasis.Model),
            classifier.classify("林黛玉", maleNameRegex = "["))
        assertEquals(SpeechNameClassification(SpeechNameHint.Male, SpeechNameHintBasis.Model),
            classifier.classify("王小明", femaleNameRegex = "王小明"))
    }

    @Test fun isolatedRulesKeepFullMatchesOrderAndLocatableSyntaxErrors(): Unit = runBlocking {
        assertEquals(SpeechNameClassification(SpeechNameHint.Male, SpeechNameHintBasis.MaleRule),
            classifier.classify("Anna", "Anna", "Anna"))
        assertEquals(SpeechNameClassification(SpeechNameHint.Female, SpeechNameHintBasis.FemaleRule),
            classifier.classify("Anna", "Ann", "Anna"))
        assertEquals(SpeechNameClassification(SpeechNameHint.Unknown, SpeechNameHintBasis.None),
            classifier.classify("Anna", "Ann", ""))
        // A later invalid rule is not compiled after an earlier match.
        assertEquals(SpeechNameHint.Male, classifier.classify("Anna", "Anna", "[").hint)
        try {
            classifier.classify("Anna", "Ann", "[")
            fail("Expected the female rule's syntax error")
        } catch (error: PatternSyntaxException) {
            assertTrue(error.description.startsWith("femaleNameRegex:"))
            assertEquals("[", error.pattern)
        }
    }

    @Test fun deadlineReleasesTheWorkerForTheNextName(): Unit = runBlocking {
        // Warm the worker so this checks matching's budget rather than cold service startup.
        assertEquals(SpeechNameHint.Male, classifier.classify("Anna", "Anna").hint)
        val input = "a".repeat(40_000) + "!"
        val failure = withTimeout(25_000) {
            try { classifier.classify(input, femaleNameRegex = "(a+)+$"); null }
            catch (error: IllegalStateException) { error }
        }
        assertEquals("femaleNameRegex: Timeout", failure?.message)
        assertEquals(SpeechNameHint.Female, classifier.classify("Anna", "Ann", "Anna").hint)
    }
}
