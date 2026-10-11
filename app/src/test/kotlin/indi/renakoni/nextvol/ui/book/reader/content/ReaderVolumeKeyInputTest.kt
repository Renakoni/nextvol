package indi.renakoni.nextvol.ui.book.reader.content

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReaderVolumeKeyInputTest {
    private val forward = ReaderVolumeDirection.Forward
    private val backward = ReaderVolumeDirection.Backward

    @Test
    fun disabledAndUnownedReleasesReturnToSystem() = runTest {
        val input = ReaderVolumeKeyInput(this, -1f) { fail("Unexpected movement") }
        assertFalse(input.onKey(forward, true))
        assertFalse(input.onKey(forward, false))
        input.setEnabled(true)
        assertFalse(input.onKey(forward, false))
        assertFalse(input.onKey(forward, true, 2))
    }

    @Test
    fun quickTapMovesOnceEvenWhenReleasedBeforeCoroutineRuns() = runTest {
        val moves = mutableListOf<ReaderVolumeDirection>()
        val input = ReaderVolumeKeyInput(this, 0.1f) { moves += it }
        input.setEnabled(true)
        assertTrue(input.onKey(forward, true))
        assertTrue(input.onKey(forward, false))
        advanceTimeBy(1_000)
        assertEquals(listOf(forward), moves)
    }

    @Test
    fun timerIsTheOnlyRepeatSourceAndReleaseStopsIt() = runTest {
        val moves = mutableListOf<ReaderVolumeDirection>()
        val input = ReaderVolumeKeyInput(this, 0.1f) { moves += it }
        input.setEnabled(true)
        input.onKey(backward, true)
        runCurrent()
        repeat(10) { assertTrue(input.onKey(backward, true, it + 1)) }
        assertTrue(input.onKey(backward, true))
        assertEquals(listOf(backward), moves)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(backward, backward), moves)
        input.onKey(backward, false)
        advanceTimeBy(1_000)
        assertEquals(2, moves.size)
    }

    @Test
    fun negativeOrNonFiniteIntervalDisablesRepeating() = runTest {
        for (interval in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            var moves = 0
            val input = ReaderVolumeKeyInput(this, interval) { moves++ }
            input.setEnabled(true)
            input.onKey(forward, true)
            advanceTimeBy(10_000)
            assertEquals(1, moves)
            input.setEnabled(false)
        }
    }

    @Test
    fun slowMovementIsSerialAndRapidInputsDoNotBuildABacklog() = runTest {
        var moves = 0
        var running = 0
        val input = ReaderVolumeKeyInput(this, 0.1f) {
            assertEquals(0, running++)
            moves++
            try { delay(1_000) } finally { running-- }
        }
        input.setEnabled(true)
        input.onKey(forward, true)
        runCurrent()
        repeat(100) {
            input.onKey(forward, false)
            input.onKey(forward, true)
        }
        input.onKey(forward, false)
        advanceTimeBy(10_000)
        assertEquals(1, moves)
        assertEquals(0, running)
    }

    @Test
    fun losingOwnershipCancelsMovementAndRequiresANewPress() = runTest {
        var cancelled = false
        val input = ReaderVolumeKeyInput(this, 0.1f) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        input.setEnabled(true)
        input.onKey(forward, true)
        runCurrent()
        input.setEnabled(false)
        runCurrent()
        assertTrue(cancelled)
        input.setEnabled(true)
        assertFalse(input.onKey(forward, true, 1))
        assertFalse(input.onKey(forward, false))
    }

    @Test
    fun releasingTheOtherKeyDoesNotCancelTheCurrentHold() = runTest {
        val moves = mutableListOf<ReaderVolumeDirection>()
        val input = ReaderVolumeKeyInput(this, 0.1f) { moves += it }
        input.setEnabled(true)
        input.onKey(forward, true)
        runCurrent()
        input.onKey(backward, true)
        runCurrent()
        input.onKey(forward, false)
        advanceTimeBy(100)
        runCurrent()
        input.onKey(backward, false)
        assertEquals(listOf(forward, backward, backward), moves)
    }

    @Test
    fun scrollDistanceUsesCurrentViewportAndValidatedFraction() {
        assertEquals(200f, volumeKeyScrollDistance(800, 0.25f, forward))
        assertEquals(-100f, volumeKeyScrollDistance(400, 0.25f, backward))
        assertEquals(400f, volumeKeyScrollDistance(800, 0.5f, forward))
        assertEquals(0f, volumeKeyScrollDistance(0, 0.25f, forward))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f, 1.01f)) {
            assertEquals(480f, volumeKeyScrollDistance(800, invalid, forward), 0.001f)
        }
        assertEquals(80f, volumeKeyScrollDistance(800, 0.1f, forward))
        assertEquals(800f, volumeKeyScrollDistance(800, 1f, forward))
    }
}
