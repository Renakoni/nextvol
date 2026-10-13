package hnovel.content

import hnovel.rules.RuleValue
import hnovel.content.RuleDiscoveryCatalogParser.MAX_CATALOG_ROWS
import hnovel.content.RuleDiscoveryCatalogParser.inputTypes
import kotlinx.serialization.json.*

data class RuleDiscoveryEnvironment(val themeMode: String = "0", val theme: JsonObject = JsonObject(emptyMap()),
    val reading: JsonObject = JsonObject(emptyMap()))
data class RuleDiscoveryRow(val id: String, val title: String, val type: String, val url: String = "",
    val action: String = "", val choices: List<String> = emptyList(), val default: String = "", val field: String = "exploreUrl",
    val viewName: String = "", val targetPrefixes: List<String> = emptyList(), val showInCategories: Boolean = true)
data class RuleDiscoveryCatalog(val rows: List<RuleDiscoveryRow>, val values: Map<String, String>,
    val homepage: List<RuleDiscoveryRow>? = null)
data class RuleDiscoveryAction(val kind: String, val value: String = "", val title: String = "",
    val html: String? = null, val script: String = "")
data class RuleDiscoveryUpdate(val catalog: RuleDiscoveryCatalog, val actions: List<RuleDiscoveryAction>, val refresh: Boolean)

/** A page-local draft. Explicit infoMap saves are source settings; no feed or cursor is persisted. */
class RuleDiscoverySession internal constructor(private val source: RuleSource, val id: String,
    initialValues: Map<String, String>, private val environment: RuleDiscoveryEnvironment) {
    private var values = initialValues.toMap()
    private var initialized = false
    private var saveSeconds: Long? = null
    private var current: RuleDiscoveryCatalog? = null
    private var homepageOnly = false
    init { require(id.isNotBlank() && id.length <= 128); validateValues(values) }

    suspend fun catalog(refresh: Boolean = false, homepage: Boolean = false): RuleDiscoveryCatalog = source.operation("exploreUrl") {
        if (!source.canDiscover) throw SourceContentException(ContentError.MissingCapability, "exploreUrl")
        if (refresh) source.clearDiscoveryPreviews()
        if (!refresh && homepageOnly == homepage) current?.let { return@operation it }
        val modules = RuleDiscoveryCatalogParser.homepageModules(source.spec.homepageModules)
        val context = context()
        // Direct homepage URLs need no category lookup. kindTitle still resolves against exploreUrl.
        val needsCategories = !homepage || modules == null || modules.any { it.jsonObject.string("url").isBlank() }
        val rows = (if (needsCategories) rows(context, source.spec.exploreUrl, "exploreUrl") else emptyList()) +
            source.spec.exploreScreen.takeIf(String::isNotBlank)?.let { rows(context, it, "exploreScreen") }.orEmpty()
        if (rows.size > MAX_CATALOG_ROWS) throw SourceContentException(ContentError.Limit, "exploreUrl")
        if (rows.map { it.id }.distinct().size != rows.size)
            throw SourceContentException(ContentError.InvalidRule, "exploreUrl.id")
        val draft = readValues(context).toMutableMap()
        rows.filter { it.type in inputTypes }.forEach { row ->
            val value = draft[row.id] ?: row.default
            draft[row.id] = if (row.choices.isNotEmpty() && value !in row.choices) row.default else value
        }
        // A new definition may remove controls. Their old drafts cannot become URL/filter inputs.
        val inputs = rows.filter { it.type in inputTypes }.map { it.id }.toSet()
        val filtered = draft.filterKeys { it in inputs || it !in initialInputKeys }
        context.discovery = JsonObject(context.discovery!! + ("values" to jsonValues(filtered)))
        val rendered = rows.map { row ->
            if (row.viewName.isBlank()) row else {
                val title = script(context, row.viewName, "${row.field}.viewName").jsonPrimitive.content
                if (title.length > 256) throw SourceContentException(ContentError.Limit, "${row.field}.viewName")
                row.copy(title = title)
            }
        }
        capture(context)
        initialInputKeys = inputs
        RuleDiscoveryCatalog(rendered, values.toMap(), RuleDiscoveryCatalogParser.homepage(modules, rendered)).also { current = it; homepageOnly = homepage }
    }

    private var initialInputKeys = emptySet<String>()

    suspend fun interact(rowId: String, value: String? = null, longClick: Boolean = false): RuleDiscoveryUpdate {
        val catalog = catalog(homepage = homepageOnly)
        return source.operation("discovery.action") {
            val row = catalog.rows.singleOrNull { it.id == rowId }
                ?: throw SourceContentException(ContentError.InvalidRule, "discovery.action.id")
            val next = values.toMutableMap()
            if (value != null) {
                if (row.type !in inputTypes || value.length > 4096 || row.choices.isNotEmpty() && value !in row.choices)
                    throw SourceContentException(ContentError.InvalidRule, "${row.field}.value")
                next[row.id] = value
            } else if (row.type != "button") throw SourceContentException(ContentError.InvalidRule, "${row.field}.value")
            val context = context(interactive = true, draft = next, longClick = longClick)
            if (row.action.isNotBlank()) {
                if (row.action.startsWith("https://", true) || row.action.startsWith("http://", true)) {
                    context.discovery = JsonObject(context.discovery!! + ("actions" to buildJsonArray {
                        add(buildJsonObject { put("kind", "showBrowser"); put("args", buildJsonArray { add(row.action) }) })
                    }))
                } else script(context, row.action, "${row.field}.action")
            }
            val actions = actions(context)
            val refresh = actions.any { it.kind == "refresh" || it.kind == "configuration" }
            capture(context)
            val updated = catalog.copy(values = values.toMap())
            current = if (refresh) null else updated
            RuleDiscoveryUpdate(updated, actions.filterNot { it.kind == "refresh" }, refresh)
        }
    }

    suspend fun page(url: String, page: Int, filters: Map<String, String>): List<RuleBook> = source.operation("ruleExplore") {
        if (!source.canDiscover || source.spec.explore.isEmpty()) throw SourceContentException(ContentError.MissingCapability, "ruleExplore")
        if (page !in 1..64) throw SourceContentException(ContentError.Limit, "ruleExplore.page")
        validateValues(filters)
        source.discoveryPage(context(page = page, draft = values + filters, noBook = false), url)
    }

    fun openPages(url: String, filters: Map<String, String>): RuleListSession = pages(url, filters)

    suspend fun preview(url: String, filters: Map<String, String>): RuleListPage = pages(url, filters, 6).page(1)

    suspend fun concurrentPreviews(urls: List<String>, filters: Map<String, String>): List<RuleListSession>? {
        if (!source.canReadPreviewsConcurrently(urls)) return null
        val draft = values + filters
        validateValues(draft)
        val prepared = source.prepareConcurrentPreviews(urls) { context(draft = draft, noBook = false) } ?: return null
        return prepared.map { load -> source.listSession(source.spec.explore, "ruleExplore") { page, _, _ ->
            if (page != 1) throw SourceContentException(ContentError.InvalidRule, "ruleExplore.page")
            load()
        } }
    }

    private fun pages(url: String, filters: Map<String, String>, previewLimit: Int? = null): RuleListSession {
        val draft = values + filters
        return source.listSession(source.spec.explore, "ruleExplore") { page, next, memory ->
            source.operation("ruleExplore") {
                if (!source.canDiscover || source.spec.explore.isEmpty())
                    throw SourceContentException(ContentError.MissingCapability, "ruleExplore")
                validateValues(draft)
                source.listPage(context(page = page, draft = draft, noBook = false, memory = memory),
                    next ?: url, "exploreUrl", source.spec.explore, "ruleExplore", overview = true, previewLimit = previewLimit)
            }
        }
    }

    private fun context(page: Int = 1, interactive: Boolean = false, draft: Map<String, String> = values,
        longClick: Boolean = false, noBook: Boolean = true,
        memory: hnovel.execution.ScriptMemory = hnovel.execution.ScriptMemory()): RuleEvaluation {
        if (!initialized) {
            source.discoveryState("info")?.let { saved ->
                val stored = Json.parseToJsonElement(saved).jsonObject
                val expires = stored["expires"]?.jsonPrimitive?.long ?: 0
                if (expires == 0L || expires > System.currentTimeMillis()) values = stringMap(stored.getValue("values")) + values
            }
            initialized = true
        }
        return source.evaluation(page = page, interactive = interactive, memory = memory).also { context ->
            context.discovery = buildJsonObject {
                put("sessionId", id); put("values", jsonValues(values + draft)); put("interactive", interactive)
                put("event", JsonNull); put("longClick", longClick); put("noBook", noBook)
                // Source callbacks omit rule input; login uses this envelope with an explicit result.
                put("noResult", noBook)
                put("saveSeconds", saveSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("themeMode", environment.themeMode); put("theme", environment.theme); put("reading", environment.reading)
            }
        }
    }

    private suspend fun script(context: RuleEvaluation, code: String, field: String): JsonElement {
        val result = context.script("JSON.stringify(eval(${JsonPrimitive(scriptBody(code))}))", RuleValue.Empty, field).text()
        return if (result.isBlank()) JsonNull else Json.parseToJsonElement(result)
    }

    private suspend fun rows(context: RuleEvaluation, rule: String, field: String): List<RuleDiscoveryRow> {
        val value = if (rule.trimStart().startsWith("@js:", true) || rule.trimStart().startsWith("<js>", true)) script(context, rule, field)
            else JsonPrimitive(rule)
        return RuleDiscoveryCatalogParser.rows(value, field)
    }

    private fun actions(context: RuleEvaluation): List<RuleDiscoveryAction> =
        ((context.discovery?.get("actions") as? JsonArray).orEmpty()).also {
            if (it.size > 16) throw SourceContentException(ContentError.Limit, "discovery.actions")
        }.mapIndexed { index, value ->
            val row = value.jsonObject; val kind = row.getValue("kind").jsonPrimitive.content
            val args = row.getValue("args").jsonArray
            fun text(i: Int) = args.getOrNull(i)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty()
            fun invalid(): Nothing = throw SourceContentException(ContentError.InvalidRule, "discovery.actions[$index].$kind")
            when (kind) {
                "refresh" -> { if (args.isNotEmpty()) invalid(); RuleDiscoveryAction("refresh") }
                "upConfig" -> {
                    if (args.isEmpty()) RuleDiscoveryAction("settings")
                    else {
                        if (args.size != 1) invalid()
                        val settings = stringMap(args[0]); validateValues(settings)
                        // Settings belong to this source. This protocol never mutates global reader preferences.
                        RuleDiscoveryAction("configuration", jsonValues(settings).toString())
                    }
                }
                "searchBook" -> {
                    if (args.size !in 1..2 || text(0).length > 1024 || args.size == 2 && text(1).isNotBlank() && text(1) != source.definition.importKey) invalid()
                    RuleDiscoveryAction("search", text(0))
                }
                "open" -> {
                    if (args.size !in 1..4 || text(3).isNotBlank() && text(3) != source.definition.importKey) invalid()
                    when (text(0)) {
                        "login" -> RuleDiscoveryAction("login")
                        "explore" -> RuleDiscoveryAction("results", text(1), text(2))
                        "search" -> RuleDiscoveryAction("search", text(2).ifBlank { text(1) })
                        else -> invalid()
                    }
                }
                "showBrowser" -> {
                    if (args.size !in 1..4) invalid()
                    val config = text(3).takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it).jsonObject }
                    if (config != null && (config.keys - setOf("title")).isNotEmpty()) invalid()
                    RuleDiscoveryAction("browser", sourceLink(source.spec.baseUrl, text(0)), config?.string("title").orEmpty(), text(1).takeIf(String::isNotBlank), text(2))
                }
                else -> invalid()
            }
        }

    private fun readValues(context: RuleEvaluation) = stringMap(context.discovery!!.getValue("values"))
    private fun capture(context: RuleEvaluation, draft: Map<String, String> = readValues(context)) {
        validateValues(draft)
        val saving = context.discovery?.get("saveSeconds")?.takeUnless { it == JsonNull }?.jsonPrimitive?.long
        if (saving != null) {
            if (saving !in 0..Int.MAX_VALUE.toLong()) throw SourceContentException(ContentError.InvalidRule, "infoMap.save")
            source.saveDiscoveryState("info", buildJsonObject {
                put("values", jsonValues(draft)); put("expires", if (saving == 0L) 0 else System.currentTimeMillis() + saving * 1000)
            }.toString())
        }
        actions(context).filter { it.kind == "configuration" }.forEach { source.saveDiscoveryState("configuration", it.value) }
        values = draft.toMap(); saveSeconds = saving
    }

    private fun stringMap(value: JsonElement): Map<String, String> = value.jsonObject.mapValues {
        val text = it.value as? JsonPrimitive ?: throw SourceContentException(ContentError.InvalidRule, "infoMap")
        if (!text.isString) throw SourceContentException(ContentError.InvalidRule, "infoMap")
        text.content
    }.also(::validateValues)
    private fun validateValues(value: Map<String, String>) {
        if (value.size > 128 || value.any { it.key.length > 256 || it.value.length > 4096 } || value.entries.sumOf { it.key.length + it.value.length } > 32768)
            throw SourceContentException(ContentError.Limit, "infoMap")
    }
    private fun jsonValues(value: Map<String, String>) = JsonObject(value.mapValues { JsonPrimitive(it.value) })
}
