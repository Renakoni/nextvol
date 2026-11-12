package hnovel.rhino

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.apache.commons.text.StringEscapeUtils

/** Nested AnalyzeRule helpers share the current Rhino context, variables and rule budget. */
internal class ScriptRuleHelpers(private val scope: Scriptable, frame: ScriptFrame, private val limits: ScriptLimits) {
    private val context = frame.ruleContext ?: RuleContext(frame.sourceId, frame.bookId, frame.chapterId, frame.baseUrl)
    init { if (context.content == null) context.content = frame.ruleInput ?: (frame.variables["result"] ?: JsonNull).toRuleValue() }
    private var root: RuleValue
        get() = context.content!!
        set(value) { context.content = value }
    private var baseUrl: String
        get() = context.contentBaseUrl
        set(value) { context.contentBaseUrl = value }
    private val budget = frame.ruleBudget ?: RuleBudget(RuleLimits(maxInputChars = limits.maxBridgeChars,
        maxOutputChars = limits.maxBridgeChars))
    private var depth = 0
    private var elements: RuleValue? = null

    fun sourceValue(cx: Context): Any? = JsonScriptData(cx, scope, budget.limits.maxInputChars).convert(json(root))

    fun elementView(cx: Context, active: Scriptable, data: JsonElement): Any? {
        return elements?.let { inputView(cx, active, it, limits.maxBridgeChars) }
            ?: JsonScriptData(cx, active, limits.maxBridgeChars).convert(data)
    }

    fun inputView(cx: Context, active: Scriptable, input: RuleValue, maxChars: Int): Any? {
        // Keep the JSON input budget, but retain DOM methods for selected HTML nodes.
        val plain = JsonScriptData(cx, active, maxChars).convert(json(input))
        fun convert(value: RuleValue, data: Any?): Any? = when (value) {
            is RuleValue.Node -> if (value.kind == InputKind.Html || value.kind == InputKind.Xml && value.parentTag != null) ScriptDom.wrap(cx, active, value.htmlElement(""))
                else if (value.kind == InputKind.Xml) ScriptDom.fragment(cx, active, value.content, "", true)
                else data
            is RuleValue.Items -> {
                val array = data as NativeArray
                val nodes = value.values.mapIndexed { index, item -> convert(item, array.get(index, array)) }
                if ((nodes.isNotEmpty() || value.elementKind == InputKind.Html) && nodes.all { it is ScriptDomElement }) ScriptDom.elements(cx, active, nodes.map { (it as ScriptDomElement).element })
                else array.apply { nodes.forEachIndexed { index, item -> put(index, this, item) } }
            }
            else -> data
        }
        return convert(input, plain)
    }

    fun supports(name: String, args: List<JsonElement>) = name in setOf("java.getString", "java.getStringList",
        "java.getElement", "java.getElements", "java.setContent", "java.put", "java.getUrl") || name == "java.get" && args.size == 1

    fun call(cx: Context, name: String, args: List<JsonElement>, elementInput: RuleValue? = null): JsonElement {
        elements = null
        if (++depth > budget.limits.maxDepth) { depth--; throw ScriptBudgetExceeded() }
        try {
            budget.check()
            if (name == "java.getUrl") { require(args.isEmpty()); return JsonPrimitive(context.baseUrl) }
            if (name == "java.setContent") {
                require(args.size in 1..2 && args[0] != JsonNull)
                val nextBase = args.getOrNull(1)?.takeUnless { it == JsonNull }?.let {
                    require(it is JsonPrimitive && it.isString)
                    it.content
                }
                root = elementInput ?: if (args[0] is JsonArray) RuleValue.Node(args[0].toString(), InputKind.Json) else args[0].toRuleValue()
                if (nextBase != null) baseUrl = nextBase
                return JsonNull
            }
            if (name == "java.get") return JsonPrimitive(context.get(args.single().jsonPrimitive.content))
            if (name == "java.put") {
                require(args.size == 2)
                return JsonPrimitive(context.put(args[0].jsonPrimitive.content, args[1].jsonPrimitive.content))
            }
            val strings = name == "java.getString" || name == "java.getStringList"
            require(args.size in 1..if (strings) 3 else 1)
            val rule = args[0].let { if (it == JsonNull && strings) "" else it.jsonPrimitive.content }
            if (rule.isEmpty()) return when (name) {
                "java.getString" -> JsonPrimitive("")
                "java.getElements" -> JsonArray(emptyList())
                else -> JsonNull
            }
            val booleanOverload = name == "java.getString" && args.size == 2 && args[1] is JsonPrimitive && args[1].jsonPrimitive.booleanOrNull != null
            val unescape = !booleanOverload || args[1].jsonPrimitive.boolean
            val input = elementInput ?: args.getOrNull(1)?.takeUnless { booleanOverload || it == JsonNull }?.toRuleValue() ?: root
            val url = args.getOrNull(2)?.jsonPrimitive?.boolean ?: false
            val output = when (name) {
                "java.getString" -> if (url) OutputKind.Url else OutputKind.Text
                "java.getStringList" -> if (url) OutputKind.UrlList else OutputKind.TextList
                "java.getElement" -> OutputKind.Element
                else -> OutputKind.Elements
            }
            val evaluator = RuleEvaluator(unescapeHtml = false) { request, _, _ ->
                budget.checkSize(request.script.length, limits.maxScriptChars)
                // Nested input must neither create a caller binding nor write through its const result.
                val nested = NativeObject().apply {
                    prototype = scope
                    put("result", this, inputView(cx, this, request.input, budget.limits.maxInputChars))
                    put("baseUrl", this, baseUrl)
                    put("src", this, sourceValue(cx))
                }
                Json.parseToJsonElement(BoundedJsonResult(limits.maxBridgeChars)
                    .encode(evaluateGlobal(cx, nested, request.script, "nested-rule"))).toRuleValue()
            }
            val result = evaluator.evaluate(rule, input, context, output, RuleLocation(name), budget, context.baseUrl, baseUrl)
            if (result is RuleResult.Failure) {
                if (result.error.stage == RuleStage.Budget) throw ScriptBudgetExceeded()
                error("Nested rule failed")
            }
            val selected = (result as RuleResult.Success).value
            if (name == "java.getElement" || name == "java.getElements") {
                elements = selected
            }
            return when (name) {
                "java.getString" -> JsonPrimitive(selected.text().let { if (unescape) StringEscapeUtils.unescapeHtml4(it) else it })
                "java.getStringList" -> JsonArray((if (selected is RuleValue.Text) selected.value.split('\n') else selected.items().map { it.text() }).map(::JsonPrimitive))
                else -> json(selected)
            }
        } catch (_: RuleBudgetExceeded) { throw ScriptBudgetExceeded() }
        finally { depth-- }
    }

    private fun json(value: RuleValue): JsonElement = when (value) {
        RuleValue.Empty -> JsonNull
        is RuleValue.Text -> JsonPrimitive(value.value)
        is RuleValue.Node -> if (value.kind == InputKind.Json) Json.parseToJsonElement(value.content) else JsonPrimitive(value.content)
        is RuleValue.Items -> JsonArray(value.values.map(::json))
        is RuleValue.Captures -> JsonArray(value.groups.map(::JsonPrimitive))
    }
}
