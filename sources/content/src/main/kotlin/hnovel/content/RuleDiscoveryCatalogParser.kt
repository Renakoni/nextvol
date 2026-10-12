package hnovel.content

import hnovel.rules.RequestOptionsJson
import kotlinx.serialization.json.*

/** Shared structural parsing for static capability checks and evaluated catalogues. No scripts run here. */
internal object RuleDiscoveryCatalogParser {
    fun homepageModules(raw: String): JsonArray? {
        if (raw.isBlank()) return null
        val modules = Json.parseToJsonElement(raw) as? JsonArray
            ?: throw SourceContentException(ContentError.InvalidRule, "homepageModules")
        if (modules.size > 64) throw SourceContentException(ContentError.Limit, "homepageModules")
        if (modules.any { it !is JsonObject }) throw SourceContentException(ContentError.InvalidRule, "homepageModules")
        return modules
    }

    fun homepage(modules: JsonArray?, rows: List<RuleDiscoveryRow>): List<RuleDiscoveryRow>? {
        modules ?: return null
        val keys = mutableSetOf<String>()
        return modules.mapIndexedNotNull { index, value ->
            val field = "homepageModules[$index]"
            val module = value as? JsonObject ?: throw SourceContentException(ContentError.InvalidRule, field)
            val key = module.string("key")
            val title = module.string("title")
            if (key.isBlank() || key.length > 256 || !keys.add(key))
                throw SourceContentException(ContentError.InvalidRule, "$field.key")
            if (title.isBlank() || title.length > 256)
                throw SourceContentException(ContentError.InvalidRule, "$field.title")
            // These modules carry book lists; rendering remains owned by the host.
            if (module.string("type") !in setOf("ranking", "card", "grid", "banner", "gridRanking", "infiniteGrid", "waterfall"))
                throw SourceContentException(ContentError.MissingCapability, "$field.type")
            val url = module.string("url").ifBlank {
                val kindTitle = module.string("kindTitle")
                if (kindTitle.isBlank()) throw SourceContentException(ContentError.InvalidRule, "$field.kindTitle")
                val matches = rows.filter { it.type == "url" && it.title == kindTitle }
                // Source settings may hide explicitly optional categories, including every module.
                if (matches.isEmpty() && module["optional"] == JsonPrimitive(true)) return@mapIndexedNotNull null
                matches.singleOrNull()?.url
                    ?: throw SourceContentException(ContentError.InvalidRule, "$field.kindTitle")
            }
            if (url.isBlank()) throw SourceContentException(ContentError.InvalidRule, "$field.url")
            RuleDiscoveryRow("homepage:$key", title, "url", url, field = field)
        }
    }

    fun rows(value: JsonElement, field: String): List<RuleDiscoveryRow> {
        val raw = if (value is JsonPrimitive && value.isString) {
            val text = value.content.trim()
            // Legado reads ExploreKind arrays with lenient Gson, including single quotes and
            // bare keys. Catalogues have a separate budget from network request options.
            if (text.startsWith('[')) {
                if (text.length > 512000) throw SourceContentException(ContentError.Limit, field)
                try { RequestOptionsJson.parse(text, maxChars = 512000) }
                catch (_: hnovel.rules.RequestOptionsException) { throw SourceContentException(ContentError.InvalidRule, field) }
            } else buildJsonArray {
                text.split(Regex("(?:&&|\\r?\\n)+")).filter(String::isNotBlank).forEach { item ->
                    val parts = item.split("::", limit = 2)
                    add(buildJsonObject { put("title", parts[0]); put("url", parts.getOrElse(1) { "" }) })
                }
            }
        } else value
        if (raw !is JsonArray) throw SourceContentException(ContentError.InvalidRule, field)
        if (raw.size > MAX_CATALOG_ROWS) throw SourceContentException(ContentError.Limit, field)
        val occurrences = mutableMapOf<String, Int>()
        return raw.mapIndexedNotNull { index, item ->
            // Legado's lenient Gson array reader filters nulls, including trailing commas.
            if (field == "exploreUrl" && item == JsonNull) return@mapIndexedNotNull null
            val location = "$field[$index]"
            val row = item as? JsonObject ?: throw SourceContentException(ContentError.InvalidRule, location)
            // Keep style acceptance aligned with LoginForm.parse: sources may declare it, but the
            // host owns layout. Tightening either row schema must preserve this shared decision.
            val unknown = row.keys - setOf("id", "title", "url", "type", "action", "chars", "default", "viewName", "style", "targetPrefixes", "showInCategories")
            if (unknown.isNotEmpty()) throw SourceContentException(ContentError.InvalidRule, "$location.${unknown.first()}")
            // Blank URL rows only fill Legado's grid. Skip inert spacers before assigning IDs,
            // but keep unnamed targets: ExploreKind permits a clickable URL without a title.
            if (field == "exploreUrl" && layoutSeparator(row)) return@mapIndexedNotNull null
            val name = row.string("title")
            val type = row.string("type").ifBlank { "url" }
            if (type !in inputTypes + setOf("url", "button") || field == "exploreScreen" && type == "url")
                throw SourceContentException(ContentError.InvalidRule, "$location.type")
            val url = row.string("url")
            // Maintained catalogues can retain homepage targets without presenting them as genres.
            // Imported definitions keep their existing category behaviour unless they opt in.
            val showInCategories = row["showInCategories"]?.let {
                if (type != "url" || it !in listOf(JsonPrimitive(true), JsonPrimitive(false)))
                    throw SourceContentException(ContentError.InvalidRule, "$location.showInCategories")
                it == JsonPrimitive(true)
            } ?: true
            // Input IDs are form/infoMap keys; only URL/button rows may use explicit IDs or digests.
            // viewName is presentation-only, and host result routes carry drafts under these same keys.
            val key = if (type in inputTypes) name else row.string("id").ifBlank {
                val identity = "$type:$url:${row.string("action")}"; val occurrence = occurrences.merge(identity, 1, Int::plus)!!
                digest("$field:$identity:$occurrence")
            }
            val unnamedTarget = type == "url" && url.isNotBlank() && row.keys.all { it in separatorKeys }
            if (name.isBlank() && !unnamedTarget || name.length > 256 || key.isBlank() || key.length > 256)
                throw SourceContentException(ContentError.InvalidRule, "$location.title")
            val choices = (row["chars"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            if (type == "toggle" && choices.size != 2 || type == "select" && choices.isEmpty() || choices.size > 64 || choices.distinct().size != choices.size)
                throw SourceContentException(ContentError.InvalidRule, "$location.chars")
            val default = row.string("default").ifEmpty { choices.firstOrNull().orEmpty() }
            if (choices.isNotEmpty() && default !in choices) throw SourceContentException(ContentError.InvalidRule, "$location.default")
            // Host extension: scoped inputs appear only in matching result lists. Compare the
            // declared target before template expansion; no source code or regex runs in the UI.
            val prefixes = row["targetPrefixes"]?.let { value ->
                val array = value as? JsonArray ?: throw SourceContentException(ContentError.InvalidRule, "$location.targetPrefixes")
                if (field != "exploreScreen" || type !in inputTypes || array.isEmpty() || array.size > 16)
                    throw SourceContentException(ContentError.InvalidRule, "$location.targetPrefixes")
                array.map { prefix ->
                    val text = (prefix as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (text.isNullOrBlank() || text.length > 2048)
                        throw SourceContentException(ContentError.InvalidRule, "$location.targetPrefixes")
                    text
                }
            }.orEmpty()
            RuleDiscoveryRow(key, name, type, url, row.string("action"), choices, default, location, row.string("viewName"), prefixes, showInCategories)
        }
    }

    private fun layoutSeparator(row: JsonObject) = row.keys.all { it in separatorKeys } &&
        (row["style"] == null || row["style"] is JsonObject) &&
        listOf("title", "url").all { key ->
            row[key] == null || row[key] == JsonNull ||
                (row[key] as? JsonPrimitive)?.let { it.isString && it.content.isBlank() } == true
        }

    // Catalogue rows are not infoMap entries. Larger real catalogues keep the existing
    // 128-value / 32,768-character form-state limits and 64-choice control limits.
    const val MAX_CATALOG_ROWS = 4096
    private val separatorKeys = setOf("title", "url", "style")
    val inputTypes = setOf("text", "toggle", "select")
}
