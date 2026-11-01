package indi.renakoni.nextvol.tts

internal data class SpeechStructure(
    val content: SpeechMappedText,
    val dialogue: Boolean,
    val changeVoice: Boolean = true,
    val speakerHint: String? = null,
    val personVoice: SpeechPersonVoice? = null,
    val beforeText: List<String> = emptyList(),
    val afterText: List<String> = emptyList(),
    val rememberedText: List<String> = emptyList(),
    /** A remembered voice suggestion, without inventing a person for an unresolved dialogue. */
    val previousVoice: String? = null,
    val controlMark: Boolean = false,
    val textRule: SpeechRoleMatch? = null,
) {
    val speaker get() = personVoice?.person?.takeIf { it.isNotEmpty() }
}

internal data class SpeechStructureResult(val parts: List<SpeechStructure>, val nextContext: SpeechContext)

internal enum class SpeechContextDirection { Explicit, Previous, Next }

private enum class QuoteState { DialogueBefore, DialogueStart, DialogueEnd, NarrationStart, NarrationQuote }

internal val speechControlMark = Regex("\\[\\[.*?\\]\\]")

/** MultiTTS's five quote states; role rules supply the optional resolver in the role pipeline. */
internal suspend fun speechStructure(
    input: SpeechMappedText,
    settings: SpeechTextSettings,
    incoming: SpeechContext = SpeechContext(),
    resolve: (suspend (String, SpeechContextDirection) -> SpeechPersonVoice?)? = null,
    textRules: List<SpeechRoleText> = emptyList(),
    checkCancelled: () -> Unit,
): SpeechStructureResult {
    checkCancelled()
    if (input.text.isEmpty()) return SpeechStructureResult(emptyList(), incoming)

    val result = mutableListOf<SpeechStructure>()
    var start = 0
    var state = if (incoming.openDialogue) QuoteState.DialogueStart else QuoteState.DialogueBefore
    var changeDialogue = !incoming.openDialogue
    var changeNarration = true
    data class Bypass(val start: Int, val end: Int, val role: SpeechRoleMatch?)
    val bypasses = mutableListOf<Bypass>()
    var offset = 0
    for (span in textRules.ifEmpty { listOf(SpeechRoleText(input)) }) {
        if (span.match != null) bypasses += Bypass(offset, offset + span.content.text.length, span.match)
        else speechControlMark.findAll(span.content.text).forEach {
            bypasses += Bypass(offset + it.range.first, offset + it.range.last + 1, null)
        }
        offset += span.content.text.length
    }
    val controls = bypasses.iterator()
    var control = if (controls.hasNext()) controls.next() else null
    fun emit(end: Int, dialogue: Boolean) {
        result += SpeechStructure(input.slice(start, end), dialogue,
            changeVoice = if (dialogue) changeDialogue else changeNarration)
        start = end
    }
    for (index in input.text.indices) {
        checkCancelled()
        if (control != null && index >= control.end) control = if (controls.hasNext()) controls.next() else null
        if (control != null && index in control.start until control.end) {
            if (index == control.start) {
                if (start < index) {
                    val dialogue = settings.dialogue && state == QuoteState.DialogueStart
                    emit(index, dialogue)
                    changeDialogue = !dialogue
                    if (!dialogue) changeNarration = false
                }
                val end = control.end
                result += SpeechStructure(input.slice(index, end), false, changeVoice = control.role != null,
                    personVoice = control.role?.personVoice(), controlMark = control.role == null, textRule = control.role)
                start = end
            }
            continue
        }
        if (!settings.dialogue) continue
        val char = input.text[index]
        if (char == ' ' || char == '\u3000') continue
        when (state) {
            QuoteState.DialogueBefore -> when (char) {
                '"', '“' -> {
                    state = QuoteState.DialogueStart
                    if (start < index) {
                        emit(index, false)
                        changeNarration = false
                        changeDialogue = true
                    }
                }
                '”' -> {
                    emit(index + 1, true)
                    changeDialogue = true
                }
                else -> state = QuoteState.NarrationStart
            }
            QuoteState.DialogueStart -> if (char == '"' || char == '”') {
                emit(index + 1, true)
                state = QuoteState.DialogueEnd
                changeDialogue = true
            }
            QuoteState.DialogueEnd -> state = when (char) {
                '"', '“' -> QuoteState.DialogueStart
                in ":,;.!?：，；。！？" -> QuoteState.DialogueBefore
                else -> QuoteState.NarrationStart
            }
            QuoteState.NarrationStart -> when (char) {
                '”' -> {
                    emit(index + 1, true)
                    state = QuoteState.DialogueEnd
                    changeDialogue = true
                }
                '"', '“' -> {
                    if (start < index) emit(index, false)
                    state = QuoteState.NarrationQuote
                    changeNarration = true
                    changeDialogue = true
                }
                in ":,;.!?：，；。！？" -> state = QuoteState.DialogueBefore
            }
            QuoteState.NarrationQuote -> when (char) {
                ':', '：' -> state = QuoteState.DialogueBefore
                '"', '”' -> {
                    emit(index + 1, false)
                    state = QuoteState.NarrationStart
                    changeNarration = true
                    changeDialogue = true
                }
            }
        }
    }
    val unfinished = settings.dialogue && (state == QuoteState.DialogueStart || state == QuoteState.NarrationQuote)
    if (start < input.text.length) emit(input.text.length, unfinished)
    // Tagged control spans bypass the quote FSM and the original contextual-text list.
    val contextual = result.filterNot { it.controlMark || it.textRule != null }.toMutableList()
    if (!settings.dialogue || contextual.isEmpty()) return SpeechStructureResult(result, incoming)
    val resolved = resolveSpeechContext(contextual, settings, incoming, unfinished, resolve, checkCancelled)
    val parts = resolved.parts.iterator()
    return SpeechStructureResult(result.map { if (it.controlMark || it.textRule != null) it else parts.next() }, resolved.nextContext)
}
