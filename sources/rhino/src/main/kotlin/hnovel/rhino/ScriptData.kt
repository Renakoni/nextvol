package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import java.nio.charset.Charset

internal class ScriptMapValue(val map: MutableMap<String, Any?>, private val ownerCheck: (() -> Unit)? = null) : NativeObject() {
    override fun get(name: String, start: Scriptable): Any? {
        val own = super.get(name, start)
        return if (own != NOT_FOUND || !map.containsKey(name)) own
            else ScriptDom.wrap(Context.getCurrentContext(), parentScope, map[name], ownerCheck)
    }
    override fun has(name: String, start: Scriptable) = super.has(name, start) || map.containsKey(name)
}

/** Native map/list data views shared by response headers, DOM attributes and entity variables. */
internal object ScriptData {
    fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonArray(value.map(::json))
        is JsonElement -> value
        else -> ScriptDom.snapshot(value)
    }
    private fun raw(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonPrimitive -> when { value.isString -> value.content; value.booleanOrNull != null -> value.boolean; else -> value.double }
        is JsonArray -> value.map(::raw).toMutableList()
        is JsonObject -> value.mapValues { raw(it.value) }.toMutableMap()
    }
    fun argument(cx: Context, scope: Scriptable, value: Any?): Any? {
        val json = BoundedJsonResult(cx.getThreadLocal(bridgeLimitKey) as Int).encode(value)
        return if (value is ScriptDomValue) value.value else raw(Json.parseToJsonElement(json))
    }

    @Suppress("UNCHECKED_CAST")
    fun map(cx: Context, scope: Scriptable, source: Map<*, *>, changed: (() -> Unit)? = null,
        validate: ((Map<String, Any?>) -> Unit)? = null, ownerCheck: (() -> Unit)? = null): ScriptableObject {
        val values = source as MutableMap<String, Any?>
        val realm = ScriptRealm.current(cx)
        val result = ScriptMapValue(values, ownerCheck).apply { parentScope = scope; prototype = realm.objectIn(scope).prototype }
        fun guarded(active: Scriptable, message: String, action: (Context, Scriptable, Array<out Any>) -> Any?) =
            ScriptCalls.method(active, message) { c, s, a -> try { action(c, s, a) } finally { ownerCheck?.invoke() } }
        fun wrap(c: Context, s: Scriptable, value: Any?) = ScriptDom.wrap(c, s, value, ownerCheck)
        fun method(name: String, action: (Context, Scriptable, Array<out Any>) -> Any?) {
            result.defineProperty(name, guarded(scope, "invalid map argument", action), ScriptableObject.DONTENUM)
        }
        fun check(next: Map<String, Any?>) { validate?.invoke(next); JsonScriptData(Context.getCurrentContext(), scope, Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).convert(json(next)) }
        method("get") { c, s, a -> require(a.size == 1); wrap(c, s, values[Context.toString(a[0])]) }
        method("getOrDefault") { c, s, a -> require(a.size == 2); wrap(c, s, values.getOrDefault(Context.toString(a[0]), argument(c, s, a[1]))) }
        method("put") { c, s, a ->
            require(a.size == 2); val key = Context.toString(a[0]); val value = argument(c, s, a[1])
            check(values + (key to value)); val old = values.put(key, value); changed?.invoke(); wrap(c, s, old)
        }
        method("putAll") { c, s, a ->
            require(a.size == 1)
            val incoming = argument(c, s, a[0]) as? Map<String, Any?> ?: error("Map required")
            check(values + incoming); values.putAll(incoming); changed?.invoke(); null
        }
        method("remove") { c, s, a -> require(a.size == 1); val old = values.remove(Context.toString(a[0])); changed?.invoke(); wrap(c, s, old) }
        method("clear") { _, _, a -> require(a.isEmpty()); values.clear(); changed?.invoke(); null }
        method("containsKey") { _, _, a -> require(a.size == 1); values.containsKey(Context.toString(a[0])) }
        method("containsValue") { c, s, a -> require(a.size == 1); values.containsValue(argument(c, s, a[0])) }
        method("size") { _, _, a -> require(a.isEmpty()); values.size }
        method("isEmpty") { _, _, a -> require(a.isEmpty()); values.isEmpty() }
        method("keySet") { c, s, a -> require(a.isEmpty()); list(c, s, values.keys.toMutableList(), ownerCheck) }
        method("values") { c, s, a -> require(a.isEmpty()); list(c, s, values.values.toMutableList(), ownerCheck) }
        method("entrySet") { c, s, a -> require(a.isEmpty()); ScriptRealm.current(c).arrayIn(s, values.keys.map { key ->
            ScriptRealm.current(c).objectIn(s).apply {
                defineProperty("getKey", guarded(s, "invalid entry argument") { _, _, args -> require(args.isEmpty()); key }, ScriptableObject.DONTENUM)
                defineProperty("getValue", guarded(s, "invalid entry argument") { context, active, args -> require(args.isEmpty()); wrap(context, active, values[key]) }, ScriptableObject.DONTENUM)
                defineProperty("setValue", guarded(s, "invalid entry argument") { context, active, args ->
                    require(args.size == 1); val value = argument(context, active, args[0]); check(values + (key to value))
                    val old = values.put(key, value); changed?.invoke(); wrap(context, active, old)
                }, ScriptableObject.DONTENUM)
            }
        }.toTypedArray()) }
        method("toString") { _, _, a -> require(a.isEmpty()); values.toString() }
        // Map methods mutate the backing data; field reads are live conveniences.
        return result
    }

    @Suppress("UNCHECKED_CAST")
    fun list(cx: Context, scope: Scriptable, source: MutableList<*>, ownerCheck: (() -> Unit)? = null): NativeArray {
        val values = source as MutableList<Any?>
        fun wrap(c: Context, s: Scriptable, value: Any?) = ScriptDom.wrap(c, s, value, ownerCheck)
        val result = ScriptRealm.current(cx).arrayIn(scope, values.map { wrap(cx, scope, it) }.toTypedArray())
        fun method(name: String, changes: Boolean = false, action: (Context, Scriptable, Array<out Any>) -> Any?) {
            result.defineProperty(name, ScriptCalls.method(scope, "invalid list argument") { c, s, a ->
                val answer = try { action(c, s, a) } finally { if (changes) ownerCheck?.invoke() }
                if (changes) {
                    for (i in values.size until result.length.toInt()) result.delete(i)
                    result.put("length", result, values.size)
                    values.forEachIndexed { index, value -> result.put(index, result, wrap(c, s, value)) }
                }
                answer
            }, ScriptableObject.DONTENUM)
        }
        method("get") { c, s, a -> require(a.size == 1); wrap(c, s, values[Context.toNumber(a[0]).toInt()]) }
        method("size") { _, _, a -> require(a.isEmpty()); values.size }
        method("isEmpty") { _, _, a -> require(a.isEmpty()); values.isEmpty() }
        method("contains") { c, s, a -> require(a.size == 1); values.contains(argument(c, s, a[0])) }
        fun check(next: List<Any?>) { JsonScriptData(Context.getCurrentContext(), scope, Context.getCurrentContext().getThreadLocal(bridgeLimitKey) as Int).convert(json(next)) }
        method("add", true) { c, s, a -> require(a.size == 1); val value = argument(c, s, a[0]); check(values + value); values.add(value) }
        method("set", true) { c, s, a -> require(a.size == 2); val index = Context.toNumber(a[0]).toInt(); val value = argument(c, s, a[1]); check(values.toMutableList().apply { set(index, value) }); wrap(c, s, values.set(index, value)) }
        method("remove", true) { c, s, a -> require(a.size == 1); if (a[0] is Number) wrap(c, s, values.removeAt(Context.toNumber(a[0]).toInt())) else values.remove(argument(c, s, a[0])) }
        method("clear", true) { _, _, a -> require(a.isEmpty()); values.clear(); null }
        return result
    }

    fun charset(cx: Context, scope: Scriptable, value: Charset): ScriptableObject = ScriptDomValue(value).apply {
        parentScope = scope; prototype = ScriptRealm.current(cx).objectIn(scope).prototype
        for (name in listOf("name", "displayName", "toString")) defineProperty(name, ScriptCalls.method(scope, "invalid charset argument") { _, _, a -> require(a.isEmpty()); value.name() }, ScriptableObject.DONTENUM)
        defineProperty("aliases", ScriptCalls.method(scope, "invalid charset argument") { c, s, a -> require(a.isEmpty()); list(c, s, value.aliases().toMutableList()) }, ScriptableObject.DONTENUM)
        defineProperty("canEncode", ScriptCalls.method(scope, "invalid charset argument") { _, _, a -> require(a.isEmpty()); value.canEncode() }, ScriptableObject.DONTENUM)
    }

    fun enum(cx: Context, scope: Scriptable, value: Enum<*>): ScriptableObject = ScriptDomValue(value).apply {
        parentScope = scope; prototype = ScriptRealm.current(cx).objectIn(scope).prototype
        for (name in listOf("name", "toString", "ordinal")) defineProperty(name, ScriptCalls.method(scope, "invalid enum argument") { _, _, a ->
            require(a.isEmpty()); if (name == "ordinal") value.ordinal else if (name == "toString") value.toString() else value.name
        }, ScriptableObject.DONTENUM)
    }

    fun url(cx: Context, scope: Scriptable, value: java.net.URL): ScriptableObject = ScriptDomValue(value).apply {
        parentScope = scope; prototype = ScriptRealm.current(cx).objectIn(scope).prototype
        for (name in listOf("toString", "toExternalForm", "getProtocol", "getHost", "getPort", "getDefaultPort", "getPath", "getQuery", "getRef", "getAuthority", "getUserInfo", "getFile")) {
            defineProperty(name, ScriptCalls.method(scope, "invalid URL argument") { _, _, a ->
                require(a.isEmpty())
                when (name) {
                    "getProtocol" -> value.protocol; "getHost" -> value.host; "getPort" -> value.port; "getDefaultPort" -> value.defaultPort
                    "getPath" -> value.path; "getQuery" -> value.query; "getRef" -> value.ref; "getAuthority" -> value.authority
                    "getUserInfo" -> value.userInfo; "getFile" -> value.file; else -> value.toExternalForm()
                }
            }, ScriptableObject.DONTENUM)
        }
    }
}
