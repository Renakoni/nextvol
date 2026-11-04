package hnovel.speech

import kotlinx.serialization.json.*
import java.util.Locale

/** F6.q.m: deduplicate by zbid and retain the service's emotion choices as editable speaker data. */
internal fun peiyinyaVoices(response: JsonObject): List<JsonObject> {
    val voices = mutableListOf<JsonObject>()
    val seen = mutableSetOf<String>()
    val chinese = Locale.getDefault().language == "zh"
    val degree = buildJsonObject {
        put("code", "emotionDegree"); put("name", if (chinese) "情感强度" else "Emotion Degree"); put("value", "50")
        putJsonObject("candidate") { for (value in 0..100 step 10) put(value.toString(), "$value%") }
    }
    for (group in response.getValue("model").jsonObject.getValue("ttsZhuboAllList").jsonArray) {
        for (item in group.jsonObject.getValue("zhuboList").jsonArray) {
            val entry = item.jsonObject
            fun field(key: String) = entry.getValue(key).jsonPrimitive.content
            val id = field("zbid")
            if (!seen.add(id)) continue
            val code = field("speakercode")
            var name = field("speakername")
            var description = field("zbdesp")
            val suffix = name.indexOf('（')
            if (suffix >= 0) {
                description += name.substring(suffix)
                name = name.substring(0, suffix)
            }
            var parameters = id
            var extension = ""
            val emotion = (entry["emotion"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (emotion.isNotEmpty()) {
                val choices = linkedMapOf<String, JsonElement>()
                for (choice in Json.parseToJsonElement(emotion).jsonArray) {
                    val data = choice.jsonObject
                    choices[data.getValue("code").jsonPrimitive.content] = data.getValue("desc")
                }
                extension = buildJsonArray {
                    add(buildJsonObject {
                        put("code", "emotion"); put("name", if (chinese) "情感" else "Emotion")
                        put("value", choices.keys.firstOrNull().orEmpty()); put("candidate", JsonObject(choices))
                    })
                    add(degree)
                }.toString()
                parameters += ",@emotion,@emotionDegree"
            }
            val country = field("country").let { if (it.equals("UK", ignoreCase = true)) "GB" else it }
            voices += buildJsonObject {
                put("group", "peiyinya"); put("code", code); put("name", name); put("desc", description)
                put("gender", (2 - field("gender").toInt()).toShort().toInt())
                put("locale", "${field("diction")}-$country"); put("avatar", "$code.png")
                put("param", parameters); put("extendUI", extension)
                put("type", 1); put("sampleRate", 44100)
                put("speed", 1f); put("volume", 1f); put("pitch", 1f)
            }
        }
    }
    return voices
}
