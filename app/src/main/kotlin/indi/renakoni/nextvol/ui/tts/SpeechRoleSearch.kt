package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.tts.SpeechRoleRule
import java.util.Locale

/** r2.p.w / D6.a.v: submitted whitespace-separated conditions are combined with AND. */
internal fun filterSpeechRoles(rules: List<SpeechRoleRule>, query: String): List<SpeechRoleRule> {
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
                "isabstract" -> rule.isAbstract == (value == "true")
                "ischecked" -> rule.activate == (value == "true")
                "matchmode" -> when (value) { "before" -> rule.matchBefore; "after" -> rule.matchAfter; else -> true }
                "lang" -> rule.regex.startsWith("lang:") && contains(rule.regex.removePrefix("lang:"))
                "regex" -> contains(rule.regex)
                "voice" -> contains(rule.speaker?.label.orEmpty()) || contains(rule.speaker?.let {
                    it.httpSource ?: it.offlineVoice?.id ?: it.voice
                }.orEmpty())
                else -> {
                    val keyword = prefix + value
                    listOf(rule.name, rule.regex, rule.speaker?.label.orEmpty()).any { it.lowercase(locale).contains(keyword) }
                }
            }
        }
    }
}

internal fun speechRoleSearchSuggestions(rules: List<SpeechRoleRule>) = listOf(
    "isAbstract:true", "isAbstract:false", "isChecked:true", "isChecked:false",
    "matchMode:before", "matchMode:after", "regex:", "voice:", "lang:",
) + rules.map { it.name }
