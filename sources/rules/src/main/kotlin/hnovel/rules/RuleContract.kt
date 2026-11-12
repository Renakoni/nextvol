package hnovel.rules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Safe intermediate values; no host/client, DOM object, or scripting engine instance crosses the port. */
@Serializable sealed interface RuleValue {
    @Serializable data class Text(val value: String) : RuleValue
    // Empty selections have no child node from which the script facade can recover their kind.
    @Serializable data class Items(val values: List<RuleValue>, val elementKind: InputKind? = null) : RuleValue
    @Serializable data class Node(val content: String, val kind: InputKind, val parentTag: String? = null) : RuleValue
    @Serializable data class Captures(val groups: List<String>) : RuleValue
    @Serializable data object Empty : RuleValue
}

@Serializable enum class InputKind { Auto, Html, Json, Xml }
@Serializable enum class OutputKind { Text, TextList, Url, UrlList, Element, Elements }
@Serializable enum class RuleStage { Parse, Select, Replace, Script, Budget }
@Serializable data class RuleLocation(val field: String, val offset: Int = 0)
@Serializable data class RuleError(val stage: RuleStage, val location: RuleLocation, val code: String)
sealed interface RuleResult {
    data class Success(val value: RuleValue) : RuleResult
    data class Failure(val error: RuleError) : RuleResult
}

/** Owned by one evaluation. Entity writes are snapshots; the host commits them after the rule succeeds. */
class RuleContext(
    val sourceId: String,
    val bookId: String? = null,
    val chapterId: String? = null,
    val baseUrl: String = "",
    sourceVariables: Map<String, String> = emptyMap(),
    bookVariables: Map<String, String> = emptyMap(),
    chapterVariables: Map<String, String> = emptyMap(),
    bookBigVariables: Map<String, String> = emptyMap(),
    chapterBigVariables: Map<String, String> = emptyMap(),
) {
    /** AnalyzeRule content survives script stages; each stage's result remains independent. */
    var content: RuleValue? = null
    var contentBaseUrl: String = baseUrl
    val bookValues = bookVariables.filterValues { it.length < 10000 }.toMutableMap()
    val chapterValues = chapterVariables.filterValues { it.length < 10000 }.toMutableMap()
    val bookBigValues = (bookVariables.filterValues { it.length >= 10000 } + bookBigVariables).toMutableMap()
    val chapterBigValues = (chapterVariables.filterValues { it.length >= 10000 } + chapterBigVariables).toMutableMap()
    val bookWrites = linkedMapOf<String, String?>()
    val chapterWrites = linkedMapOf<String, String?>()
    val bookBigWrites = linkedMapOf<String, String?>()
    val chapterBigWrites = linkedMapOf<String, String?>()
    var bookMetadata: String? = null
    var chapterMetadata: String? = null
    val metadataVariablesInitialized = mutableSetOf<String>()
    var initializeMetadataVariables: (() -> Unit)? = null
    var readSpecialVariable: ((String) -> String?)? = null
    var putMetadataVariable: ((String, String) -> Unit)? = null
    var readSourceVariable: ((String) -> String?)? = null
    var putSourceVariable: ((String, String) -> Unit)? = null
    val hasBook get() = bookId != null || bookMetadata?.let { it != "{}" } == true
    val hasChapter get() = chapterId != null || chapterMetadata?.let { it != "{}" } == true
    private val sourceValues = sourceVariables.toMap()
    private val values = linkedMapOf<String, String>()
    fun get(key: String): String {
        readSpecialVariable?.invoke(key)?.let { return it }
        val metadata = when {
            key == "bookName" && hasBook -> bookMetadata to "name"
            key == "title" && hasChapter -> chapterMetadata to "title"
            else -> null
        }
        if (metadata != null) return metadata.first?.let { Json.parseToJsonElement(it).jsonObject[metadata.second]?.jsonPrimitive?.contentOrNull }.orEmpty()
        initializeVariables()
        return values[key]?.takeIf { it.isNotEmpty() }
        ?: listOf(chapterValues[key] ?: chapterBigValues[key], bookValues[key] ?: bookBigValues[key], sourceValues[key])
            .firstOrNull { !it.isNullOrEmpty() } ?: readSourceVariable?.invoke(key).orEmpty()
    }
    private fun initializeVariables() {
        val initializer = initializeMetadataVariables
        if (initializer != null) initializer() else loadMetadataVariables()
    }
    private fun loadMetadataVariables() {
        for ((name, json, values) in listOf(Triple("book", bookMetadata, bookValues), Triple("chapter", chapterMetadata, chapterValues))) {
            if (metadataVariablesInitialized.add(name)) {
                val initial = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(json!!).jsonObject["variable"]!!.jsonPrimitive.content).jsonObject }.getOrNull()
                initial?.forEach { (key, value) -> if (value is JsonPrimitive && value.isString) values.putIfAbsent(key, value.content) }
            }
        }
    }
    fun put(key: String, value: String): String {
        if (!hasChapter && !hasBook) {
            val writer = putSourceVariable
            if (writer == null) values[key] = value else writer(key, value)
            return value
        }
        initializeVariables()
        putMetadataVariable?.let { it(key, value); return value }
        // Selectors and scripts write the same entity, including the reference's big-value split.
        val chapter = hasChapter
        val smallValues = if (chapter) chapterValues else bookValues
        val bigValues = if (chapter) chapterBigValues else bookBigValues
        val smallWrites = if (chapter) chapterWrites else bookWrites
        val bigWrites = if (chapter) chapterBigWrites else bookBigWrites
        val existed = smallValues.containsKey(key)
        val small = value.length < 10000
        if (small) { smallValues[key] = value; bigValues.remove(key) }
        else { smallValues.remove(key); bigValues[key] = value }
        smallWrites[key] = smallValues[key]; bigWrites[key] = bigValues[key]
        if (small || existed) {
            val before = (if (chapter) chapterMetadata else bookMetadata)?.let { Json.parseToJsonElement(it).jsonObject }.orEmpty()
            val updated = JsonObject(before + ("variable" to JsonPrimitive(JsonObject(smallValues.mapValues { JsonPrimitive(it.value) }).toString()))).toString()
            if (chapter) chapterMetadata = updated else bookMetadata = updated
        }
        return value
    }
    fun writes(): Map<String, String> = values.toMap()
}

data class ScriptRequest(val script: String, val input: RuleValue, val location: RuleLocation)
fun interface RuleScriptPort {
    fun evaluate(request: ScriptRequest, context: RuleContext, budget: RuleBudget): RuleValue
}

data class RuleLimits(val maxRuleChars: Int = 65536, val maxInputChars: Int = 2_000_000,
    val maxOutputChars: Int = 2_000_000, val maxDepth: Int = 64, val maxSteps: Long = 2_000_000,
    val timeoutMillis: Long = 2000) {
    init { require(maxRuleChars > 0 && maxInputChars > 0 && maxOutputChars > 0 && maxDepth > 0 && maxSteps > 0 && timeoutMillis > 0) }
}

class RuleBudget(val limits: RuleLimits = RuleLimits()) {
    private val started = System.nanoTime()
    private var steps = 0L
    fun check(cost: Long = 1) {
        steps += cost
        if (steps > limits.maxSteps || Thread.currentThread().isInterrupted ||
            (System.nanoTime() - started) / 1_000_000 >= limits.timeoutMillis) throw RuleBudgetExceeded()
    }
    fun checkSize(size: Int, max: Int) { check(); if (size > max) throw RuleBudgetExceeded() }
    internal fun checkValue(value: RuleValue, max: Int): Long {
        val pending = ArrayDeque<RuleValue>()
        pending.add(value)
        var size = 0L
        while (pending.isNotEmpty()) {
            check()
            when (val next = pending.removeLast()) {
                is RuleValue.Text -> size += next.value.length
                is RuleValue.Node -> size += next.content.length
                is RuleValue.Captures -> size += next.groups.sumOf { it.length.toLong() + 1 }
                is RuleValue.Items -> { size += next.values.size; pending.addAll(next.values) }
                RuleValue.Empty -> Unit
            }
            if (size > max) throw RuleBudgetExceeded()
        }
        return size
    }
}

/** Script adapters supply stable redacted codes, never exception messages or source text. */
class RuleScriptFailure(val code: String) : RuntimeException(code)

class RuleBudgetExceeded : RuntimeException("Rule execution budget exceeded")
internal class RuleFailure(val error: RuleError) : RuntimeException(error.code)

fun RuleValue.text(): String = when (this) {
    is RuleValue.Text -> value
    is RuleValue.Node -> content
    is RuleValue.Captures -> groups.joinToString("\n")
    is RuleValue.Items -> values.joinToString("\n") { it.text() }
    RuleValue.Empty -> ""
}

fun RuleValue.items(): List<RuleValue> = when (this) {
    is RuleValue.Items -> values
    RuleValue.Empty -> emptyList()
    else -> listOf(this)
}

fun JsonElement.toRuleValue(): RuleValue = when (this) {
    JsonNull -> RuleValue.Empty
    is JsonPrimitive -> RuleValue.Text(content)
    is JsonArray -> RuleValue.Items(map {
        if (it is JsonPrimitive && !it.isString) RuleValue.Node(it.toString(), InputKind.Json) else it.toRuleValue()
    })
    is JsonObject -> RuleValue.Node(toString(), InputKind.Json)
}
