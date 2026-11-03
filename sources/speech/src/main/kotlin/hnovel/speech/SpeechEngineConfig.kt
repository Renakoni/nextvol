package hnovel.speech

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

fun isOnlineSpeechEngine(code: String, type: String?): Boolean =
    type == "http" || (code in setOf("mstrans", "edgetts") || code in dedicatedOnlineSpeechEngines) && (type == null || type == "inner")

@Serializable
data class SpeechEngineConfig(
    val code: String,
    val name: String,
    val type: String = "inner",
    val parameters: String = "",
    val note: String = "",
    val url: String = "",
    val header: String = "",
    val enabled: Boolean = true,
    val deleted: Boolean = false,
    val order: Int = Int.MAX_VALUE,
    val raw: JsonObject? = null,
) {
    val isOnline: Boolean get() = isOnlineSpeechEngine(code, type)
    override fun toString() = "SpeechEngineConfig(code=$code)"
}
