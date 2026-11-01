package hnovel.network

import kotlinx.serialization.json.*

/** Data-only worker output. Authority, redirects and resource policy remain host-owned. */
object MultiTtsRequest {
    fun compile(value: JsonObject, id: String): BrokerRequest {
        val url = value.getValue("url").jsonPrimitive.content
        val method = value.getValue("method").jsonPrimitive.content
        require(method == "GET" || method == "POST")
        val timeout = value.getValue("timeoutMillis").jsonPrimitive.long
        return BrokerRequest(id, url, method,
            headers = value.getValue("headers").jsonObject.mapValues {
                require(it.value is JsonPrimitive && it.value.jsonPrimitive.isString)
                it.value.jsonPrimitive.content
            },
            body = if (method == "POST") value["body"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty() else null,
            charset = "UTF-8", responseCharset = "UTF-8", timeoutMillis = timeout, kind = ResourceKind.Api)
    }
}
