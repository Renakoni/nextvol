package indi.renakoni.nextvol.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val MAX_SLEEP_TIMER_MINUTES = 24 * 60 - 1

/** Counts elapsed playback time; the service freezes it whenever book audio stops. */
internal class SpeechSleepTimer(
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val onChanged: (Long?) -> Unit,
    private val onExpired: () -> Unit,
) {
    var deadline: Long? = null
        private set
    var remainingMillis: Long? = null
        private set
    private var job: Job? = null

    fun set(minutes: Int?, playing: Boolean) {
        require(minutes == null || minutes in 1..MAX_SLEEP_TIMER_MINUTES)
        job?.cancel()
        job = null
        deadline = null
        remainingMillis = minutes?.times(60_000L)
        if (playing) schedule()
        onChanged(deadline)
    }

    fun setPlaying(playing: Boolean) {
        if (expireIfDue() || remainingMillis == null || playing == (deadline != null)) return
        if (playing) schedule()
        else {
            remainingMillis = (deadline!! - elapsedRealtime()).coerceAtLeast(0)
            deadline = null
            job?.cancel()
            job = null
        }
        onChanged(deadline)
    }

    private fun schedule() {
        val remaining = remainingMillis ?: return
        deadline = elapsedRealtime() + remaining
        job = scope.launch { delay(remaining); expireIfDue() }
    }

    /** Background playback can reach its deadline before the delayed callback is dispatched. */
    fun expireIfDue(): Boolean {
        val expires = deadline ?: return false
        if (elapsedRealtime() < expires) return false
        cancel()
        onExpired()
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (remainingMillis != null) {
            deadline = null
            remainingMillis = null
            onChanged(null)
        }
    }
}
