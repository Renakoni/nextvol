package indi.renakoni.nextvol.tts

import android.util.Log
import indi.renakoni.nextvol.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Only live session engines are cleared. Installed packages and reading data are not cache entries. */
@Singleton
class SpeechSynthesizerCaches @Inject constructor() {
    private val sessions = mutableMapOf<CachedSpeechSynthesizer, Int>()
    private val mutableCount = MutableStateFlow(0)
    val count = mutableCount.asStateFlow()

    internal fun update(session: CachedSpeechSynthesizer, count: Int) = synchronized(sessions) {
        sessions[session] = count
        mutableCount.value = sessions.values.sum()
    }

    internal fun remove(session: CachedSpeechSynthesizer) = synchronized(sessions) {
        sessions.remove(session)
        mutableCount.value = sessions.values.sum()
    }

    suspend fun clear() {
        val current = synchronized(sessions) { sessions.keys.toList() }
        for (session in current) session.clear()
    }
}

internal data class SpeechRuntime(
    val synthesizer: SpeechSynthesizer,
    val exclusiveGroup: String? = null,
    val sharedModel: Any? = null,
)

/** R6.q: access-ordered, bounded voice cache with five-minute access expiry. Owned by one reading session. */
internal class CachedSpeechSynthesizer(
    private val caches: SpeechSynthesizerCaches,
    private val create: suspend (SpeechSettings) -> SpeechRuntime,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) : SpeechSynthesizer {
    private class Entry(val settings: SpeechSettings, val runtime: SpeechRuntime, val label: String, var accessed: Long)
    private val entries = LinkedHashMap<SpeechSettings, Entry>(16, 0.75f, true)
    private val mutex = Mutex()
    private val cancelled = AtomicBoolean()
    @Volatile private var active: SpeechSynthesizer? = null
    private var selected: Entry? = null
    private var settings: SpeechSettings? = null
    private var closed = false
    private var inputLimit = Int.MAX_VALUE
    private var selectedAudioParameters = SpeechAudioParameters()
    override val maxInputLength get() = inputLimit
    override val audioParameters get() = selectedAudioParameters

    override suspend fun open(settings: SpeechSettings): String = mutex.withLock {
        this.settings = settings
        select(settings).label
    }

    private suspend fun select(settings: SpeechSettings): Entry {
        check(!closed)
        if (cancelled.get()) throw CancellationException("Speech cache cancelled")
        currentCoroutineContext().ensureActive()
        val time = now()
        evict(entries.values.filter { time - it.accessed >= 5 * 60_000L })
        entries[settings]?.let { entry ->
            entry.accessed = time
            selected = entry
            active = entry.runtime.synthesizer
            inputLimit = active!!.maxInputLength
            selectedAudioParameters = active!!.audioParameters
            trace("hit", settings, entry.runtime.exclusiveGroup, 0)
            return entry
        }
        val runtime = create(settings)
        // Cache capacity never grants another backend slot. Shared Sherpa speakers use the same model slot.
        val conflicting = entries.values.filter { entry ->
            runtime.exclusiveGroup != null && entry.runtime.exclusiveGroup == runtime.exclusiveGroup &&
                (runtime.sharedModel == null || entry.runtime.sharedModel != runtime.sharedModel)
        }
        evict(conflicting)
        while (entries.size >= settings.engineCacheCapacity) evict(listOf(entries.values.first()))
        currentCoroutineContext().ensureActive()
        if (cancelled.get()) throw CancellationException("Speech cache cancelled")
        active = runtime.synthesizer
        val started = now()
        try {
            val label = runtime.synthesizer.open(settings)
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw CancellationException("Speech cache cancelled")
            val entry = Entry(settings, runtime, label, now())
            entries[settings] = entry
            selected = entry
            inputLimit = runtime.synthesizer.maxInputLength
            selectedAudioParameters = runtime.synthesizer.audioParameters
            caches.update(this, entries.size)
            trace("open", settings, runtime.exclusiveGroup, now() - started)
            return entry
        } catch (failure: Throwable) {
            active = null
            withContext(NonCancellable + Dispatchers.IO) {
                try { runtime.synthesizer.close() }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> = mutex.withLock {
        val entry = selected ?: select(checkNotNull(settings))
        if (cancelled.get()) throw CancellationException("Speech cache cancelled")
        entry.accessed = now()
        entry.runtime.synthesizer.synthesize(text, output).also { entry.accessed = now() }
    }

    override fun cancel() {
        cancelled.set(true)
        active?.cancel()
    }

    /** Waits for synthesis before releasing a lease; the next segment reopens its selected voice. */
    suspend fun clear() = mutex.withLock { evict(entries.values.toList()) }

    override fun close() {
        val cancellationFailure = runCatching { cancel() }.exceptionOrNull()
        try {
            runBlocking {
                mutex.withLock {
                    closed = true
                    evict(entries.values.toList())
                }
            }
        } catch (failure: Throwable) {
            if (cancellationFailure != null && cancellationFailure !== failure) failure.addSuppressed(cancellationFailure)
            throw failure
        } finally { caches.remove(this) }
        cancellationFailure?.let { throw it }
    }

    private suspend fun evict(removed: List<Entry>) {
        if (removed.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            var failure: Throwable? = null
            for (entry in removed) {
                entries.remove(entry.settings)
                if (selected === entry) { selected = null; active = null }
                val started = now()
                try { entry.runtime.synthesizer.close() }
                catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                } finally {
                    caches.update(this@CachedSpeechSynthesizer, entries.size)
                    trace("close", entry.settings, entry.runtime.exclusiveGroup, now() - started)
                }
            }
            failure?.let { throw it }
        }
    }

    private fun trace(event: String, settings: SpeechSettings, group: String?, elapsed: Long) {
        if (BuildConfig.DEBUG) Log.d("SpeechCache", "$event voice=${settings.voiceReference().key} group=$group elapsedMs=$elapsed")
    }
}
