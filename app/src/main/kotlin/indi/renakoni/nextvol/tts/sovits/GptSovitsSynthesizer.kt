package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import androidx.annotation.RequiresApi
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.audio.SpeechWaveFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class GptSovitsSynthesizer(private val context: Context, private val voice: GptSovitsVoice) : SpeechSynthesizer {
    override val maxInputLength = SPEECH_SOVITS_INPUT_LIMIT
    override var audioParameters = SpeechAudioParameters()
        private set
    private val opened = AtomicBoolean()
    private val operation = ReentrantLock()
    private val lifecycle = Any()
    private val generation = GenerationContext()
    private var acoustic: SovitsAcoustic? = null
    private var handle = 0L
    private var ownsPermit = false
    private var preparing: Job? = null
    private var frontend: SovitsFrontend? = null
    private var options = voice.options

    override suspend fun open(settings: SpeechSettings): String {
        check(opened.compareAndSet(false, true))
        try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val job = currentCoroutineContext().job
                    synchronized(lifecycle) { generation.checkCancelled(); preparing = job }
                    try {
                        val npuRuntime = DeviceAcceleration.detect(context)
                        if (!DeviceAcceleration.supportsRuntime(context) ||
                            !SpeechRuntimePack.Sovits.installed || !SpeechRuntimePack.SovitsModels.installed)
                            throw SpeechException(SpeechError.OfflineUnavailable)
                        permit.acquire()
                        synchronized(lifecycle) {
                            ownsPermit = true
                            generation.checkCancelled()
                        }
                        prepare(settings, npuRuntime.takeIf { settings.audio.sovitsNpu })
                    } finally { synchronized(lifecycle) { preparing = null } }
                }
            }
            return voice.character
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            if (failure is CancellationException || failure is SpeechException) throw failure
            if (failure is Exception || failure is LinkageError)
                throw SpeechException(SpeechError.OfflineUnavailable).apply { initCause(failure) }
            throw failure
        }
    }

    @RequiresApi(29)
    private suspend fun prepare(settings: SpeechSettings, npuRuntime: File?) {
        options = voice.options.withReadingRate(settings.rate)
        val cache = SovitsReferenceCache(voice)
        val cached = cache.read()
        prepareSovitsFiles(voice, encodeReference = cached == null)
        val audio = if (cached == null) readSovitsReference(voice.referenceAudio) else null
        nativeOperation {
            val languageFrontend = SovitsFrontend(voice.runtime)
            frontend = languageFrontend
            val prompt = languageFrontend.encode(voice.referenceText, voice.referenceLanguage)
            synchronized(lifecycle) {
                generation.checkCancelled()
                handle = SovitsNative.create(voice.model.absolutePath.encodeToByteArray(),
                    voice.runtime.absolutePath.encodeToByteArray())
            }
            val features = cached ?: checkNotNull(audio).let { (pcm32, pcm16) ->
                SovitsNative.encodeReference(handle, pcm32, pcm16).also {
                    generation.checkCancelled()
                    cache.write(it)
                }
            }
            android.util.Log.d("NextVolSoVITS", "Reference features cache hit=${cached != null}")
            SovitsNative.prepare(handle, prompt.phones, prompt.tokens, prompt.repeats, prompt.bertRuns, features)
            if (settings.audio.sovitsGpu && DeviceAcceleration.status.value.gpu == true) {
                acoustic = SovitsAcoustic(voice.copy(options = options), SovitsNative.condition(handle, false), SovitsNative.condition(handle, true))
                acoustic?.prepare(context, npuRuntime)
            }
            audioParameters = SpeechAudioParameters(volume = settings.volume)
        }
    }

    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
        writeWave(output) { it.append(pcm(text)) }
        return emptyList()
    }

    private suspend fun pcm(text: String): ByteArray {
        require(text.isNotBlank() && text.length <= maxInputLength)
        return nativeOperation {
            val input = checkNotNull(frontend).encode(text)
            val tokens = SovitsNative.semantics(handle, input.phones, input.tokens, input.repeats, input.bertRuns,
                options.samplingValues())
            val accelerated = generation.run { acoustic?.generate(input.phones, tokens) }
            accelerated ?: SovitsNative.vocode(handle, input.phones, tokens, options.speed.toDouble())
        }
    }

    private suspend fun writeWave(output: File, write: suspend (SpeechWaveFile) -> Unit) {
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                require(!output.exists()) { "Output file already exists" }
                val partial = File.createTempFile("sovits-", ".part", output.absoluteFile.parentFile)
                try {
                    SpeechWaveFile(partial, 32000).use { wave ->
                        write(wave)
                        wave.finish()
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(lifecycle) {
                        generation.checkCancelled()
                        Files.move(partial.toPath(), output.toPath())
                        committed = true
                    }
                } finally { partial.delete() }
            }
        } catch (failure: Exception) {
            if (committed) output.delete()
            if (failure is CancellationException || failure is SpeechException) throw failure
            throw SpeechException(if (failure is IOException) SpeechError.Storage else SpeechError.SynthesisFailed)
                .apply { initCause(failure) }
        }
    }

    // ORT allows another thread to terminate RunOptions while Run is in progress.
    private suspend fun <T> nativeOperation(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        continuation.resumeWith(runCatching {
            operation.withLock {
                generation.checkCancelled()
                try { block().also { generation.checkCancelled() } }
                catch (failure: Exception) { generation.checkCancelled(); throw failure }
            }
        })
    }

    override fun cancel() {
        val job = synchronized(lifecycle) {
            generation.cancel()
            if (handle != 0L) SovitsNative.cancel(handle)
            preparing
        }
        job?.cancel()
    }

    override fun close() {
        cancel()
        operation.withLock {
            synchronized(lifecycle) {
                try {
                    acoustic?.close()
                } finally {
                    acoustic = null
                    if (handle != 0L) SovitsNative.close(handle)
                    handle = 0
                    try { frontend?.close() } finally {
                        frontend = null
                        if (ownsPermit) { ownsPermit = false; permit.release() }
                    }
                }
            }
        }
    }

    private companion object { val permit = Semaphore(1) }
}
