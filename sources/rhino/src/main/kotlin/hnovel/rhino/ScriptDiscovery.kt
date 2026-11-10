package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*

/** Invocation data and deferred UI intents. No action here owns an Android object or host authority. */
class ScriptDiscovery(initial: JsonObject) {
    var snapshot: JsonObject = initial
        private set

    internal fun install(cx: Context, scope: Scriptable, java: ScriptableObject, source: ScriptableObject) {
        cx.putThreadLocal(ownerKey, this)
        val limit = cx.getThreadLocal(bridgeLimitKey) as Int
        val data = JsonScriptData(cx, scope, limit)
        val info = data.convert(snapshot["values"] ?: JsonObject(emptyMap())) as ScriptableObject
        val actions = (snapshot["actions"] as? JsonArray).orEmpty().toMutableList()
        var save = snapshot["saveSeconds"] ?: JsonNull
        val interactive = snapshot["interactive"]?.jsonPrimitive?.boolean == true
        fun owner() { check(Context.getCurrentContext().getThreadLocal(ownerKey) === this) }
        fun json(value: Any?) = Json.parseToJsonElement(BoundedJsonResult(limit).encode(value))
        fun values() = json(info).jsonObject.also { value ->
            require(value.size <= 128 && value.values.all { it is JsonPrimitive && it.isString && it.content.length <= 4096 })
        }
        fun replace(value: JsonObject) {
            require(value.size <= 128 && value.values.all { it is JsonPrimitive && it.isString && it.content.length <= 4096 })
            info.ids.forEach { info.delete(it.toString()) }
            value.forEach { (key, item) -> info.put(key, info, data.convert(item)) }
        }
        fun method(target: ScriptableObject, name: String, block: (Array<out Any?>) -> Any?) {
            val guarded = ScriptCalls.method(scope, "invalid discovery action: $name") { _, _, args ->
                owner()
                block(args)
            }
            // The reference overload accepts the current BookSource, not a serialized host object.
            val function = if (name == "searchBook") ScriptRealm.current(cx).method(scope) { activeCx, active, args ->
                guarded.call(activeCx, active, null, if (args.size == 2 && args[1] === source) arrayOf(args[0]) else args)
            } else guarded
            target.defineProperty(name, function, ScriptableObject.DONTENUM or ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        }
        fun emit(kind: String, args: Array<out Any?>) {
            require(interactive) { "Discovery interaction required" }
            require(actions.size < 16)
            actions += buildJsonObject {
                put("kind", kind)
                put("args", json(ScriptRealm.current(cx).arrayIn(scope, args)))
            }
        }
        method(info, "get") { args ->
            require(args.size <= 1)
            if (args.isEmpty()) info else values()[Context.toString(args[0])]?.jsonPrimitive?.content
        }
        method(info, "put") { args ->
            require(args.size == 2)
            val key = Context.toString(args[0])
            val previous = values()[key]?.jsonPrimitive?.content
            replace(JsonObject(values() + (key to JsonPrimitive(Context.toString(args[1])))))
            previous
        }
        method(info, "set") { args -> require(args.size == 1); replace(json(args[0]).jsonObject); null }
        method(info, "save") { args ->
            // Catalogue/viewName scripts may save drafts too; the host commits only after validation.
            // Unlike UI intents and upLoginData, this deliberately does not require an interaction.
            require(args.size <= 2)
            val seconds = args.firstOrNull()?.let { Context.toNumber(it).also { n -> require(n.isFinite() && n % 1 == 0.0) }.toLong() } ?: 0
            require(seconds in 0..Int.MAX_VALUE.toLong())
            save = if (args.getOrNull(1)?.let(Context::toBoolean) != false) JsonPrimitive(seconds) else JsonNull
            null
        }
        scope.put("infoMap", scope, info)
        scope.put("event", scope, data.convert(snapshot["event"] ?: JsonNull))
        scope.put("isLongClick", scope, snapshot["longClick"]?.jsonPrimitive?.boolean ?: false)
        // Discovery callbacks have no implicit current book or chapter.
        if (snapshot["noBook"]?.jsonPrimitive?.boolean == true) {
            scope.put("book", scope, null)
            scope.put("chapter", scope, null)
        }
        for (target in listOf(java, source)) method(target, "refreshExplore") { args ->
            require(args.isEmpty()); emit("refresh", args); null
        }
        method(java, "reLoginView") { args ->
            require(args.size <= 1 && (args.isEmpty() || args[0] is Boolean)); emit("refresh", emptyArray()); null
        }
        method(java, "upLoginData") { args ->
            require(args.size == 1 && interactive)
            if (args[0] != null && !Undefined.isUndefined(args[0])) replace(JsonObject(values() + json(args[0]).jsonObject))
            null
        }
        for (name in listOf("upConfig", "open", "searchBook", "showBrowser")) method(java, name) { args ->
            emit(name, args); null
        }
        for ((name, key) in listOf("ReadBookConfig" to "reading", "ThemeConfig" to "theme")) {
            val value = (snapshot[key] as? JsonObject) ?: JsonObject(emptyMap())
            method(java, "get$name") { args -> require(args.isEmpty()); value.toString() }
            method(java, "get${name}Map") { args ->
                require(args.isEmpty())
                ScriptData.map(cx, scope, value.mapValues { (_, v) ->
                    val p = v.jsonPrimitive
                    when { p.isString -> p.content; p.booleanOrNull != null -> p.boolean; p.doubleOrNull != null -> p.double; else -> null }
                }.toMutableMap())
            }
        }
        method(java, "getThemeMode") { args -> require(args.isEmpty()); snapshot["themeMode"]?.jsonPrimitive?.content ?: "0" }
        capture = {
            owner()
            snapshot = JsonObject(snapshot + mapOf("values" to values(), "saveSeconds" to save, "actions" to JsonArray(actions)))
            JsonScriptData(cx, scope, limit).convert(snapshot)
        }
    }

    private var capture: (() -> Unit)? = null
    internal fun capture() { capture?.invoke(); capture = null }

    companion object { private val ownerKey = Any() }
}
