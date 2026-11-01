package hnovel.rhino

import hnovel.rules.*
import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import java.net.URLEncoder

internal class UnsupportedSpeechOption : RuntimeException()

/** MultiTTS's request language is distinct from book-source URL rules. */
internal class MultiTtsRequests(private val cx: Context, private val bridge: HostBridge,
    private val frame: ScriptFrame) {
    private val callerRealm = ScriptRealm.current(cx)
    private val realm = ScriptRealm(cx)
    private val scope = realm.global
    private val limit = cx.getThreadLocal(bridgeLimitKey) as Int

    fun prepare(input: JsonObject): JsonObject = try {
        ScriptRealm.install(cx, realm)
        require(frame.speakText != null)
        for (key in listOf("voiceCode", "params", "speakSpeed", "speakVolume"))
            scope.put(key, scope, JsonScriptData(cx, scope, limit).convert(input.getValue(key)))
        scope.put("speakText", scope, frame.speakText.replace(Regex("[\"']"), " "))
        scope.put("GET", scope, "GET")
        scope.put("POST", scope, "POST")
        val java = realm.objectIn(scope)
        fun method(name: String, call: (Array<out Any>) -> Any?) {
            java.put(name, java, realm.method(scope) { _, _, args -> call(args) })
        }
        for (name in listOf("ajax", "fetch")) method(name) { args ->
            require(args.size in 1..2 && args.all { it is CharSequence })
            val rule = Context.toString(args[0]) + if (args.size == 2) "," + Context.toString(args[1]) else ""
            // Arguments have already been evaluated. Never reinterpret book text as code.
            val request = request(bounded(rule), emptyMap(), "application/json")
            try { bridge.call("speech.ajax", listOf(request)).jsonPrimitive.content }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { throw BridgeRejected(realm.errorIn(scope, "speech request failed"), null) }
        }
        method("encodeURI") { args ->
            require(args.size == 1)
            bounded(URLEncoder.encode(bounded(Context.toString(args[0])), "UTF-8"))
        }
        method("log") { args -> require(args.size in 1..2); Undefined.instance }
        method("sleep") { args ->
            require(args.size == 1 && args[0] is Number)
            val duration = (args[0] as Number).toDouble()
            require(duration.isFinite() && duration >= 0 && duration <= Long.MAX_VALUE.toDouble())
            @Suppress("UNCHECKED_CAST")
            val checkDeadline = cx.getThreadLocal(scriptDeadlineKey) as () -> Unit
            val started = System.nanoTime()
            while (true) {
                checkDeadline()
                val remaining = duration.toLong() - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) break
                try { Thread.sleep(minOf(remaining, 10)) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); checkDeadline() }
            }
            Undefined.instance
        }
        scope.put("java", scope, java)
        val rule = input.getValue("url").jsonPrimitive.content.trim()
        val headers = input.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content }
        request(analyze(bounded(rule)), headers, "application/x-www-form-urlencoded")
    } finally { ScriptRealm.install(cx, callerRealm) }

    private fun bounded(text: String): String = text.also { if (it.length > limit) throw ResultTooLarge() }

    private fun evaluate(code: String, emptyNull: Boolean): String {
        val value = evaluateGlobal(cx, scope, code, "multitts-request")
        if (value == null) return if (emptyNull) "" else "null"
        if (!Undefined.isUndefined(value)) BoundedJsonResult(limit).encode(value)
        return bounded(Context.toString(value))
    }

    private fun analyze(rule: String): String {
        val blocks = Regex("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", RegexOption.IGNORE_CASE).findAll(rule)
        var last = -1
        var result = ""
        for (block in blocks) {
            result = evaluate(block.groups[1]?.value ?: block.groupValues[2], emptyNull = false)
            last = block.range.last + 1
        }
        if (last < 0) return expressions(rule)
        // The APK consumes only the final literal tail, not intermediate @result strings.
        val tail = rule.substring(last).trim()
        return if (tail.isEmpty()) result else bounded(expressions(tail).replace("@result", result))
    }

    private fun expressions(rule: String): String {
        val parser = RuleParser()
        val budget = RuleBudget(RuleLimits(maxRuleChars = limit, maxOutputChars = limit))
        val output = StringBuilder()
        var offset = 0
        while (offset < rule.length) {
            val start = rule.indexOf("{{", offset).let { if (it < 0) rule.length else it }
            output.append(rule, offset, start)
            if (start == rule.length) break
            val end = parser.balancedEnd(rule, start, RuleLocation("speech.url"), budget)
            require(rule.substring(end - 2, end) == "}}")
            output.append(evaluate(rule.substring(start + 2, end - 2), emptyNull = true))
            if (output.length > limit) throw ResultTooLarge()
            offset = end
        }
        return bounded(output.toString())
    }

    private fun request(rule: String, defaults: Map<String, String>, bodyType: String): JsonObject {
        if (rule.startsWith("base64://")) return buildJsonObject { put("url", rule) }
        val split = Regex("\\s*,\\s*(?=\\{)").find(rule)
        val url = rule.substring(0, split?.range?.first ?: rule.length).trim()
        val options = split?.let { RequestOptionsJson.parse(rule.substring(it.range.last + 1), maxChars = limit).jsonObject }
            ?: JsonObject(emptyMap())
        // These declared fields have no implementation in the original HTTP transport.
        for (key in listOf("webView", "webJs", "js", "origin", "serverID", "retry", "webViewDelayTime")) {
            val value = options[key]
            if (value != null && value != JsonNull && value !in listOf(JsonPrimitive(false), JsonPrimitive(0), JsonPrimitive(""), JsonPrimitive("false"), JsonPrimitive("0")))
                throw UnsupportedSpeechOption()
        }
        val method = options["method"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: "GET"
        if (method !in setOf("GET", "POST")) throw UnsupportedSpeechOption()
        val headers = defaults.toMutableMap()
        options["headers"]?.takeUnless { it == JsonNull }?.let { value ->
            val map = if (value is JsonPrimitive && value.isString) RequestOptionsJson.parse(value.content, maxChars = limit).jsonObject else value.jsonObject
            map.forEach { (key, value) ->
                headers.keys.removeAll { it.equals(key, true) }
                headers[key] = if (value is JsonPrimitive) value.content else value.toString()
            }
        }
        if (method == "POST" && headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = bodyType
        val timeout = options["timeout"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.long ?: 5000L
        require(timeout > 0)
        val seconds = (timeout / 1000).coerceAtMost(60)
        // The reference rounds down to seconds; a subsecond timeout expires immediately.
        if (seconds == 0L) throw ScriptBudgetExceeded()
        return buildJsonObject {
            put("url", url); put("method", method)
            put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
            options["body"]?.takeUnless { it == JsonNull }?.let {
                put("body", if (it is JsonPrimitive && it.isString) it.content else it.toString())
            }
            put("timeoutMillis", seconds * 1000)
        }.also { bounded(it.toString()) }
    }
}
