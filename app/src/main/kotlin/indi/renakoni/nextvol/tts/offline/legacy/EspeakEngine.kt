package indi.renakoni.nextvol.tts.offline.legacy

import androidx.annotation.Keep
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import java.io.File

/** MultiTTS p4.a: language code, optional voice identifier and variant. */
internal class EspeakEngine(directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    @Volatile private var cancelled = false
    private var closed = false
    override val sampleRate: Int

    init {
        val parameters = voice.parameters.split(',').map(String::trim)
        val variant = parameters.getOrNull(1)?.ifEmpty { null } ?: "female"
        val parts = variant.split('-')
        val gender = when (parts[0]) { "male" -> 1; "female" -> 2; else -> 0 }
        val age = if (parts.size == 2) { if (parts[1] == "young") 12 else 60 } else 0
        sampleRate = EspeakNative.create(File(directory, "espeak-ng-data").absolutePath,
            voice.code, parameters[0], if (gender == 0) parts[0] else "", gender, age)
    }

    override fun synthesize(text: String, speed: Float, volume: Float, emit: (ByteArray, Int) -> Unit) {
        check(!closed)
        EspeakNative.synthesize(text.toByteArray(Charsets.UTF_8),
            (100f + 4f * speed).toInt(), (volume * 2f).toInt()) { pcm ->
            if (cancelled) throw CancellationException("eSpeak synthesis cancelled")
            if (pcm.isNotEmpty()) emit(pcm, pcm.size)
        }
    }

    override fun cancel() { cancelled = true }

    override fun close() {
        if (!closed) {
            closed = true
            EspeakNative.destroy()
        }
    }
}

@Keep
internal fun interface EspeakPcmCallback {
    fun audio(pcm: ByteArray)
}

@Keep
internal object EspeakNative {
    init { System.loadLibrary("nextvol-espeak") }
    external fun create(data: String, language: String, identifier: String, variant: String, gender: Int, age: Int): Int
    external fun synthesize(text: ByteArray, rate: Int, volume: Int, callback: EspeakPcmCallback)
    external fun destroy()
}
