package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.logging.SpeechLogEvent
import indi.renakoni.nextvol.data.logging.SpeechLogRecord
import indi.renakoni.nextvol.data.logging.SpeechLogStage
import indi.renakoni.nextvol.tts.names.SpeechNameHintBasis
import java.util.concurrent.atomic.AtomicLong

/** Observes the production plan. It never evaluates rules, allocates voices or retains another plan. */
internal class SpeechPlanTrace(private val record: (SpeechLogRecord) -> Unit, private val string: (Int) -> String,
    private val enabled: () -> Boolean = { true }, private val rules: List<SpeechRoleRule> = emptyList()) {
    val id = sequence.incrementAndGet()
    private val replacements = ArrayDeque<() -> Unit>()

    fun input(chapter: SpeechChapter) {
        if (!enabled()) return
        emit(listOf(R.string.tts_log_book to chapter.bookTitle, R.string.tts_log_chapter to chapter.title,
            R.string.tts_log_original to chapter.text))
    }

    fun text(label: Int, text: String) { if (enabled()) emit(listOf(label to text)) }

    fun replacement(rule: SpeechReplacementRule, wholeText: Boolean, range: Pair<Int, Int>, before: String, after: String) {
        if (!enabled()) return
        if (replacements.size == 1000) replacements.removeFirst()
        replacements.addLast {
            record(SpeechLogRecord(SpeechLogStage.Analysis, SpeechLogEvent.Completed,
                subject = listOf(rule.group, rule.label).filter(String::isNotEmpty).joinToString(" / "),
                processId = id, details = listOf(
                    R.string.tts_log_replacement_stage to if (wholeText) "1" else "2",
                    R.string.tts_log_range to "${range.first}..${range.second}",
                    R.string.tts_log_before to before,
                    R.string.tts_log_after to after,
                )))
        }
    }

    fun flushReplacements() {
        while (replacements.isNotEmpty()) {
            val emit = replacements.removeFirst()
            if (enabled()) emit()
        }
    }

    fun structure(part: SpeechStructure) {
        if (!enabled()) return
        emit(buildList {
            add(R.string.tts_log_range to part.content.range().let { "${it.first}..${it.second}" })
            add(R.string.tts_log_dialogue to boolean(part.dialogue))
            add(R.string.tts_log_change_voice to boolean(part.changeVoice))
            part.speakerHint?.let { add(R.string.tts_log_hint to it) }
            part.speaker?.let { add(R.string.tts_log_person to it) }
            add(R.string.tts_log_structure to part.content.text)
            if (part.beforeText.isNotEmpty()) add(R.string.tts_log_before_context to part.beforeText.joinToString("\n"))
            if (part.afterText.isNotEmpty()) add(R.string.tts_log_after_context to part.afterText.joinToString("\n"))
            if (part.rememberedText.isNotEmpty()) add(R.string.tts_log_remembered_context to part.rememberedText.joinToString("\n"))
        })
    }

    fun pairing(pair: SpeechVoicePair, existing: Boolean) {
        if (!enabled()) return
        record(SpeechLogRecord(SpeechLogStage.Analysis, SpeechLogEvent.Completed, voice = pair.value, processId = id,
            details = listOf(R.string.tts_log_pairing to string(if (existing) R.string.tts_log_pair_existing else R.string.tts_log_pair_assigned),
                R.string.tts_log_person to pair.person, R.string.tts_log_pool to poolLabel(pair.group),
                R.string.tts_log_manual to boolean(pair.manual))))
    }

    fun segment(index: Int, segment: SpeechSegment) {
        if (!enabled()) return
        val role = segment.roleMatch
        val voice = segment.selectedVoice
        record(SpeechLogRecord(SpeechLogStage.Analysis, SpeechLogEvent.Completed,
            voice = voice?.reference,
            processId = id, segment = index + 1, details = buildList {
                add(R.string.tts_log_range to "${segment.start}..${segment.end}")
                add(R.string.tts_log_dialogue to boolean(segment.dialogue))
                (role?.person ?: segment.speaker)?.let { add(R.string.tts_log_person to it) }
                segment.language?.let { add(R.string.tts_log_language to it) }
                role?.let {
                    if (it.ruleId.isNotEmpty()) add(R.string.tts_log_role to it.ruleName)
                    rules.firstOrNull { rule -> rule.id == it.ruleId }?.let { rule ->
                        add(R.string.tts_log_pattern to rule.pattern)
                    }
                    add(R.string.tts_log_origin to string(when (it.origin) {
                        SpeechPersonOrigin.Rule -> R.string.tts_person_rule
                        SpeechPersonOrigin.Manual -> R.string.tts_person_manual
                        SpeechPersonOrigin.Pair -> R.string.tts_person_pair
                        SpeechPersonOrigin.Ner -> R.string.tts_person_ner
                        SpeechPersonOrigin.NewWord -> R.string.tts_person_new_word
                    }))
                    it.classification?.let { classification -> add(R.string.tts_log_classification to when (classification) {
                        SpeechNameHintBasis.Model.name -> string(R.string.tts_name_basis_model)
                        SpeechNameHintBasis.MaleRule.name -> string(R.string.tts_name_basis_male)
                        SpeechNameHintBasis.FemaleRule.name -> string(R.string.tts_name_basis_female)
                        SpeechNameHintBasis.None.name -> string(R.string.tts_name_basis_none)
                        else -> classification
                    }) }
                    it.poolGroup?.let { group -> add(R.string.tts_log_pool to poolLabel(group)) }
                }
                add(R.string.tts_log_reason to string(when (segment.voiceReason) {
                    SpeechVoiceReason.Narration -> R.string.tts_voice_narrator
                    SpeechVoiceReason.Dialogue -> R.string.tts_voice_dialogue
                    SpeechVoiceReason.Explicit -> R.string.tts_log_reason_explicit
                    SpeechVoiceReason.Remembered -> R.string.tts_log_reason_remembered
                    SpeechVoiceReason.Continued -> R.string.tts_log_reason_continued
                    SpeechVoiceReason.Language -> R.string.tts_log_reason_language
                }))
                voice?.let { addAll(speechParameterDetails(it.settings, it.voiceParameters, null)) }
                add(R.string.tts_log_final_text to segment.text)
            }))
    }

    private fun emit(details: List<Pair<Int, String>>) = record(SpeechLogRecord(
        SpeechLogStage.Analysis, SpeechLogEvent.Completed, processId = id, details = details))

    private fun boolean(value: Boolean) = string(if (value) R.string.tts_log_yes else R.string.tts_log_no)

    private fun poolLabel(group: String) = when (group) {
        SPEECH_POOL_MALE -> string(R.string.tts_pool_male)
        SPEECH_POOL_FEMALE -> string(R.string.tts_pool_female)
        else -> group
    }

    private companion object { val sequence = AtomicLong() }
}

/** Global controls and voice controls are separate from the actual PCM adjustments. */
internal fun speechParameterDetails(settings: SpeechSettings, voice: SpeechAudioParameters?, audio: SpeechAudioParameters?) = buildList {
    add(R.string.tts_log_global_parameters to "${settings.rate ?: "—"} / ${settings.pitch ?: "—"} / ${settings.httpVolume}")
    voice?.let { add(R.string.tts_log_voice_parameters to "${it.speed} / ${it.pitch} / ${it.volume}") }
    audio?.let { add(R.string.tts_log_audio_parameters to "${it.speed} / ${it.pitch} / ${it.volume}") }
    add(R.string.tts_log_audio_processing to "${settings.audio.processor} / ${settings.audio.minimumSampleRate} / ${settings.audio.checkSilence}")
}
