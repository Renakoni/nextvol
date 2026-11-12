package indi.renakoni.nextvol.ui.tts

import hnovel.speech.SpeechSearchQuery
import indi.renakoni.nextvol.tts.SpeechReplacementRule

/** MultiTTS r2.p.w / D6.a.v: whitespace-separated conditions are combined with AND. */
internal fun filterSpeechReplacements(rules: List<SpeechReplacementRule>, query: String): List<SpeechReplacementRule> {
    val search = SpeechSearchQuery(query)
    val locale = search.locale
    return rules.filter { rule ->
        search.terms.all { (prefix, value) ->
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
