package indi.renakoni.nextvol.tts.matcha

import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechException
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechSynthesizer
import indi.renakoni.nextvol.tts.SpeechTiming
import indi.renakoni.nextvol.tts.validateSpeechWav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MatchaSpeechSynthesizer internal constructor(
    private val resources: MatchaResources,
    private val native: () -> MatchaNativeApi = { MatchaNative },
) : SpeechSynthesizer {
    private val lock = ReentrantLock()
    private val cancelled = AtomicBoolean()
    private val opened = AtomicBoolean()
    private var closed = false
    private var ownsPermit = false
    private var handle = 0L
    private var speed = 1f

    override suspend fun open(settings: SpeechSettings): String {
        check(opened.compareAndSet(false, true))
        val job = currentCoroutineContext().job
        try {
            val rate = settings.rate ?: 1f
            require(rate.isFinite() && rate in 0.5f..2f && (settings.pitch == null || settings.pitch == 1f))
            require(settings.httpSource == null && settings.voice.isEmpty())
            instances.acquire()
            var transferred = false
            try {
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        job.ensureActive()
                        check(!closed)
                        checkCancelled()
                        ownsPermit = true
                        transferred = true
                        val root = resources.validate()
                        speed = rate
                        handle = native().create(root.path)
                        check(handle != 0L)
                        job.ensureActive()
                        checkCancelled()
                    }
                }
            } finally {
                if (!transferred) instances.release()
            }
            return "Matcha · 中文"
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            if (error is CancellationException) throw error
            if (error is LinkageError || error is Exception) throw SpeechException(SpeechError.EngineUnavailable)
            throw error
        }
    }

    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
        require(text.isNotBlank() && text.length <= 200 && '\u0000' !in text)
        val job = currentCoroutineContext().job
        val partial = File(output.parentFile, output.name + ".part")
        var committed = false
        var ownsPartial = false
        try {
            withContext(Dispatchers.IO) {
                lock.withLock {
                    check(!closed && handle != 0L)
                    require(!output.exists() && !partial.exists())
                    ownsPartial = true
                    checkCancelled()
                    job.ensureActive()
                    native().generate(handle, text, speed, partial.path, MatchaCancellation { cancelled.get() || !job.isActive })
                    job.ensureActive()
                    checkCancelled()
                    validateSpeechWav(partial)
                    if (!partial.renameTo(output)) throw SpeechException(SpeechError.Storage)
                    committed = true
                }
            }
            return emptyList()
        } catch (error: Exception) {
            if (committed) output.delete()
            if (error is CancellationException || error is SpeechException) throw error
            throw SpeechException(SpeechError.SynthesisFailed)
        } finally {
            if (ownsPartial) partial.delete()
        }
    }

    override fun cancel() { cancelled.set(true) }

    /** The same lock spans JNI calls and deletion; cancellation never acquires this lock. */
    override fun close() {
        cancel()
        lock.withLock {
            closed = true
            try {
                if (handle != 0L) native().destroy(handle)
            } finally {
                handle = 0L
                if (ownsPermit) {
                    ownsPermit = false
                    instances.release()
                }
            }
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Matcha cancelled")
    }

    private companion object {
        // A cancelled initializer owns its slot until its real native call has returned.
        val instances = Semaphore(1)
    }
}
