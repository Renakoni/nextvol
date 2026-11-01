package hnovel.speech

import hnovel.execution.BridgeWire
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Imported source data is retained verbatim as JSON; parsing never executes scripts or sends requests. */
class HttpSpeechDefinition internal constructor(val id: String, val raw: JsonObject) {
    val name: String get() = raw.getValue("name").jsonPrimitive.content
    val url: String get() = raw.getValue("url").jsonPrimitive.content
    val revision: String = digest(raw.canonical().toString())
    internal val microsoft: MicrosoftSpeechConfiguration? by lazy { MicrosoftSpeechConfiguration.recognize(this) }
    val usesMicrosoftTranslator: Boolean get() = microsoft != null
    internal val multiTts: JsonObject? get() = (raw["multiTts"] as? JsonObject)?.takeIf { metadata ->
        val engine = metadata["engine"] as? JsonObject
        val voice = metadata["voice"] as? JsonObject
        (engine?.get("type") as? JsonPrimitive)?.contentOrNull == "http" &&
            !(engine?.get("url") as? JsonPrimitive)?.contentOrNull.isNullOrBlank() &&
            !(voice?.get("code") as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
    }
    val usesMultiTts: Boolean get() = multiTts != null
    val unknownFields: Set<String> get() = raw.keys - fields
    val speed: Int? get() = raw["speed"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
    val hasScripts: Boolean get() = url.contains("{{") || url.contains("@js:", true) || url.contains("<js>", true)
        || !text("jsLib").isBlank()
    val hasConfiguration: Boolean get() = !text("loginUrl").isBlank() || !text("loginUi").isBlank()

    fun text(field: String): String = when (val value = raw[field]) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    /** Config speed=5 is 1x; the reference passes speed+5 to the speakSpeed script binding. */
    fun scriptSpeed(rate: Float? = null): Int {
        require(rate == null || rate.isFinite() && rate in 0.5f..2f)
        return if (rate == null) (speed ?: 5) + 5 else kotlin.math.round(rate * 10).toInt()
    }

    override fun toString() = "HttpSpeechDefinition(id=$id, fields=${raw.size})"

    companion object {
        internal val fields = setOf("id", "name", "url", "contentType", "header", "jsLib", "loginUrl", "loginUi",
            "loginCheckJs", "concurrentRate", "enabledCookieJar", "speed", "lastUpdateTime")

        internal fun parse(raw: JsonObject): HttpSpeechDefinition {
            fun string(key: String): String? = raw[key]?.takeUnless { it == JsonNull }?.let {
                require(it is JsonPrimitive && it.isString) { key }
                it.content
            }
            require(!string("name").isNullOrBlank() && string("name")!!.length <= 256) { "name" }
            require(!string("url").isNullOrBlank() && string("url")!!.length <= 256 * 1024) { "url" }
            for (key in listOf("contentType", "jsLib", "loginUrl", "loginCheckJs", "concurrentRate")) string(key)
            for (key in listOf("header", "loginUi")) {
                val value = raw[key]
                require(value == null || value == JsonNull || value is JsonObject ||
                    key == "loginUi" && value is JsonArray || value is JsonPrimitive && value.isString) { key }
            }
            raw["speed"]?.takeUnless { it == JsonNull }?.let {
                require(it is JsonPrimitive && !it.isString && it.intOrNull in 0..80) { "speed" }
            }
            raw["enabledCookieJar"]?.takeUnless { it == JsonNull }?.let {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "enabledCookieJar" }
            }
            val id = raw["id"]?.takeUnless { it == JsonNull }?.let {
                require(it is JsonPrimitive && (it.isString || it.longOrNull != null) && it.content.isNotBlank()
                    && it.content.length <= 128) { "id" }
                it.content
            } ?: "sha256:${digest(raw.canonical().toString())}"
            return HttpSpeechDefinition(id, raw)
        }
    }
}

enum class SpeechImportError { TooLarge, InvalidJson, InvalidEntry, DuplicateId }
data class SpeechImportIssue(val index: Int?, val error: SpeechImportError, val field: String? = null)
data class SpeechImportPreview(val sources: List<HttpSpeechDefinition>, val issues: List<SpeechImportIssue>)

/** Unknown fields survive preview and saving, including dependencies not supported by the current runtime. */
fun previewHttpSpeech(text: String): SpeechImportPreview {
    fun failed(error: SpeechImportError) = SpeechImportPreview(emptyList(), listOf(SpeechImportIssue(null, error)))
    if (text.length > 4 * 1024 * 1024 || text.toByteArray().size > 4 * 1024 * 1024) return failed(SpeechImportError.TooLarge)
    val element = try { Json.parseToJsonElement(BridgeWire.validate(text.removePrefix("\uFEFF").toByteArray(Charsets.UTF_8), 4 * 1024 * 1024)) }
        catch (_: IllegalArgumentException) { return failed(SpeechImportError.InvalidJson) }
    val entries = when (element) {
        is JsonObject -> listOf(element)
        is JsonArray -> element.toList()
        else -> return failed(SpeechImportError.InvalidJson)
    }
    if (entries.size > 512) return failed(SpeechImportError.TooLarge)
    val sources = mutableListOf<HttpSpeechDefinition>()
    val issues = mutableListOf<SpeechImportIssue>()
    val seen = mutableSetOf<String>()
    entries.forEachIndexed { index, value ->
        if (value !is JsonObject) issues += SpeechImportIssue(index, SpeechImportError.InvalidEntry)
        else try {
            val source = HttpSpeechDefinition.parse(value)
            if (!seen.add(source.id)) issues += SpeechImportIssue(index, SpeechImportError.DuplicateId, "id")
            else sources += source
        } catch (failure: IllegalArgumentException) {
            issues += SpeechImportIssue(index, SpeechImportError.InvalidEntry, failure.message?.takeIf { it in HttpSpeechDefinition.fields })
        }
    }
    return SpeechImportPreview(sources, issues)
}

private fun JsonElement.canonical(): JsonElement = when (this) {
    is JsonObject -> JsonObject(toSortedMap().mapValues { it.value.canonical() })
    is JsonArray -> JsonArray(map { it.canonical() })
    else -> this
}

private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
