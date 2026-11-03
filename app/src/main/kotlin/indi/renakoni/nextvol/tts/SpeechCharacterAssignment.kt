package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.names.SpeechNameClassification
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import java.util.regex.Pattern

/** O6.f.e/a/c and O6.j.g: rules first, then discovery, existing gender pairs, classification and pool. */
internal class SpeechCharacterAssignment(
    private val settings: SpeechSettings,
    private val chapter: SpeechChapter,
    private val pairs: List<SpeechVoicePair>,
    private val catalog: Map<String, SpeechResolvedVoice>,
    private val recognize: suspend (String) -> List<String>,
    private val newWords: suspend (String, String, Boolean) -> List<String>,
    private val classify: suspend (String) -> SpeechNameClassification,
    private val existing: suspend (String, List<String>) -> SpeechVoicePair?,
    private val assign: suspend (String, String) -> SpeechVoicePair?,
    private val checkCancelled: () -> Unit,
) {
    private val manualVoices = (pairs.filter { it.manual && it.person != SPEECH_LANGUAGE_PERSON }.map { it.person to it.value } +
        settings.speakerVoices.takeIf { settings.speakerBookId == chapter.bookId }.orEmpty().toList())
        .distinctBy { it.first }.toMap()

    private fun manual(person: String): SpeechVoiceRef? = manualVoices[person]

    suspend fun allocate(match: SpeechRoleMatch): SpeechRoleMatch {
        manual(match.person)?.let { return match.copy(voiceRef = it, origin = SpeechPersonOrigin.Manual) }
        if (!match.isAbstract) return match
        val group = match.voiceRef?.voice?.takeIf { it == SPEECH_POOL_MALE || it == SPEECH_POOL_FEMALE }
            ?: configurationPoolGroup(match.ruleName)
        val pair = assign(match.person, group)
        return match.copy(voiceRef = pair?.value ?: match.voiceRef, poolGroup = group)
    }

    /** O6.f.b / O6.j.h: language defaults are paired within their own abstract role. */
    suspend fun allocateLanguage(match: SpeechRoleMatch): SpeechRoleMatch {
        if (!match.isAbstract) return match
        val group = configurationPoolGroup(match.ruleName)
        val pair = assign(SPEECH_LANGUAGE_PERSON, group)
        return match.copy(voiceRef = pair?.value ?: match.voiceRef, poolGroup = group,
            origin = if (pair?.manual == true) SpeechPersonOrigin.Manual else match.origin)
    }

    suspend fun resolve(text: String, direction: SpeechContextDirection, unitKey: String,
        rules: SpeechRoleRules): SpeechPersonVoice? {
        checkCancelled()
        known(text, direction, manualVoices.keys.toList(), rules)?.let { name ->
            val voice = manual(name)!!
            return SpeechRoleMatch(name, "", "", false, voice, origin = SpeechPersonOrigin.Manual).personVoice()
        }
        rules.resolve(text, direction)?.let { return allocate(it).personVoice() }
        // Existing people remain recognisable when automatic discovery is switched off.
        val known = known(text, direction, pairs.filter { !it.manual && it.person != SPEECH_LANGUAGE_PERSON }
            .map { it.person }.distinct(), rules)
        if (known != null) {
            val groups = pairs.filter { it.person == known }.map { it.group }
            existing(known, groups)?.let { return it.match(SpeechPersonOrigin.Pair).personVoice() }
        }
        if (!settings.autoAssignRole) return null
        val name = if (direction == SpeechContextDirection.Explicit) text else {
            val candidates = when (settings.text.nameDiscoveryAlgorithm) {
                100 -> newWords(text, "$unitKey:${direction.name}", direction == SpeechContextDirection.Previous)
                else -> recognize(text).let { if (direction == SpeechContextDirection.Previous) it.asReversed() else it }
            }
            rules.contextualName(text, candidates) ?: return null
        }
        if (name.isEmpty()) return null
        // Original O6.j.g looks in both gender groups before asking Ngr again.
        existing(name, listOf(SPEECH_POOL_FEMALE, SPEECH_POOL_MALE))?.let {
            return it.match(SpeechPersonOrigin.Pair).personVoice()
        }
        val classification = classify(name)
        val group = when (classification.hint) {
            SpeechNameHint.Male -> SPEECH_POOL_MALE
            SpeechNameHint.Female -> SPEECH_POOL_FEMALE
            SpeechNameHint.Unknown -> return null
        }
        val pair = assign(name, group) ?: return null
        return pair.match(if (settings.text.nameDiscoveryAlgorithm == 100) SpeechPersonOrigin.NewWord else SpeechPersonOrigin.Ner)
            .copy(classification = classification.basis.name).personVoice()
    }

    private suspend fun known(text: String, direction: SpeechContextDirection, names: List<String>,
        rules: SpeechRoleRules): String? {
        if (direction == SpeechContextDirection.Explicit) return text.takeIf { it in names }
        if (names.isEmpty()) return null
        val pattern = Pattern.compile(names.joinToString("|") { Pattern.quote(it) })
        val matches = pattern.matcher(text)
        val candidates = mutableListOf<String>()
        while (matches.find()) { checkCancelled(); candidates += matches.group() }
        if (direction == SpeechContextDirection.Previous) candidates.reverse()
        return rules.contextualName(text, candidates)
    }

    private fun SpeechVoicePair.match(origin: SpeechPersonOrigin) = SpeechRoleMatch(person, "", group, true,
        catalog[value.key]?.reference ?: value, poolGroup = group, origin = origin)
}
