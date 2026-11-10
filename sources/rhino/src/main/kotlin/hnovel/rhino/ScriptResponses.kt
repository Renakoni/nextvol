package hnovel.rhino

import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source

/** Native JS response views backed only by bounded data, never OkHttp/Jsoup/Java wrappers. */
internal object ScriptResponses {
    /** Body bytes count once, before Base64 transport expansion; metadata keeps JSON accounting. */
    fun validate(data: JsonElement, maxChars: Int) {
        val batch = data is JsonArray
        val snapshots = if (batch) data.jsonArray.toList() else listOf(data)
        var remaining = maxChars - if (batch) 2 else 0
        if (remaining < 0) throw ResultTooLarge()
        for ((index, item) in snapshots.withIndex()) {
            if (batch && index > 0) remaining--
            val snapshot = item.jsonObject
            val bytes = snapshot["bytes"]?.let {
                require(it.jsonPrimitive.isString)
                val encoded = it.jsonPrimitive.content
                if (encoded.length.toLong() > (remaining.toLong() + 2) / 3 * 4) throw ResultTooLarge()
                java.util.Base64.getDecoder().decode(encoded).size
            } ?: 0
            remaining -= bytes
            if (remaining < 0) throw ResultTooLarge()
            remaining -= BoundedJsonResult(remaining).encodeJson(JsonObject(snapshot - "bytes")).length
        }
    }

    fun create(context: Context, scope: Scriptable, data: JsonObject, jsoup: Boolean, browserCompleted: Boolean = false): ScriptableObject {
        // Legado wraps both completed browser paths with StrResponse(url, body), a synthetic 200 response.
        if (browserCompleted) return create(context, scope, buildJsonObject {
            put("url", data.getValue("url")); put("body", data.getValue("body"))
            put("status", 200); put("message", "OK"); put("headers", JsonObject(emptyMap()))
            put("protocol", "data")
        }, false)
        val realm = ScriptRealm.current(context)
        val response = realm.objectIn(scope)
        val body = data["body"]?.let { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
        val url = data.getValue("url").jsonPrimitive.content
        val status = data.getValue("status").jsonPrimitive.int
        val message = data.getValue("message").jsonPrimitive.content
        val headers = data.getValue("headers").jsonObject
        // Validate every entry while still inside the guarded bridge call, before exposing
        // retained/lazy accessors. Invalid host data must not throw a JVM exception later.
        for (field in listOf("url", "message")) require(data.getValue(field).jsonPrimitive.isString)
        headers.values.forEach { list -> list.jsonArray.forEach { require(it.jsonPrimitive.isString) } }
        fun method(target: ScriptableObject, name: String, action: (Array<out Any>) -> Any?) {
            target.defineProperty(name, realm.method(scope) { cx, active, args ->
                try {
                    val limit = cx.getThreadLocal(bridgeLimitKey) as Int
                    BoundedJsonResult(limit).encode(ScriptRealm.current(cx).arrayIn(active, args))
                    action(args).also { BoundedJsonResult(limit).encode(it) }
                } catch (large: ResultTooLarge) { throw large }
                catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
                catch (_: Exception) { throw JavaScriptException(ScriptRealm.current(cx).errorIn(active, "host bridge denied"), "host-bridge", 1) }
            }, ScriptableObject.DONTENUM)
        }
        fun constant(name: String, value: Any?) = method(response, name) { require(it.isEmpty()); value }
        fun headerSnapshot() = okhttp3.Headers.Builder().apply {
            headers.forEach { (name, values) -> values.jsonArray.forEach { add(name, it.jsonPrimitive.content) } }
        }.build()
        if (data["kind"]?.jsonPrimitive?.content == "BrowserDocument") {
            // Ordinary DOM extraction has no observed HTTP status or raw response.
            val text = body ?: String(java.util.Base64.getDecoder().decode(data.getValue("bytes").jsonPrimitive.content), Charsets.UTF_8)
            constant("body", text); constant("getBody", text)
            constant("url", url); constant("getUrl", url)
            constant("code", 0); constant("statusCode", 0)
            constant("isBrowserDocument", true)
            constant("headers", ScriptDom.wrap(context, scope, headerSnapshot()))
            constant("raw", null); constant("getRaw", null)
            constant("message", ""); constant("isSuccessful", false)
            return response
        }
        if (jsoup) {
            val bytes = data["bytes"]?.let { java.util.Base64.getDecoder().decode(it.jsonPrimitive.content) }
                ?: requireNotNull(body).toByteArray(Charsets.UTF_8)
            val snapshot = ResponseSnapshot(java.net.URL(url), org.jsoup.Connection.Method.valueOf(data["method"]?.jsonPrimitive?.content ?: "GET"),
                status, message, data["charset"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                bytes, headers.mapValues { (_, list) -> list.jsonArray.map { it.jsonPrimitive.content } })
            snapshot.checkSize(context.getThreadLocal(bridgeLimitKey) as Int)
            return ScriptDom.wrap(context, scope, snapshot) as ScriptableObject
        }
        requireNotNull(body)
        val bodySize = data["bodySize"]?.jsonPrimitive?.int ?: data["bytes"]?.let {
            java.util.Base64.getDecoder().decode(it.jsonPrimitive.content).size
        } ?: body.toByteArray(Charsets.UTF_8).size
        require(bodySize >= 0)
        fun values(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.jsonArray
        constant("body", body)
        constant("url", url)
        constant("code", status)
        constant("message", message)
        constant("getBody", body)
        constant("getUrl", url)
        constant("isSuccessful", status in 200..299)
        constant("errorBody", null)
        constant("getErrorBody", null)
        val responseHeaders = headerSnapshot()
        val inline = data["protocol"]?.jsonPrimitive?.content == "data"
        val rawBody = if (inline) null else object : okhttp3.ResponseBody() {
            private val content = object : java.io.FilterInputStream(ByteArray(0).inputStream()) {
                private var closed = false
                override fun close() { closed = true; super.close() }
                override fun read(): Int { if (closed) throw java.io.IOException("closed"); return super.read() }
                override fun read(b: ByteArray, offset: Int, length: Int): Int {
                    if (closed) throw java.io.IOException("closed")
                    return super.read(b, offset, length)
                }
            }.source().buffer()
            override fun contentType() = responseHeaders["Content-Type"]?.toMediaTypeOrNull()
            override fun contentLength() = bodySize.toLong()
            override fun source() = content
        }
        rawBody?.source()?.close() // newCallStrResponse consumes/closes raw.body while producing its text.
        val verb = data["method"]?.jsonPrimitive?.content ?: "GET"
        val request = okhttp3.Request.Builder().url(url).method(verb,
            if (verb in listOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")) ByteArray(0).toRequestBody() else null).build()
        val raw = okhttp3.Response.Builder().request(request)
            .protocol(if (inline) okhttp3.Protocol.HTTP_1_1 else okhttp3.Protocol.get(data["protocol"]?.jsonPrimitive?.content ?: "http/1.1"))
            .code(status).message(message).headers(responseHeaders).apply { if (rawBody != null) body(rawBody) }
            .sentRequestAtMillis(data["sentAt"]?.jsonPrimitive?.long ?: 0)
            .receivedResponseAtMillis(data["receivedAt"]?.jsonPrimitive?.long ?: 0).build()
        val view = ScriptDom.wrap(context, scope, raw)
        constant("raw", view)
        constant("getRaw", view)
        constant("toString", raw.toString())
        constant("headers", ScriptDom.wrap(context, scope, responseHeaders))
        method(response, "header") { args ->
            require(args.size == 1 && args[0] is CharSequence)
            val matching = values(args[0].toString())
            matching?.lastOrNull()?.jsonPrimitive?.content
        }
        return response
    }
}
