package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import kotlinx.serialization.Serializable

/** Catalog identity is stable when voice parameters or installed resources change. */
@Serializable
data class SpeechVoiceRef(
    val engine: String = "",
    val voice: String = "",
    val httpSource: String? = null,
    val offlineVoice: OfflineVoiceRef? = null,
    val label: String = "",
) {
    val key get() = when {
        offlineVoice != null -> "offline:${offlineVoice.id}"
        httpSource != null -> "http:$httpSource"
        else -> "system:$engine:$voice"
    }
}

data class SpeechResolvedVoice(val reference: SpeechVoiceRef, val settings: SpeechSettings,
    val maxInputLength: Int, val label: String = reference.label, val revision: String = "",
    val error: SpeechError? = null)

enum class SpeechVoiceReason { Narration, Dialogue, Explicit, Remembered, Continued }

internal fun SpeechSettings.voiceReference() = SpeechVoiceRef(engine, voice, httpSource, offlineVoice)

internal fun SpeechSettings.forVoice(reference: SpeechVoiceRef) = copy(
    engine = reference.engine, voice = reference.voice, httpSource = reference.httpSource,
    offlineVoice = reference.offlineVoice, narrationVoices = emptyList(), dialogueVoices = emptyList(),
    speakerVoices = emptyMap(), speakerBookId = "", voiceRoutingConfigured = false,
).synthesisSettings()

/** R6.p.d: separate round-robin cursors; an explicit/remembered voice does not advance either. */
internal class SpeechVoiceRouting(private val settings: SpeechSettings, private val bookId: String,
    private val voices: Map<String, SpeechResolvedVoice>, incoming: SpeechContext, private val defaultLimit: Int) {
    var narrationIndex = incoming.narrationIndex
        private set
    var dialogueIndex = incoming.dialogueIndex
        private set
    private var narration: SpeechResolvedVoice? = null
    private var dialogue: SpeechResolvedVoice? = null

    fun explicit(name: String): SpeechPersonVoice? = settings.speakerVoices
        .takeIf { settings.speakerBookId == bookId }?.get(name)?.let { SpeechPersonVoice(name, it.key) }

    private fun resolve(reference: SpeechVoiceRef) = voices[reference.key]
        ?: SpeechResolvedVoice(reference, settings.forVoice(reference), defaultLimit)

    fun select(part: SpeechStructure): Pair<SpeechResolvedVoice, SpeechVoiceReason> {
        if (part.dialogue) {
            val key = (part.personVoice?.voice?.takeIf { it.isNotEmpty() } ?: part.previousVoice)?.takeIf { it.isNotEmpty() }
            if (key != null) {
                val reference = settings.speakerVoices.values.firstOrNull { it.key == key }
                val selected = voices[key] ?: reference?.let(::resolve)
                if (selected != null) return selected to
                    if (part.personVoice?.voice == key) SpeechVoiceReason.Explicit else SpeechVoiceReason.Remembered
                return SpeechResolvedVoice(SpeechVoiceRef(voice = key), settings, defaultLimit,
                    error = SpeechError.MissingVoice) to SpeechVoiceReason.Remembered
            }
        }
        val candidates = (if (part.dialogue) settings.dialogueVoices else settings.narrationVoices)
            .ifEmpty { if (settings.voiceRoutingConfigured) emptyList() else listOf(settings.voiceReference()) }
        val reason = if (part.dialogue) SpeechVoiceReason.Dialogue else SpeechVoiceReason.Narration
        if (candidates.isEmpty()) return SpeechResolvedVoice(settings.voiceReference(), settings,
            defaultLimit, error = SpeechError.MissingVoice) to reason
        var index = if (part.dialogue) dialogueIndex else narrationIndex
        // Control spans are handled before R6.p.d and must not consume a candidate turn.
        if (part.controlMark) return resolve(candidates[maxOf(0, index) % candidates.size]) to reason
        if (part.changeVoice || index < 0) index = (index + 1) % candidates.size
        else index %= candidates.size
        val previous = if (part.dialogue) dialogue else narration
        val selected = resolve(candidates[index])
        if (part.dialogue) { dialogueIndex = index; dialogue = selected }
        else { narrationIndex = index; narration = selected }
        return selected to when {
            !part.changeVoice && previous != null -> SpeechVoiceReason.Continued
            part.dialogue -> SpeechVoiceReason.Dialogue
            else -> SpeechVoiceReason.Narration
        }
    }
}
