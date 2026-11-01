package indi.renakoni.nextvol.tts.names

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A single book's bounded text range, normally one chapter. */
data class SpeechNewWordScope(val bookId: String, val rangeId: String)

/**
 * Statistics for one scope only. Use stable unit keys within that scope; a repeated
 * unit reuses its earlier result. A new scope or name rule discards the old history.
 * Call [reset] when stopping or switching algorithms. Previews use a separate instance;
 * do not use a whole book as one range or share this session with batch analysis.
 */
class SpeechNewWordSession(stopwords: Set<String>) {
    private val stopwords = stopwords.toSet()
    private val mutex = Mutex()
    private var scope: SpeechNewWordScope? = null
    private var nameRegex: String? = null
    private var engine: RealtimeNameDiscoverer? = null
    private var units = linkedMapOf<String, UnitResult>()

    private data class UnitResult(val text: String, val reverseWindows: Boolean, val candidates: List<RealtimeNameCandidate>)

    suspend fun submit(scope: SpeechNewWordScope, unitKey: String, text: String, nameRegex: String,
        reverseWindows: Boolean = false): List<RealtimeNameCandidate> = mutex.withLock {
        val context = currentCoroutineContext()
        val checkCancelled = { context.ensureActive() }
        checkCancelled()
        if (this.scope != scope || this.nameRegex != nameRegex) {
            this.scope = scope
            this.nameRegex = nameRegex
            units.clear()
            engine = null
        }
        val previous = units[unitKey]
        if (previous != null && previous.text == text && previous.reverseWindows == reverseWindows) return@withLock previous.candidates
        try {
            if (previous != null) {
                // Replacing a unit preserves its position in submission order. Publish only a complete replay.
                val next = LinkedHashMap(units)
                next[unitKey] = UnitResult(text, reverseWindows, emptyList())
                val rebuilt = RealtimeNameDiscoverer(nameRegex, stopwords)
                for ((key, unit) in next) {
                    val candidates = rebuilt.submit(unit.text, unit.reverseWindows, checkCancelled)
                    next[key] = unit.copy(candidates = candidates)
                }
                checkCancelled()
                units = next
                engine = rebuilt
                next.getValue(unitKey).candidates
            } else {
                val active = engine ?: RealtimeNameDiscoverer(nameRegex, stopwords).also { rebuilt ->
                    for (unit in units.values) rebuilt.submit(unit.text, unit.reverseWindows, checkCancelled)
                }
                val candidates = active.submit(text, reverseWindows, checkCancelled)
                checkCancelled()
                units[unitKey] = UnitResult(text, reverseWindows, candidates)
                engine = active
                candidates
            }
        } catch (failure: Exception) {
            // A cancelled/failed submit may already have changed the trie. Rebuild from completed units on retry.
            engine = null
            throw failure
        }
    }

    /** Release the range history when leaving algorithm 100 or ending its reading scope. */
    suspend fun reset() = mutex.withLock {
        scope = null
        nameRegex = null
        units.clear()
        engine = null
    }
}
