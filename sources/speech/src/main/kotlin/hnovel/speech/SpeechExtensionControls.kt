package hnovel.speech

import hnovel.execution.BridgeWire
import kotlinx.serialization.json.*

internal fun speechParameterJson(text: String, field: String): JsonElement = try {
    Json.parseToJsonElement(BridgeWire.validate(text.toByteArray(Charsets.UTF_8)))
} catch (_: IllegalArgumentException) { configurationFailure(MatchaPackageError.Parameters, field) }

/** MultiTTS candidates map parameter values to labels. Keep unknown fields when editing a value. */
data class SpeechExtensionControl(
    val code: String,
    val name: String,
    val value: JsonPrimitive,
    val candidates: Map<String, String>,
    val supported: Boolean,
)

fun speechExtensionControls(text: String): List<SpeechExtensionControl> {
    if (text.isBlank()) return emptyList()
    val array = speechParameterJson(text, "extendUI") as? JsonArray
        ?: configurationFailure(MatchaPackageError.Parameters, "extendUI")
    val seen = mutableSetOf<String>()
    return array.map { element ->
        val item = element as? JsonObject ?: configurationFailure(MatchaPackageError.Parameters, "extendUI")
        val code = item.string("code")
        if (code.isBlank() || !seen.add(code)) configurationFailure(MatchaPackageError.Parameters, "extendUI.code")
        val options = (item["candidate"] as? JsonObject)?.mapValues { (_, label) ->
            (label as? JsonPrimitive)?.contentOrNull ?: configurationFailure(MatchaPackageError.Parameters, "extendUI.candidate")
        }.orEmpty()
        val supplied = item["value"]
        if (supplied != null && supplied !is JsonPrimitive) configurationFailure(MatchaPackageError.Parameters, "extendUI.value")
        val value = (supplied as? JsonPrimitive)?.takeUnless { it == JsonNull || it.content.isEmpty() }
            ?: JsonPrimitive(options.keys.firstOrNull().orEmpty())
        SpeechExtensionControl(code, item.string("name"), value, options,
            (item["candidate"] == null || item["candidate"] == JsonNull || item["candidate"] is JsonObject))
    }
}

fun updateSpeechExtension(text: String, code: String, value: String): String {
    val controls = speechExtensionControls(text)
    val control = controls.single { it.code == code }
    require(control.supported)
    require(value in control.candidates)
    val replacement = JsonPrimitive(value)
    return JsonArray((Json.parseToJsonElement(text) as JsonArray).map { element ->
        val item = element as JsonObject
        if (item.string("code") == code) JsonObject(item + ("value" to replacement)) else item
    }).toString()
}
