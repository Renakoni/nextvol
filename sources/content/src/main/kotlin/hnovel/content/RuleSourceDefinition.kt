package hnovel.content

import hnovel.imports.SourceDefinition
import hnovel.network.LocalStorageRetention
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Parsed definition data. Nothing here grants network access or executes source expressions. */
internal class RuleSourceDefinition(val stored: SourceDefinition) {
    private val root = Json.parseToJsonElement(stored.rawJson).jsonObject
    val baseUrl = root.string("bookSourceUrl")
    val lastUpdateTime = (root["lastUpdateTime"] as? JsonPrimitive)?.longOrNull ?: 0L
    val library = root.string("jsLib").takeIf { it.isNotBlank() }
    val searchUrl = root.string("searchUrl")
    val exploreUrl = root.string("exploreUrl")
    val exploreScreen = root.string("exploreScreen")
    val homepageModules = root.string("homepageModules")
    val bookUrlPattern = root.string("bookUrlPattern")
    val header = root.string("header")
    val loginCheck = root.string("loginCheckJs")
    val loginUrl = root.string("loginUrl")
    val comment = root["bookSourceComment"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    val loginUi = root.string("loginUi")
    val cookiesEnabled = root["enabledCookieJar"]?.jsonPrimitive?.booleanOrNull ?: true
    val browserRead = root["browserRead"]?.jsonPrimitive?.booleanOrNull ?: false
    val localStorageRetention = LocalStorageRetention.parse(root[LocalStorageRetention.FIELD])
    val concurrentRate = root.string("concurrentRate")
    val coverDecode = root.string("coverDecodeJs")
    val search = rules("ruleSearch")
    // Legado treats an explore rule without a list selector as a partial override
    // and uses the search list rule. Keep that behavior for sources such as Kuwo.
    val explore = rules("ruleExplore").takeIf { rule ->
        rule["bookList"]?.jsonPrimitive?.content?.isNotBlank() == true
    } ?: search
    val information = rules("ruleBookInfo")
    val toc = rules("ruleToc")
    val content = rules("ruleContent")
    private fun rules(name: String): JsonObject = when (val value = root[name]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> value
        is JsonArray -> if (value.isEmpty()) JsonObject(emptyMap()) else throw SourceContentException(ContentError.InvalidRule, name)
        is JsonPrimitive -> Json.parseToJsonElement(value.content).jsonObject
    }
}

internal fun JsonObject.string(name: String) = when (val value = this[name]) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}
internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .toHexString()

/** Keep request options with their logical URL; their scripts are evaluated only before dispatch. */
internal fun sourceLink(base: String, value: String): String {
    return try { hnovel.execution.resolveSourceLink(base, value) }
        catch (_: IllegalArgumentException) { throw SourceContentException(ContentError.InvalidRule, "url") }
}
