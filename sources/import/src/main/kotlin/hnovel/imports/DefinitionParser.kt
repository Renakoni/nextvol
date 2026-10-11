package hnovel.imports

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import hnovel.network.LocalStorageRetention
import kotlinx.serialization.json.*
import java.io.StringReader

/** Streaming validation catches duplicate fields and bounds nesting before constructing a JSON tree. */
internal fun parseDefinitionJson(text: String, maxDepth: Int): JsonElement {
    JsonReader(StringReader(text.removePrefix("\uFEFF"))).use { reader ->
        reader.strictness = Strictness.STRICT
        fun read(depth: Int): JsonElement {
            if (depth > maxDepth) throw ImportFailure(ImportCode.TooDeep)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val fields = linkedMapOf<String, JsonElement>()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name in fields) throw ImportFailure(ImportCode.DuplicateField)
                        fields[name] = read(depth + 1)
                    }
                    reader.endObject()
                    JsonObject(fields)
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    val values = mutableListOf<JsonElement>()
                    while (reader.hasNext()) values.add(read(depth + 1))
                    reader.endArray()
                    JsonArray(values)
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> Json.parseToJsonElement(reader.nextString())
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull }
                else -> throw ImportFailure(ImportCode.InvalidJson)
            }
        }
        try {
            val result = read(0)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw ImportFailure(ImportCode.InvalidJson)
            return result
        } catch (failure: Exception) {
            // Gson exposes its cursor in the reader description. Retain only numeric coordinates.
            val location = Regex(" at line (\\d+) column (\\d+) path ").find(reader.toString())
            throw ImportFailure((failure as? ImportFailure)?.code ?: ImportCode.InvalidJson,
                (failure as? ImportFailure)?.field, location?.groupValues?.get(1)?.toIntOrNull(),
                location?.groupValues?.get(2)?.toIntOrNull())
        }
    }
}

data class ParsedSource(val key: String, val name: String, val enabled: Boolean, val enabledExplore: Boolean,
    val notices: List<ImportNotice>)

/** Adapters recognize/validate definitions; they never create an execution engine. */
interface SourceFormatAdapter {
    val format: String
    val profiles: Set<String>
    fun recognizes(value: JsonObject): Boolean
    fun validate(value: JsonObject): ParsedSource
}

class LegadoSourceAdapter : SourceFormatAdapter {
    override val format = "legado-json"
    override val profiles = setOf(LEGADO_PROFILE, EXTENSION_PROFILE)
    override fun recognizes(value: JsonObject) = "bookSourceUrl" in value || "bookSourceName" in value

    override fun validate(value: JsonObject): ParsedSource {
        fun string(key: String): String? {
            val element = value[key] ?: return null
            if (element == JsonNull) return null
            if (element !is JsonPrimitive || !element.isString) throw ImportFailure(ImportCode.InvalidField, key)
            return element.content
        }
        fun boolean(key: String, default: Boolean): Boolean {
            val element = value[key] ?: return default
            if (element == JsonNull) return default
            return (element as? JsonPrimitive)?.booleanOrNull ?: throw ImportFailure(ImportCode.InvalidField, key)
        }
        fun number(key: String): Long? {
            val element = value[key] ?: return null
            if (element == JsonNull) return null
            return (element as? JsonPrimitive)?.longOrNull ?: throw ImportFailure(ImportCode.InvalidField, key)
        }
        val key = string("bookSourceUrl")?.takeIf { it.isNotBlank() }
            ?: throw ImportFailure(ImportCode.MissingIdentity, "bookSourceUrl")
        val name = string("bookSourceName")?.takeIf { it.isNotBlank() } ?: key
        if ((number("bookSourceType") ?: 0) != 0L) throw ImportFailure(ImportCode.UnsupportedType, "bookSourceType")
        val enabled = boolean("enabled", true)
        val explore = boolean("enabledExplore", true)
        boolean("enabledCookieJar", true)
        boolean("browserRead", false)
        try { LocalStorageRetention.parse(value[LocalStorageRetention.FIELD]) }
        catch (_: IllegalArgumentException) { throw ImportFailure(ImportCode.InvalidField, LocalStorageRetention.FIELD) }
        for (flag in listOf("customButton", "eventListener")) {
            boolean(flag, false)
            if ((value[flag] as? JsonPrimitive)?.isString == true) throw ImportFailure(ImportCode.InvalidField, flag)
        }
        val strings = setOf("bookSourceGroup", "bookUrlPattern", "jsLib", "header",
            "loginUrl", "loginUi", "loginCheckJs", "coverDecodeJs", "bookSourceComment", "variableComment",
            "exploreUrl", "exploreScreen", "homepageModules", "searchUrl")
        strings.forEach(::string)
        val numbers = setOf("customOrder", "lastUpdateTime", "respondTime", "weight")
        numbers.forEach(::number)
        // Legado keeps intervals and request/window rates as nullable text. Preserve the numeric
        // form accepted by earlier imports too; RuleSource configures runtime admission pacing.
        value["concurrentRate"]?.takeUnless { it == JsonNull }?.let { rate ->
            if (rate !is JsonPrimitive || !rate.isString && rate.longOrNull == null)
                throw ImportFailure(ImportCode.InvalidField, "concurrentRate")
        }
        val rules = setOf("ruleExplore", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleReview")
        rules.forEach { field ->
            val rule = value[field]
            // Legacy exports use [] for an absent optional rule; the reference reads it as null.
            if (rule != null && rule != JsonNull && rule !is JsonObject && !(rule is JsonArray && rule.isEmpty()))
                throw ImportFailure(ImportCode.InvalidField, field)
        }
        val known = strings + numbers + rules + setOf("bookSourceUrl", "bookSourceName", "bookSourceType", "enabled", "enabledExplore", "enabledCookieJar", "browserRead", "customButton", "eventListener", "concurrentRate", LocalStorageRetention.FIELD)
        val notices = value.keys.filter { it !in known }.map { ImportNotice("UnclassifiedField", it) }.toMutableList()
        // Parsing a rule object is not a claim that its fields or scripts are executable.
        notices.add(ImportNotice("ExecutionCompatibilityPending"))
        if ("customOrder" in value) notices.add(ImportNotice("ExternalOrderRetainedNotApplied", "customOrder"))
        listOf("customButton", "eventListener").filter { it in value }.forEach { notices.add(ImportNotice("DiscoveryExtension", it)) }
        return ParsedSource(key, name, enabled, explore, notices)
    }
}

internal fun canonical(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(value.map(::canonical))
    else -> value
}
