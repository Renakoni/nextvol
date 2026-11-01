package indi.renakoni.nextvol.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechReplacementInstrumentedTest {
    @Test fun nativeRegexIsTerminatedAndTheNextPlanCanUseTheWorker(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        val plans = SpeechPlanPreparer(executor, authority)
        try {
            val rule = SpeechReplacementRule(id = "slow", source = "(a+)+$", target = "", regex = true)
            val chapter = SpeechChapter("book", "chapter", "", "", "a".repeat(30_000) + "!")
            val failure = withTimeout(20_000) {
                try { plans.prepare(chapter, SpeechSettings(replacements = listOf(rule))); null }
                catch (failure: SpeechReplacementException) { failure.failure }
            }
            assertEquals("slow", failure?.ruleId)
            assertEquals(SpeechReplacementProblem.Budget, failure?.problem)
            val healthy = plans.prepare(chapter.copy(text = "😀甲"), SpeechSettings(replacements = listOf(
                rule.copy(source = "(?<face>😀)(甲)", target = "$2\${face}"))))
            assertEquals("甲😀", healthy.segments.single().text)
            assertEquals(2, healthy.position(0, 0, 1).start)
            assertEquals(0, healthy.position(0, 1, 3).start)
            assertEquals(2, healthy.position(0, 1, 3).end)
        } finally { executor.close() }
    }
}
