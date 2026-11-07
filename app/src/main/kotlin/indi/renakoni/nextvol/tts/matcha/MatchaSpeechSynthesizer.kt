package indi.renakoni.nextvol.tts.matcha

import hnovel.speech.multiTtsControl
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MatchaSpeechSynthesizer internal constructor(
    private val resources: MatchaResources,
    private val native: () -> MatchaNativeApi = { MatchaNative.apply { requireAvailable() } },
) : SpeechSynthesizer {
    override val maxInputLength = indi.renakoni.nextvol.tts.speechOfflineInputLimit("sherpa")
    private val lock = ReentrantLock()
    private val cancelled = AtomicBoolean()
    private val opened = AtomicBoolean()
    private var closed = false
    private var model: MatchaModels.Model? = null
    private var speed = 1f

    internal fun describe(): IntArray = lock.withLock {
        check(!closed)
        checkNotNull(model).use { api, handle -> api.describe(handle) }
    }

    override suspend fun open(settings: SpeechSettings): String {
        check(opened.compareAndSet(false, true))
        val job = currentCoroutineContext().job
        try {
            require(settings.httpSource == null && settings.voice.isEmpty())
            withContext(Dispatchers.IO) {
                MatchaModels.acquire(resources, native) { acquire ->
                    lock.withLock {
                        job.ensureActive()
                        check(!closed)
                        checkCancelled()
                        model = acquire() ?: return@withLock
                        speed = multiTtsControl(settings.rate) / 34f
                        job.ensureActive()
                        checkCancelled()
                    }
                }
            }
            return "Sherpa"
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
                    check(!closed)
                    val active = checkNotNull(model)
                    require(!output.exists() && !partial.exists())
                    ownsPartial = true
                    checkCancelled()
                    job.ensureActive()
                    val voice = resources.configuration
                    active.use { api, handle ->
                        api.generate(handle, text, voice.speakerId, speed, partial.path,
                            MatchaCancellation { cancelled.get() || !job.isActive })
                    }
                    job.ensureActive()
                    checkCancelled()
                    validateSpeechWav(partial)
                    job.ensureActive()
                    checkCancelled()
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
            val previous = model
            model = null
            if (previous != null) MatchaModels.release(previous)
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Matcha cancelled")
    }
}
