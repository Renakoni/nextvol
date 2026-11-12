package indi.renakoni.nextvol.ui.tts

import hnovel.speech.SpeechSearchQuery
import indi.renakoni.nextvol.tts.SpeechVoiceItem
import indi.renakoni.nextvol.tts.SpeechVoicePair
import java.util.Locale

/** MultiTTS D6.a.v cases 4/5, with the shared r2.p.w AND query parser. */
internal fun filterSpeechVoicePool(items: List<SpeechVoiceItem>, query: String, groupLabel: (String) -> String): List<SpeechVoiceItem> {
    val search = SpeechSearchQuery(query)
    val locale = search.locale
    return items.filter { item -> search.terms.all { (prefix, value) ->
        when (prefix) {
            "voice" -> poolContains(item.value.key, value, locale) || poolContains(item.name, value, locale)
            "group" -> poolContains(groupLabel(item.group), value, locale)
            "ischecked" -> item.activate == (value == "true")
            else -> poolContains(item.name, prefix + value, locale) || poolContains(groupLabel(item.group), prefix + value, locale)
        }
    } }
}

internal fun filterSpeechVoicePairs(items: List<SpeechVoicePair>, query: String, groupLabel: (String) -> String): List<SpeechVoicePair> {
    val search = SpeechSearchQuery(query)
    val locale = search.locale
    return items.filter { item -> search.terms.all { (prefix, value) ->
        when (prefix) {
            "voice" -> poolContains(item.value.key, value, locale) || poolContains(item.value.label, value, locale)
            "group" -> poolContains(groupLabel(item.group), value, locale)
            "ischecked" -> false // The original pair table has no activation state.
            else -> poolContains(item.person, prefix + value, locale) || poolContains(item.value.label, prefix + value, locale)
        }
    } }
}

private fun poolContains(field: String, value: String, locale: Locale) = field.isNotEmpty() && field.lowercase(locale).contains(value)
