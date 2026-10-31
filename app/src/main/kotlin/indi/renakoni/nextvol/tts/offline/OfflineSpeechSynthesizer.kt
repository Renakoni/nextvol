package indi.renakoni.nextvol.tts.offline

import android.content.Context
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.offline.legacy.*
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

/** Owns a vendor runtime for its entire open/synthesize/close lifetime. */
internal class OfflineSpeechSynthesizer(
    context: Context,
    directory: File,
    private val voice: OfflineVoiceConfig,
    private val engineFactory: () -> OfflineEngine = { createEngine(context, directory, voice) },
) : SpeechSynthesizer {
    private val lock = ReentrantLock()
    private val cancelled = AtomicBoolean()
    private val opened = AtomicBoolean()
    @Volatile private var engine: OfflineEngine? = null
    private var ownsPermit = false
    private var closed = false
    private var speed = 1f

    override suspend fun open(settings: SpeechSettings): String {
        check(opened.compareAndSet(false, true))
        val job = currentCoroutineContext().job
        try {
            speed = settings.rate ?: 1f
            require(speed.isFinite() && speed > 0)
            instances.acquire()
            var transferred = false
            try {
                withContext(Dispatchers.IO) {
                    lock.withLock {
                        job.ensureActive()
                        checkCancelled()
                        check(!closed)
                        ownsPermit = true
                        transferred = true
                        engine = engineFactory()
                        job.ensureActive()
                        checkCancelled()
                    }
                }
            } finally {
                if (!transferred) instances.release()
            }
            return voice.engine
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { close() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            if (failure is CancellationException || failure is SpeechException) throw failure
            if (failure is LinkageError || failure is Exception)
                throw SpeechException(SpeechError.OfflineUnavailable).apply { initCause(failure) }
            throw failure
        }
    }

    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
        val job = currentCoroutineContext().job
        val partial = File(output.parentFile, output.name + ".part")
        var ownsPartial = false
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                lock.withLock {
                    check(!closed)
                    val active = checkNotNull(engine)
                    require(!output.exists() && !partial.exists())
                    job.ensureActive()
                    checkCancelled()
                    ownsPartial = true
                    OfflinePcmFile(partial, active.sampleRate).use { audio ->
                        active.synthesize(text, speed) { buffer, count ->
                            job.ensureActive()
                            checkCancelled()
                            audio.append(buffer, count)
                        }
                        job.ensureActive()
                        checkCancelled()
                        audio.finish(active.sampleRate)
                    }
                    validateSpeechWav(partial)
                    job.ensureActive()
                    checkCancelled()
                    if (!partial.renameTo(output)) throw SpeechException(SpeechError.Storage)
                    committed = true
                }
            }
            return emptyList()
        } catch (failure: Exception) {
            if (committed) output.delete()
            if (failure is CancellationException || failure is SpeechException) throw failure
            throw SpeechException(SpeechError.SynthesisFailed).apply { initCause(failure) }
        } finally {
            if (ownsPartial) partial.delete()
        }
    }

    override fun cancel() {
        cancelled.set(true)
        engine?.cancel()
    }

    override fun close() {
        val cancellationFailure = runCatching { cancel() }.exceptionOrNull()
        lock.withLock {
            closed = true
            try { engine?.close() } catch (failure: Throwable) {
                if (cancellationFailure != null && cancellationFailure !== failure)
                    failure.addSuppressed(cancellationFailure)
                throw failure
            } finally {
                engine = null
                if (ownsPermit) {
                    ownsPermit = false
                    instances.release()
                }
            }
        }
        cancellationFailure?.let { throw it }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Offline synthesis cancelled")
    }

    private companion object {
        // Several SDKs register process-global callbacks during initialization.
        val instances = Semaphore(1)

        fun createEngine(context: Context, directory: File, voice: OfflineVoiceConfig): OfflineEngine = when (voice.engine) {
            "bdetts" -> BaiduEngine(context, directory, voice)
            "sougou" -> SogouEngine(context, directory, voice)
            "microsoft" -> MicrosoftEngine(context, directory, voice)
            "weread" -> WeReadEngine(context, directory, voice)
            "xiaomi" -> XiaomiEngine(context, directory, voice)
            "samsung" -> SamsungEngine(context, directory, voice)
            "msctts" -> MscEngine(context, directory, voice)
            "isstts" -> IssEngine(context, directory, voice)
            "google" -> GoogleEngine(context, directory, voice)
            "vocalizer" -> VocalizerEngine(context, directory, voice)
            "audio" -> AudioEffectEngine(context, directory, voice)
            "vcstts" -> VcsEngine(context, directory, voice)
            "larktts" -> LarkEngine(context, directory, voice)
            "acapela" -> AcapelaEngine(context, directory, voice)
            "cereproc" -> CereProcEngine(context, directory, voice)
            else -> throw SpeechException(SpeechError.OfflineUnavailable)
        }
    }
}
