package hnovel.speech

import kotlinx.serialization.json.*

/** Q6.a's Azure/Clipchamp catalog, including the controls referenced by speaker.param. */
internal fun microsoftVoiceCatalog(response: JsonArray): List<JsonObject> = response.map { element ->
    val item = element.jsonObject
    val parameters = mutableListOf(item.getValue("ShortName").jsonPrimitive.content)
    val controls = mutableListOf<JsonObject>()
    fun control(code: String, name: String, values: List<String>, default: String = "Default") {
        controls += buildJsonObject {
            put("code", code); put("name", name); put("value", default)
            putJsonObject("candidate") {
                put(default, default)
                values.forEach { put(it, it) }
            }
        }
        parameters += "@$code"
    }
    val roles = item["RolePlayList"] as? JsonArray
    if (roles == null) parameters += "Default"
    else control("role", "Role", roles.map { it.jsonPrimitive.content })
    val styles = item["StyleList"] as? JsonArray
    if (styles == null) parameters += listOf("Default", "1.0")
    else {
        control("style", "Style", styles.map { it.jsonPrimitive.content })
        control("styleDegree", "Style degree", listOf("0.01", "0.2", "0.4", "0.6", "0.8", "1.0", "1.2", "1.4", "1.6", "1.8", "2.0"), "1.0")
    }
    (item["SecondaryLocaleList"] as? JsonArray)?.let { languages ->
        control("langSkill", "Language", languages.map { it.jsonPrimitive.content }, "")
    }
    val name = item.string("Name")
    val locale = item.getValue("Locale").jsonPrimitive.content.replace(Regex("-[a-zA-Z]{3,}(?=-)"), "")
    buildJsonObject {
        put("code", parameters.first()); put("name", item.string("LocalName").ifEmpty { parameters.first() })
        put("locale", locale); put("gender", if (item.string("Gender") == "Female") 0 else 1)
        put("desc", name.substringAfter('(', name).removeSuffix(")").replace("Neural", ""))
        put("param", parameters.joinToString(",")); put("type", 1)
        put("speed", 1f); put("volume", 2f); put("sampleRate", 24000)
        if (controls.isNotEmpty()) put("extendUI", JsonArray(controls).toString())
    }
}
