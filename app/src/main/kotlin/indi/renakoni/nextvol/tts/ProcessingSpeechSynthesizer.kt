package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.audio.processSpeechAudio
import indi.renakoni.nextvol.tts.audio.StreamingAudioInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.io.File

/** All backends finish through the same PCM path; playbackSpeed remains the player's responsibility. */
internal class ProcessingSpeechSynthesizer(private val delegate: SpeechSynthesizer) : SpeechSynthesizer {
    private var settings = SpeechAudioSettings()
    private var parameters = SpeechAudioParameters()
    @Volatile private var work: Job? = null
    override val maxInputLength get() = delegate.maxInputLength
    override val supportsStreaming get() = delegate.supportsStreaming

    override suspend fun open(settings: SpeechSettings): String {
        val label = delegate.open(settings)
        this.settings = settings.audio
        parameters = delegate.audioParameters
        return label
    }

    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> = file(text, output, parameters)

    private suspend fun file(text: String, output: File, parameters: SpeechAudioParameters): List<SpeechTiming> = processing {
        val raw = File(output.parentFile, output.name + ".raw")
        try {
            val timings = delegate.synthesize(text, raw)
            val processed = processSpeechAudio(raw, output, settings.processor, settings.minimumSampleRate,
                parameters.speed, parameters.pitch, parameters.volume, settings.checkSilence)
            timings.map { it.copy(timeMs = processed.mapTimeMs(it.timeMs)) }
        } finally { raw.delete() }
    }

    override suspend fun synthesize(text: String, output: File, onReady: suspend (SpeechStream) -> Unit): List<SpeechTiming> =
        synthesize(text, output, parameters, onReady)

    override suspend fun synthesize(segment: SpeechSegment, output: File, onReady: suspend (SpeechStream) -> Unit): List<SpeechTiming> =
        synthesize(segment.text, output, parameters * segment.audioParameters, onReady)

    private suspend fun synthesize(text: String, output: File, parameters: SpeechAudioParameters,
        onReady: suspend (SpeechStream) -> Unit): List<SpeechTiming> {
        if (!settings.decodeStream || !delegate.supportsStreaming) return file(text, output, parameters)
        return processing { coroutineScope {
            val input = StreamingAudioInput(currentCoroutineContext().job)
            var stream: SpeechStream? = null
            val receiving = launch(Dispatchers.IO) {
                try { delegate.synthesizeEncoded(text, input::receive); input.finish() }
                catch (failure: Throwable) { input.finish(failure) }
            }
            try {
                processSpeechAudio(output, null, settings.processor, settings.minimumSampleRate,
                    parameters.speed, parameters.pitch, parameters.volume, settings.checkSilence, inputSource = input,
                    onPcm = { rate, bytes ->
                        val active = stream ?: SpeechStream(rate).also { stream = it; onReady(it) }
                        active.append(bytes)
                    })
                receiving.join()
                input.failure?.let { throw it }
                stream?.finish()
                emptyList()
            } catch (failure: Throwable) {
                val cause = input.failure ?: failure
                stream?.fail(cause)
                throw cause
            } finally { receiving.cancel(); input.close() }
        } }
    }

    private suspend fun <T> processing(block: suspend () -> T): T {
        work = currentCoroutineContext().job
        try {
            return block().also { delegate.audioProcessed(null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            delegate.audioProcessed(failure.speechError(SpeechError.AudioProcessing))
            throw failure
        } finally { work = null }
    }

    override fun cancel() {
        try { delegate.cancel() } finally { work?.cancel() }
    }

    override fun close() = delegate.close()
}
