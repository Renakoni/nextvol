package indi.renakoni.nextvol.tts

import java.util.Locale

internal const val SPEECH_LANGUAGE_PERSON = "default"

internal data class SpeechLanguageMatch(val language: String, val role: SpeechRoleMatch?)

internal val SpeechRoleRule.isLanguageRule get() = activate && pattern.isNotEmpty() &&
    (mode == 1 || mode != 0 && pattern.startsWith("lang:"))

/** O6.d.f / O6.f.b: last enabled rule for a language wins; exact, Latin, then base-language lookup. */
internal class SpeechLanguageRouting(rules: List<SpeechRoleRule>, private val enabled: Boolean,
    private val detect: (suspend (String, Set<String>) -> String?)?) {
    private val languages = rules.filter { it.isLanguageRule }
        .associateBy { it.pattern.removePrefix("lang:").trim { c -> Character.isWhitespace(c) } }

    val active get() = enabled && languages.isNotEmpty()

    suspend fun route(text: String): SpeechLanguageMatch? {
        if (!active) return null
        val language = (detect ?: throw SpeechException(SpeechError.LanguageDetectorUnavailable))(text, languages.keys) ?: return null
        val rule = languages[language] ?: if (language.endsWith("-Latn")) languages["en"]
            else if ('-' in language) languages[language.substringBefore('-')] else null
        return SpeechLanguageMatch(language, rule?.let { SpeechRoleMatch(SPEECH_LANGUAGE_PERSON, it.id, it.label, it.isAbstract, it.speaker) })
    }

    /** S6.e.e keeps adjacent units together until their language changes or the 250-character target is reached. */
    suspend fun split(input: SpeechMappedText, locale: Locale, checkCancelled: () -> Unit):
        List<Pair<SpeechMappedText, SpeechLanguageMatch?>> {
        val result = mutableListOf<Pair<SpeechMappedText, SpeechLanguageMatch?>>()
        var start = 0
        var end = 0
        var match: SpeechLanguageMatch? = null
        var onlyPunctuation = true
        for (unit in speechLanguageSentences(input, locale, checkCancelled)) {
            checkCancelled()
            if (unit.text.length <= 1 && speechIsUnspoken(unit.text)) {
                end += unit.text.length
                continue
            }
            val detected = route(unit.text)
            if (!onlyPunctuation && (detected?.language != match?.language || end - start + unit.text.length > 250)) {
                result += input.slice(start, end) to match
                start = end
            }
            match = detected
            onlyPunctuation = false
            end += unit.text.length
        }
        if (start < end) result += input.slice(start, end) to match
        return result
    }
}

/** Q6.a.e: same language, same country, or English; filtering never changes a saved reference. */
internal fun speechVoiceLanguageMatches(tag: String, locale: Locale = Locale.getDefault()): Boolean {
    if (tag.isBlank()) return true
    val voice = Locale.forLanguageTag(tag.replace('_', '-'))
    return voice.language == "en" || voice.language == locale.language ||
        locale.country.isNotEmpty() && voice.country == locale.country
}
