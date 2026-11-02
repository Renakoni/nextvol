package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.audio.processSpeechAudio
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

    override suspend fun open(settings: SpeechSettings): String {
        val label = delegate.open(settings)
        this.settings = settings.audio
        parameters = delegate.audioParameters
        return label
    }

    override suspend fun synthesize(text: String, output: File): List<SpeechTiming> {
        work = currentCoroutineContext().job
        val raw = File(output.parentFile, output.name + ".raw")
        try {
            val timings = delegate.synthesize(text, raw)
            val processed = processSpeechAudio(raw, output, settings.processor, settings.minimumSampleRate,
                parameters.speed, parameters.pitch, parameters.volume, settings.checkSilence)
            return timings.map { it.copy(timeMs = processed.mapTimeMs(it.timeMs)) }
        } finally { work = null; raw.delete() }
    }

    override fun cancel() {
        try { delegate.cancel() } finally { work?.cancel() }
    }

    override fun close() = delegate.close()
}
