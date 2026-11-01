package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.tts.SpeechReplacementRule
import java.util.Locale

/** MultiTTS r2.p.w / D6.a.v: whitespace-separated conditions are combined with AND. */
internal fun filterSpeechReplacements(rules: List<SpeechReplacementRule>, query: String): List<SpeechReplacementRule> {
    val terms = query.trim { Character.isWhitespace(it) }.replace(Regex("\\s+"), " ")
        .replace(Regex("(?<=:)\\s+"), "").split(' ')
    val locale = Locale.getDefault()
    return terms.fold(rules) { matches, term ->
        val separator = term.indexOf(':')
        val prefix = if (separator < 0) "" else term.substring(0, separator).lowercase(locale)
        val value = term.substring(separator + 1).lowercase(locale)
        matches.filter { rule ->
            fun contains(field: String) = field.isNotEmpty() && field.lowercase(locale).contains(value)
            when (prefix) {
                "source" -> contains(rule.source)
                "target" -> contains(rule.target)
                "isregex" -> rule.regex == (value == "true")
                "ischecked" -> rule.activate == (value == "true")
                else -> {
                    val keyword = prefix + value
                    rule.name.lowercase(locale).contains(keyword) ||
                        rule.source.isNotEmpty() && rule.source.lowercase(locale).contains(keyword) ||
                        rule.target.isNotEmpty() && rule.target.lowercase(locale).contains(keyword)
                }
            }
        }
    }
}
