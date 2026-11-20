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
        require(speed.isFinite() && speed in 0.6f..1.65f)
        require(temperature.isFinite() && temperature in 0f..1f)
        require(topK in 1..100)
        require(topP.isFinite() && topP in 0f..1f)
        require(repetitionPenalty.isFinite() && repetitionPenalty in 1f..2f)
    }

    fun samplingValues() = floatArrayOf(temperature, topK.toFloat(), topP, repetitionPenalty)
}

private fun sovitsParameters(configuration: OfflineVoiceConfig): JsonObject =
    (configuration.rawParameters ?: configuration.parameters).let {
        if (it.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(it).jsonObject
    }

internal fun sovitsModelCode(configuration: OfflineVoiceConfig): String =
    sovitsParameters(configuration)["model"]?.jsonPrimitive?.content ?: configuration.code

internal fun sovitsOptions(configuration: OfflineVoiceConfig): SovitsSynthesisOptions =
    Json.decodeFromJsonElement(JsonObject(sovitsParameters(configuration) - "model" +
        ("speed" to JsonPrimitive(configuration.speed))))

internal fun OfflineVoiceConfig.withSovitsOptions(options: SovitsSynthesisOptions): OfflineVoiceConfig {
    val parameters = JsonObject(Json.encodeToJsonElement(options).jsonObject - "speed" +
        ("model" to JsonPrimitive(sovitsModelCode(this)))).toString()
    return copy(parameters = parameters, rawParameters = parameters, extendUI = "", engineParameters = "",
        speed = options.speed, pitch = 1f, volume = 1f, sampleRate = 32000, type = 0)
}
