package hnovel.speech

import java.util.Locale

/** Shared MultiTTS query syntax; each list owns its field matching. */
class SpeechSearchQuery(query: String) {
    val locale: Locale = Locale.getDefault()
    val terms: List<Pair<String, String>> = query.trim { Character.isWhitespace(it) }.replace(whitespace, " ")
        .replace(afterColon, "").split(' ').filter(String::isNotEmpty).map { term ->
            val separator = term.indexOf(':')
            if (separator < 0) "" to term.lowercase(locale)
            else term.substring(0, separator).lowercase(locale) to term.substring(separator + 1).lowercase(locale)
        }

    private companion object {
        val whitespace = Regex("\\s+")
        val afterColon = Regex("(?<=:)\\s+")
    }
}
