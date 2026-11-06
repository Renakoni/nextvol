package hnovel.speech

import hnovel.rules.RequestOptionsJson
import kotlinx.serialization.json.*

internal fun microsoftPackageParameters(text: String): JsonObject =
    if (text.isBlank()) JsonObject(emptyMap()) else RequestOptionsJson.parse(text).jsonObject

internal fun microsoftPackageAuthUrl(parameters: JsonObject): String {
    val host = parameters["authHost"]?.jsonPrimitive?.contentOrNull
    return if (host.isNullOrEmpty()) MicrosoftSpeechClient.AUTH_URL else "https://$host/apps/endpoint?api-version=1.0"
}

/** ExtendUI.parseExtendParams ignores malformed optional controls instead of disabling the speaker. */
internal fun nativeSpeechExtensions(text: String): Map<String, String?> = try {
    if (text.isBlank()) emptyMap() else RequestOptionsJson.parse(text).jsonArray
        .filterIsInstance<JsonObject>().filter { it.string("name").isNotEmpty() }
        .associate { "@${it.string("code")}" to it["value"]?.jsonPrimitive?.contentOrNull }
} catch (_: IllegalArgumentException) { emptyMap() }

/** F6.o's ordered speaker parameters; an empty slot stops the remaining optional parameters. */
internal class MicrosoftPackageVoice(voice: JsonObject) {
    val language = voice.string("locale").ifEmpty { "en-US" }
    val name: String
    private var role: String? = "Default"
    private var style: String? = "Default"
    private var degree: String? = "1.0"
    private var languageSkill: String? = null

    init {
        val extensions = nativeSpeechExtensions(voice.string("extendUI"))
        val parameters = java.util.regex.Pattern.compile(",").split(voice.string("param")).map {
            it.trim { char -> Character.isWhitespace(char) }
        }
        name = parameters.firstOrNull().orEmpty()
        for (index in 1..4) {
            val value = parameters.getOrNull(index) ?: break
            if (value.isEmpty()) break
            val resolved = if (value.startsWith('@')) extensions[value] else value
            when (index) { 1 -> role = resolved; 2 -> style = resolved; 3 -> degree = resolved; 4 -> languageSkill = resolved }
        }
    }

    fun ssml(text: String, rate: Float, volume: Float): String {
        if (name.isEmpty()) throw HttpSpeechException(HttpSpeechError.InvalidSource)
        val content = languageSkill?.takeIf { it.isNotEmpty() && !it.equals("default", true) }?.let {
            "<lang xml:lang=\"${xmlSpeech(it)}\">${xmlSpeech(text)}</lang>"
        } ?: xmlSpeech(text)
        return "<speak xmlns=\"http://www.w3.org/2001/10/synthesis\" xmlns:mstts=\"http://www.w3.org/2001/mstts\" " +
            "xmlns:emo=\"http://www.w3.org/2009/10/emotionml\" version=\"1.0\" xml:lang=\"${xmlSpeech(language)}\">" +
            "<voice name=\"${xmlSpeech(name)}\"><mstts:express-as style=\"${xmlSpeech(style.toString())}\" " +
            "styledegree=\"${xmlSpeech(degree.toString())}\" role=\"${xmlSpeech(role.toString())}\">" +
            "<prosody rate=\"${(multiTtsControl(rate) * .8f).toInt()}%\" pitch=\"0%\" volume=\"${multiTtsControl(volume)}\">" +
            "$content</prosody></mstts:express-as></voice></speak>"
    }
}

internal fun xmlSpeech(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
