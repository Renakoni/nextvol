package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.tts.SpeechVoiceItem
import indi.renakoni.nextvol.tts.SpeechVoicePair
import java.util.Locale

/** MultiTTS D6.a.v cases 4/5, with the shared r2.p.w AND query parser. */
internal fun filterSpeechVoicePool(items: List<SpeechVoiceItem>, query: String, groupLabel: (String) -> String) =
    poolTerms(query).fold(items) { matches, (prefix, value) -> matches.filter { item ->
        when (prefix) {
            "voice" -> poolContains(item.value.key, value) || poolContains(item.name, value)
            "group" -> poolContains(groupLabel(item.group), value)
            "ischecked" -> item.activate == (value == "true")
            else -> poolContains(item.name, prefix + value) || poolContains(groupLabel(item.group), prefix + value)
        }
    } }

internal fun filterSpeechVoicePairs(items: List<SpeechVoicePair>, query: String, groupLabel: (String) -> String) =
    poolTerms(query).fold(items) { matches, (prefix, value) -> matches.filter { item ->
        when (prefix) {
            "voice" -> poolContains(item.value.key, value) || poolContains(item.value.label, value)
            "group" -> poolContains(groupLabel(item.group), value)
            "ischecked" -> false // The original pair table has no activation state.
            else -> poolContains(item.person, prefix + value) || poolContains(item.value.label, prefix + value)
        }
    } }

private fun poolTerms(query: String): List<Pair<String, String>> = query.trim { Character.isWhitespace(it) }
    .replace(Regex("\\s+"), " ").replace(Regex("(?<=:)\\s+"), "").split(' ').map { term ->
        val separator = term.indexOf(':')
        (if (separator < 0) "" else term.substring(0, separator).lowercase(Locale.getDefault())) to
            term.substring(separator + 1).lowercase(Locale.getDefault())
    }

private fun poolContains(field: String, value: String) = field.isNotEmpty() && field.lowercase(Locale.getDefault()).contains(value)
