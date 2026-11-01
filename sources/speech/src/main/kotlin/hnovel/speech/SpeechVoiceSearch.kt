package hnovel.speech

import java.util.Locale

data class SpeechSearchVoice(
    val name: String,
    val code: String = "",
    val description: String = "",
    val note: String = "",
    val locale: String = "",
    val parameters: String = "",
    val extensions: String = "",
    val gender: Int = 0,
    val type: Int = 0,
    val sampleRate: Int = 16000,
    val selected: Boolean = false,
)

/** MultiTTS speaker search: terms intersect; plain terms search name, description and code. */
class SpeechVoiceSearch(query: String) {
    private val locale = Locale.getDefault()
    private val terms = query.trim { Character.isWhitespace(it) }.replace(whitespace, " ")
        .replace(afterColon, "").split(' ').filter(String::isNotEmpty).map { term ->
            val separator = term.indexOf(':')
            if (separator < 0) "" to term.lowercase(locale)
            else term.substring(0, separator).lowercase(locale) to term.substring(separator + 1).lowercase(locale)
        }

    fun matches(voice: SpeechSearchVoice): Boolean = terms.all { (field, value) ->
        fun String.containsTerm(term: String = value) = isNotEmpty() && lowercase(locale).contains(term)
        when (field) {
            "locale" -> voice.locale.containsTerm()
            "ischecked" -> voice.selected == (value == "true")
            "gender" -> voice.gender == when (value) { "male" -> 1; "female" -> 0; else -> -1 }
            "type" -> (voice.type == 0) == (value == "offline")
            "samplerate" -> {
                val rate = if (value.endsWith('k')) value.dropLast(1).toDoubleOrNull()?.toInt()?.times(1000)
                    else value.toDoubleOrNull()?.toInt()
                rate != null && voice.sampleRate == rate
            }
            "code" -> voice.code.containsTerm()
            "desc" -> voice.description.containsTerm()
            "note" -> voice.note.containsTerm()
            "param" -> voice.parameters.containsTerm()
            "extendui" -> voice.extensions.containsTerm()
            else -> (field + value).let { voice.name.containsTerm(it) || voice.description.containsTerm(it) || voice.code.containsTerm(it) }
        }
    }

    private companion object {
        val whitespace = Regex("\\s+")
        val afterColon = Regex("(?<=:)\\s+")
    }
}
