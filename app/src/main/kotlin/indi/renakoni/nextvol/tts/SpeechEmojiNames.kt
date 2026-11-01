package indi.renakoni.nextvol.tts

import kotlinx.serialization.json.Json
import java.util.Locale

/** Names and singleton-first lookup from MultiTTS 1.8.3's packaged emoji tables. */
internal object SpeechEmojiNames {
    private val english by lazy { read("emoji") }
    private val chinese by lazy { read("emoji-zh") }
    private val traditional by lazy { read("emoji-zh-rTW") }
    private val russian by lazy { read("emoji-ru") }

    private fun read(name: String): Map<String, String> =
        checkNotNull(javaClass.getResourceAsStream("/tts/$name.json")).bufferedReader().use {
            Json.decodeFromString(it.readText())
        }

    fun replace(input: SpeechMappedText, checkCancelled: () -> Unit, locale: Locale = Locale.getDefault()): SpeechMappedText {
        val localized = when (locale.language) {
            "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") traditional else chinese
            "ru" -> russian
            else -> english
        }
        fun name(key: String) = localized[key] ?: english[key]
        return speechReplaceEmoji(input, { name(it.toString()) }, { first, second -> name("${first}_$second") }, checkCancelled)
    }
}
