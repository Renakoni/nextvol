package indi.renakoni.nextvol.tts

import hnovel.speech.HttpSpeechDefinition
import hnovel.speech.OfflineVoiceConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

@Serializable
data class SpeechAudioSettings(
    val processor: Int = 200,
    val minimumSampleRate: Int = 16000,
    val checkSilence: Boolean = false,
    val decodeStream: Boolean = false,
)

/** Only adjustments not already applied by the backend are sent to the common PCM processor. */
data class SpeechAudioParameters(val speed: Float = 1f, val pitch: Float = 1f, val volume: Float = 1f)

internal fun offlineAudioParameters(voice: OfflineVoiceConfig, settings: SpeechSettings) =
    SpeechAudioParameters(voice.speed, voice.pitch * settings.pitch, voice.volume)

internal fun httpAudioParameters(source: HttpSpeechDefinition, settings: SpeechSettings): SpeechAudioParameters {
    val voice = httpVoiceParameters(source)
    // MultiTTS Speaker parameters are post-processing; request bindings carry the separate global values.
    return voice.copy(pitch = voice.pitch * settings.pitch,
        volume = voice.volume * if (source.usesMultiTts) 1f else settings.volume)
}

internal fun httpVoiceParameters(source: HttpSpeechDefinition): SpeechAudioParameters {
    val voice = (if (source.usesMultiTts) (source.raw["multiTts"] as? JsonObject)?.get("voice") as? JsonObject else null)
        ?: source.raw["nextvolVoice"] as? JsonObject
    fun number(name: String) = (voice?.get(name) as? JsonPrimitive)?.floatOrNull ?: 1f
    return SpeechAudioParameters(number("speed"), number("pitch"), number("volume"))
}
