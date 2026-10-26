package indi.renakoni.nextvol.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val MAX_SLEEP_TIMER_MINUTES = 180

/** A service-owned deadline: pauses and chapter changes do not extend it. */
internal class SpeechSleepTimer(
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val onChanged: (Long?) -> Unit,
    private val onExpired: () -> Unit,
) {
    var deadline: Long? = null
        private set
    private var job: Job? = null

    fun set(minutes: Int?) {
        require(minutes == null || minutes in 1..MAX_SLEEP_TIMER_MINUTES)
        job?.cancel()
        val duration = minutes?.times(60_000L)
        deadline = duration?.let { elapsedRealtime() + it }
        onChanged(deadline)
        job = duration?.let {
            scope.launch { delay(it); expireIfDue() }
        }
    }

    /** Check before resuming, since a suspended device may not have dispatched the delay yet. */
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
        if (deadline != null) {
            deadline = null
            onChanged(null)
        }
    }
}
