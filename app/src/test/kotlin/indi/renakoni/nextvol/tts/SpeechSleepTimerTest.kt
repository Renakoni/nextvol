package indi.renakoni.nextvol.tts

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SpeechSleepTimerTest {
    @Test fun expiresOnceAndClearsTheDeadline() = runTest {
        var stops = 0
        var displayed: Long? = null
        val timer = SpeechSleepTimer(this, { testScheduler.currentTime }, { displayed = it }, { stops++ })
        timer.set(1, playing = true)
        assertEquals(60_000L, displayed)
        advanceTimeBy(59_999)
        assertEquals(0, stops)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, stops)
        assertNull(displayed)
        assertFalse(timer.expireIfDue())
    }

    @Test fun replacingAndCancellingTheTimerCannotLeaveAnOldStopScheduled() = runTest {
        var stops = 0
        val timer = SpeechSleepTimer(this, { testScheduler.currentTime }, {}, { stops++ })
        timer.set(1, playing = true)
        advanceTimeBy(30_000)
        timer.set(2, playing = true)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0, stops)
        assertEquals(150_000L, timer.deadline)
        timer.cancel()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, stops)
        assertNull(timer.deadline)
    }

    @Test fun resumeChecksElapsedTimeBeforeTheDelayedCallbackCanRun() = runTest {
        var elapsed = 0L
        var stops = 0
        val timer = SpeechSleepTimer(this, { elapsed }, {}, { stops++ })
        timer.set(1, playing = true)
        runCurrent()
        // Device sleep advances elapsed time even if the service's coroutine has not run.
        elapsed = 60_001
        assertTrue(timer.expireIfDue())
        assertEquals(1, stops)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, stops)
        timer.set(1, playing = true)
        assertEquals(120_001L, timer.deadline)
        timer.cancel()
    }
}
