package hnovel.speech

import kotlinx.serialization.json.*

/** MultiTTS engine.param.webView maps browser storage names to engine parameter names. */
data class SpeechEngineLogin(val loginUrl: String, val userAgent: String?, val mapping: Map<String, String>)

fun speechEngineLogin(parameters: String): SpeechEngineLogin? = try {
    val view = microsoftPackageParameters(parameters)["webView"] as? JsonObject
    val url = view?.string("loginUrl").orEmpty()
    val mapping = (view?.get("mapping") as? JsonObject).orEmpty()
    if (url.isEmpty()) null else SpeechEngineLogin(url,
        view?.string("userAgent")?.takeIf(String::isNotEmpty),
        mapping.mapValues { it.value.jsonPrimitive.content })
} catch (_: IllegalArgumentException) { null }

/** Captured values are supplied by the user's browser session; import/preview never reads storage. */
fun applySpeechEngineLogin(parameters: String, cookies: String?, localStorage: Map<String, String>): String {
    val login = speechEngineLogin(parameters) ?: return parameters
    val updated = microsoftPackageParameters(parameters).toMutableMap()
    if (!cookies.isNullOrEmpty()) {
        val values = cookies.split(';').associate { cookie ->
            val entry = cookie.trim()
            entry.substringBefore('=') to entry.substringAfter('=', "")
        }
        for ((source, destination) in login.mapping) {
            when {
                source == "cookies" -> updated[destination] = JsonPrimitive(cookies)
                source.startsWith("cookies.") -> values[source.removePrefix("cookies.")]?.let {
                    updated[destination] = JsonPrimitive(it)
                }
            }
        }
    }
    for ((source, destination) in login.mapping) {
        // LoginActivity serializes null storage values; F6.j.B reads them with JSONObject.getString.
        if (source.startsWith("localStorage."))
            updated[destination] = JsonPrimitive(localStorage[source.removePrefix("localStorage.")] ?: "null")
    }
    return JsonObject(updated).toString()
}
