package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.names.RealtimeNameCandidate
import indi.renakoni.nextvol.tts.names.RealtimeNameDiscoverer
import indi.renakoni.nextvol.tts.names.SpeechNewWordScope
import indi.renakoni.nextvol.tts.names.SpeechNewWordSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine

class SpeechNewWordSessionTest {
    private val scope = SpeechNewWordScope("book", "chapter")
    private val rule = "林逸"
    private val parts = listOf("林逸来到门前。", "林逸转身离开。", "林逸笑着点头。", "林逸站在窗边。")

    @Test fun repeatedUnitKeepsItsHistoricalResultWithoutAddingStatistics() = runBlocking {
        val session = SpeechNewWordSession(emptySet())
        val reference = RealtimeNameDiscoverer(rule, emptySet())
        val first = session.submit(scope, "0", parts[0], rule)
        assertEquals(reference.submit(parts[0], false), first)
        val second = session.submit(scope, "1", parts[1], rule)
        assertEquals(reference.submit(parts[1], false), second)
        assertTrue(second.isNotEmpty())
        assertEquals(first, session.submit(scope, "0", parts[0], rule))
        assertEquals(second, session.submit(scope, "1", parts[1], rule))
        assertEquals(reference.submit(parts[2], false), session.submit(scope, "2", parts[2], rule))
    }

    @Test fun changedTextAndDirectionReplayAllUnitsInTheirOriginalOrder() = runBlocking {
        val session = SpeechNewWordSession(emptySet())
        session.submit(scope, "first", parts[0], rule)
        session.submit(scope, "middle", parts[1], rule)
        session.submit(scope, "last", parts[3], rule)
        val replacement = parts[1] + parts[2]
        for (reverse in listOf(true, false)) {
            val reference = RealtimeNameDiscoverer(rule, emptySet())
            reference.submit(parts[0], false)
            val expectedMiddle = reference.submit(replacement, reverse)
            val expectedLast = reference.submit(parts[3], false)
            assertTrue(expectedMiddle.isNotEmpty())
            assertEquals(expectedMiddle, session.submit(scope, "middle", replacement, rule, reverse))
            assertEquals(expectedLast, session.submit(scope, "last", parts[3], rule))
        }
    }

    @Test fun bookChapterRuleAndResetEachDiscardThePreviousHistory() = runBlocking {
        val session = SpeechNewWordSession(emptySet())
        suspend fun warm(current: SpeechNewWordScope, currentRule: String) {
            assertTrue(session.submit(current, "0", parts[0], currentRule).isEmpty())
            assertTrue(session.submit(current, "1", parts[1], currentRule).isNotEmpty())
        }
        warm(scope, rule)
        warm(scope.copy(rangeId = "next chapter"), rule)
        warm(scope.copy(bookId = "another book", rangeId = "next chapter"), rule)
        val changedRule = "(?:林逸)"
        val lastScope = scope.copy(bookId = "another book", rangeId = "next chapter")
        warm(lastScope, changedRule)
        session.reset()
        warm(lastScope, changedRule)
    }

    @Test fun cancelledAppendAndReplacementDiscardPartialStatisticsAndPreserveCompletedUnits() = runBlocking {
        for (key in listOf("new", "0")) {
            val session = SpeechNewWordSession(emptySet())
            val first = session.submit(scope, "0", parts[0], rule)
            val second = session.submit(scope, "1", parts[1], rule)
            val job = CancelOnCheck(7)
            var outcome: Result<List<RealtimeNameCandidate>>? = null
            val submit: suspend () -> List<RealtimeNameCandidate> = {
                session.submit(scope, key, parts[2], rule)
            }
            // No dispatcher or contended lock: cancel during the synchronous trie update.
            submit.startCoroutine(object : Continuation<List<RealtimeNameCandidate>> {
                override val context: CoroutineContext = job
                override fun resumeWith(result: Result<List<RealtimeNameCandidate>>) { outcome = result }
            })
            assertTrue(outcome?.exceptionOrNull() is CancellationException)
            assertEquals(first, session.submit(scope, "0", parts[0], rule))
            assertEquals(second, session.submit(scope, "1", parts[1], rule))
            val reference = RealtimeNameDiscoverer(rule, emptySet())
            reference.submit(parts[0], false)
            reference.submit(parts[1], false)
            assertEquals(reference.submit(parts[3], false), session.submit(scope, "next", parts[3], rule))
        }
    }

    private class CancelOnCheck(private var remaining: Int, private val delegate: Job = Job()) : Job by delegate {
        override val isActive: Boolean
            get() {
                if (--remaining == 0) delegate.cancel()
                return delegate.isActive
            }

        @Suppress("UNCHECKED_CAST")
        override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
            if (key == Job) this as E else null
    }
}
