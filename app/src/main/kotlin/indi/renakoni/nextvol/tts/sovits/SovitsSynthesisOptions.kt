package indi.renakoni.nextvol.tts.sovits

import hnovel.speech.OfflineVoiceConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The same per-request controls and ranges as Genie-TTS-Android's GPT library. */
@Serializable
data class SovitsSynthesisOptions(
    val speed: Float = 1f,
    val temperature: Float = 1f,
    val topK: Int = 15,
    val topP: Float = 1f,
    val repetitionPenalty: Float = 1.35f,
) {
    init {
        require(speed in 0.6f..1.65f)
        require(temperature in 0f..1f)
        require(topK in 1..100)
        require(topP in 0f..1f)
        require(repetitionPenalty in 1f..2f)
    }

    fun samplingValues() = floatArrayOf(temperature, topK.toFloat(), topP, repetitionPenalty)

    internal fun withReadingRate(rate: Float) = copy(speed = sovitsReadingSpeed(speed, rate))
}

internal fun sovitsReadingSpeed(voiceSpeed: Float, rate: Float): Float {
    val normalized = if (rate <= 1f) .6f + .4f * rate else 1f + .65f * (rate - 1f)
    return (voiceSpeed * normalized).coerceIn(.6f, 1.65f)
}

private fun sovitsParameters(configuration: OfflineVoiceConfig): JsonObject =
    Json.parseToJsonElement(configuration.parameters).jsonObject

internal fun sovitsModelCode(configuration: OfflineVoiceConfig): String =
    sovitsParameters(configuration).getValue("model").jsonPrimitive.content

internal fun sovitsOptions(configuration: OfflineVoiceConfig): SovitsSynthesisOptions =
    Json.decodeFromJsonElement(JsonObject(sovitsParameters(configuration) - "model" +
        ("speed" to JsonPrimitive(configuration.speed))))

internal fun OfflineVoiceConfig.withSovitsOptions(options: SovitsSynthesisOptions): OfflineVoiceConfig {
    val parameters = JsonObject(Json.encodeToJsonElement(options).jsonObject - "speed" +
        ("model" to JsonPrimitive(sovitsModelCode(this)))).toString()
    return copy(parameters = parameters, rawParameters = parameters, extendUI = "", engineParameters = "",
        speed = options.speed, pitch = 1f, volume = 1f, sampleRate = 32000, type = 0)
}
