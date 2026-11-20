package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import androidx.annotation.RequiresApi
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import indi.renakoni.nextvol.tts.*
import indi.renakoni.nextvol.tts.audio.PcmAudioProcessor
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    internal var lastProvider: String = "CPU"
        private set
    private var handle = 0L
    private var ownsPermit = false
    private var preparing: Job? = null
    private var frontend: SovitsFrontend? = null

    override suspend fun open(settings: SpeechSettings): String {
        check(opened.compareAndSet(false, true))
        try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val job = currentCoroutineContext().job
                    synchronized(lifecycle) { generation.checkCancelled(); preparing = job }
                    try {
                        if (!DeviceAcceleration.supportsRuntime(context) || !SpeechRuntimePack.Sovits.directory.isDirectory)
                            throw SpeechException(SpeechError.OfflineUnavailable)
                        permit.acquire()
                        synchronized(lifecycle) {
                            ownsPermit = true
                            generation.checkCancelled()
                        }
                        prepare(settings)
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
    private suspend fun prepare(settings: SpeechSettings) {
        prepareSovitsFiles(voice)
        val (pcm32, pcm16) = readSovitsReference(voice.referenceAudio)
        nativeOperation {
            val languageFrontend = SovitsFrontend(voice.runtime)
            frontend = languageFrontend
            val prompt = languageFrontend.encode(voice.referenceText, voice.referenceLanguage)
            synchronized(lifecycle) {
                generation.checkCancelled()
                handle = SovitsNative.create(voice.model.absolutePath.encodeToByteArray(),
                    voice.runtime.absolutePath.encodeToByteArray())
            }
            SovitsNative.prepare(handle, prompt.phones, prompt.tokens, prompt.repeats, prompt.bertRuns, pcm32, pcm16)
            try {
                acoustic = SovitsAcoustic(voice, SovitsNative.condition(handle, false), SovitsNative.condition(handle, true))
                acoustic?.prepare(context)
            } catch (failure: Throwable) {
                if (failure !is Exception && failure !is LinkageError) throw failure
                generation.checkCancelled()
                acoustic?.close()
                acoustic = null
                android.util.Log.w("NextVolSoVITS", "Accelerated voice unavailable", failure)
            }
            audioParameters = SpeechAudioParameters(settings.rate.coerceAtLeast(0.1f), settings.pitch, settings.volume)
        }
    }

    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
        writeWave(output) { it.append(pcm(text)) }
        return emptyList()
    }

    suspend fun downloadChapter(chapter: SpeechChapter, output: File) {
        val task = currentCoroutineContext()
        val settings = SpeechSettings()
        val plan = prepareSpeechPlan(chapter, settings, maxInputLength = maxInputLength, checkCancelled = task::ensureActive)
        if (plan.segments.isEmpty()) throw SpeechException(SpeechError.UnsupportedContent)
        try {
            open(settings)
            writeWave(output) { wave ->
                for (segment in plan.segments) {
                    task.ensureActive()
                    val pause = segment.pauseMs
                    if (pause != null) {
                        wave.appendSilence(pause, task::ensureActive)
                        continue
                    }
                    val bytes = pcm(segment.text)
                    val controls = segment.audioParameters
                    if (controls == SpeechAudioParameters()) wave.append(bytes)
                    else {
                        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        PcmAudioProcessor(32000, 32000, speed = controls.speed, pitch = controls.pitch,
                            volume = controls.volume).use { processor ->
                            wave.append(processor.process(ShortArray(input.remaining()).also { input.get(it) }))
                            wave.append(processor.finish())
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { close() }
        }
    }

    private suspend fun pcm(text: String): ByteArray {
        require(text.isNotBlank() && text.length <= maxInputLength)
        return nativeOperation {
            val input = checkNotNull(frontend).encode(text)
            val tokens = SovitsNative.semantics(handle, input.phones, input.tokens, input.repeats, input.bertRuns)
            val accelerated = generation.run { acoustic?.generate(input.phones, tokens) }
            lastProvider = if (accelerated != null) checkNotNull(acoustic).lastProvider else "CPU"
            accelerated ?: SovitsNative.vocode(handle, input.phones, tokens)
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
