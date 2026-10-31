package indi.renakoni.nextvol.tts

import hnovel.speech.*
import kotlinx.serialization.json.*
import java.util.UUID

internal fun SavedHttpSpeechSource.speakerData(): JsonObject =
    (definition.raw["multiTts"] as? JsonObject)?.get("voice") as? JsonObject
        ?: definition.raw["nextvolVoice"] as? JsonObject ?: JsonObject(emptyMap())

/** Rebuild an imported speaker using its engine template; origins and engine credentials are not changed here. */
internal fun editHttpVoice(source: SavedHttpSpeechSource, fields: JsonObject, enabled: Boolean, copy: Boolean): SavedHttpSpeechSource {
    val voice = JsonObject(source.speakerData() + fields)
    val name = voice.getValue("name").jsonPrimitive.content
    val metadata = source.definition.raw["multiTts"] as? JsonObject
    val generated = if (metadata != null && source.importedEngineCode != null) {
        val engine = requireNotNull(source.importedEngine)
        val code = requireNotNull(source.importedEngineCode)
        val converted = previewHttpSpeechPackage(SpeechPackageEngine(code, source.category.orEmpty(), "http", engine.packageValue(),
            listOf(SpeechPackageVoice(voice.getValue("code").jsonPrimitive.content, name, voice.packageValue())),
            SpeechPackageSupport.HttpAdapter, emptyList(), emptyList()))
        require(converted.error == null && converted.sources.size == 1)
        converted.sources.single().raw
    } else source.definition.raw
    val raw = JsonObject(source.definition.raw + generated + mapOf(
        "id" to JsonPrimitive(if (copy) "nextvol:voice:${UUID.randomUUID()}" else source.definition.id),
        "name" to JsonPrimitive(name), "nextvolEnabled" to JsonPrimitive(enabled)) +
        (source.group?.let { mapOf("nextvolEngineName" to JsonPrimitive(it)) } ?: emptyMap()) +
        if (metadata != null) mapOf("multiTts" to JsonObject(metadata + ("voice" to voice))) else mapOf("nextvolVoice" to voice))
    val definition = previewHttpSpeech(raw.toString()).sources.single()
    return source.copy(definition = definition, group = if (copy) null else source.group,
        engineReference = if (copy && source.isBuiltIn) source.definition.id else source.engineReference,
        speakerDefaults = source.speakerDefaults ?: source.defaultSpeaker(), isVoiceCopy = copy || source.isVoiceCopy)
}

internal fun SavedHttpSpeechSource.defaultSpeaker() = JsonObject(speakerData() + ("name" to JsonPrimitive(definition.name)))

internal fun SavedHttpSpeechSource.packageIdentity(): Triple<String, String, String>? {
    if (isVoiceCopy) return null
    val engine = importedEngineCode ?: return null
    val defaults = speakerDefaults ?: speakerData()
    val code = (defaults["code"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
    val type = (defaults["type"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { "0" }
    return Triple(engine, code, type.toIntOrNull()?.toString() ?: type)
}

/** Refresh package defaults without dropping explicit speaker edits or changing saved references. */
internal fun mergeHttpVoice(previous: SavedHttpSpeechSource?, incoming: SavedHttpSpeechSource): SavedHttpSpeechSource {
    if (incoming.packageIdentity() == null) return incoming
    val defaults = incoming.defaultSpeaker()
    if (previous == null) return incoming.copy(speakerDefaults = defaults)
    val baseline = previous.speakerDefaults ?: previous.defaultSpeaker()
    val overrides = previous.defaultSpeaker().filter { (key, value) -> value != baseline[key] }
    val raw = JsonObject(incoming.definition.raw + ("id" to JsonPrimitive(previous.definition.id)))
    return editHttpVoice(incoming.copy(definition = previewHttpSpeech(raw.toString()).sources.single(),
        speakerDefaults = defaults, packageAvatar = incoming.packageAvatar ?: previous.packageAvatar.takeIf {
            defaults["avatar"] == previous.defaultSpeaker()["avatar"]
        }), JsonObject(defaults + overrides), previous.enabled, copy = false)
}
