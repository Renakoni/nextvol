package hnovel.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRequestPacerTest {
    @Test fun bareIntervalsAndWindowsHaveExplicitInvalidValueSemantics() {
        assertEquals(SourceRequestRate(1, 2000), SourceRequestRate.parse("2000"))
        assertEquals(SourceRequestRate(2, 2000), SourceRequestRate.parse(" 2 / 2000 "))
        for (value in listOf(null, "", "0", "-1", "0/2000", "-2/2000", "2/0", "2/-1",
            "many/2000", "2/ms", "1.5", "1/2/3", "2147483648/2000", "9223372036854775808"))
            assertNull(value, SourceRequestRate.parse(value))
    }

    @Test fun delayedWakeupAnchorsToActualAdmission() = runTest {
        var clock = 0L
        val pacer = SourceRequestPacer(SourceRequestRate(1, 2000)) { clock }
        pacer.awaitAdmission()
        val first = async { pacer.awaitAdmission(); currentTime }
        runCurrent()
        clock = 20000 // Device execution resumes much later than the original deadline.
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(2000L, first.await())
        val next = async { pacer.awaitAdmission(); currentTime }
        runCurrent()
        assertFalse(next.isCompleted)
        clock = 22000
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(4000L, next.await())
    }
}
