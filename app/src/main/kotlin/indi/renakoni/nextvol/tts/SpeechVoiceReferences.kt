package indi.renakoni.nextvol.tts

import javax.inject.Inject

class SpeechVoiceReferences @Inject constructor(
    private val settings: SpeechSettingsRepository,
    private val pairs: SpeechVoicePoolRepository,
) {
    internal suspend fun replaceReference(previous: SpeechVoiceRef, replacement: SpeechVoiceRef, overwritten: SpeechVoiceRef? = null) {
        settings.updateAll {
            val updated = it.replaceVoiceReference(previous, replacement)
            if (overwritten == null) updated else updated.updateVoiceSelections(overwritten, replacement)
        }
        pairs.replaceVoiceReference(previous, replacement)
    }
}

internal fun SpeechSettings.replaceVoiceReference(previous: SpeechVoiceRef, replacement: SpeechVoiceRef): SpeechSettings {
    fun replace(value: SpeechVoiceRef) = if (value.canonicalKey == previous.canonicalKey) replacement.copy(externalId = value.externalId) else value
    val selections = updateVoiceSelections(previous, replacement)
    val selected = replace(voiceReference())
    return copy(engine = selected.engine, voice = selected.voice, httpSource = selected.httpSource, offlineVoice = selected.offlineVoice,
        narrationVoices = selections.narrationVoices, dialogueVoices = selections.dialogueVoices,
        speakerVoices = speakerVoices.mapValues { replace(it.value) }, roleRules = roleRules.map { it.copy(speaker = it.speaker?.let(::replace)) },
        voiceItems = voiceItems.map { it.copy(value = replace(it.value)) })
}
