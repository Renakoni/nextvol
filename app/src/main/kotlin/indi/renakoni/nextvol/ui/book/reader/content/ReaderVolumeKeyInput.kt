package indi.renakoni.nextvol.ui.book.reader.content

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DefaultVolumeKeyScrollFraction = 0.6f

internal fun volumeKeyScrollFraction(value: Float): Float =
    value.takeIf { it.isFinite() && it in 0.1f..1f } ?: DefaultVolumeKeyScrollFraction

// Reading order is independent of RTL and device rotation: volume down always advances.
internal enum class ReaderVolumeDirection(val sign: Int) { Backward(-1), Forward(1) }

internal fun volumeKeyScrollDistance(viewportHeight: Int, fraction: Float, direction: ReaderVolumeDirection): Float =
    viewportHeight.coerceAtLeast(0) * volumeKeyScrollFraction(fraction) * direction.sign

/** One application repeat timer, one in-flight movement, and no queued movements. */
internal class ReaderVolumeKeyInput(
    private val scope: CoroutineScope,
    intervalSeconds: Float,
    private val onStep: suspend (ReaderVolumeDirection) -> Unit,
) {
    private val intervalMillis = if (intervalSeconds.isFinite() && intervalSeconds > 0f) {
        (intervalSeconds.coerceIn(0.1f, 4f) * 1000).toLong()
    } else 0L
    private var enabled = false
    private val pressed = mutableSetOf<ReaderVolumeDirection>()
    private var repeatingDirection: ReaderVolumeDirection? = null
    private var repeatJob: Job? = null
    private var stepJob: Job? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            pressed.clear()
            repeatingDirection = null
            repeatJob?.cancel()
            stepJob?.cancel()
        }
    }

    fun onKey(direction: ReaderVolumeDirection, down: Boolean, repeatCount: Int = 0): Boolean {
        if (!enabled) return false
        if (!down) {
            if (!pressed.remove(direction)) return false
            if (repeatingDirection == direction) {
                repeatJob?.cancel()
                repeatingDirection = null
            }
            return true
        }
        // Never start a new press from a system repeat after focus or mode changes.
        if (repeatCount > 0) return direction in pressed
        if (!pressed.add(direction)) return true
        repeatJob?.cancel()
        repeatingDirection = direction
        step(direction)
        if (intervalMillis > 0) {
            repeatJob = scope.launch {
                while (isActive) {
                    stepJob?.join()
                    delay(intervalMillis)
                    step(direction)
                }
            }
        }
        return true
    }

    private fun step(direction: ReaderVolumeDirection) {
        if (stepJob?.isActive != true) {
            stepJob = scope.launch { onStep(direction) }
        }
    }
}
