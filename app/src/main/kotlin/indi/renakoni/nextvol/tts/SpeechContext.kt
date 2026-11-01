package indi.renakoni.nextvol.tts

import kotlinx.serialization.Serializable

/** Immutable chapter-boundary state. Preparing a future chapter never mutates the active context. */
@Serializable
data class SpeechContext(
    val openDialogue: Boolean = false,
    val previousText: List<SpeechContextText> = emptyList(),
    /** Two dialogue voice slots, with the next slot first. Null slots are significant. */
    val recentVoices: List<String?> = emptyList(),
    val narrationIndex: Int = -1,
    val dialogueIndex: Int = -1,
)

@Serializable
data class SpeechContextText(val text: String, val dialogue: Boolean, val personVoice: SpeechPersonVoice? = null)

@Serializable
data class SpeechPersonVoice(val person: String, val voice: String)

private val speakerMarker = Regex("<<.*?>>")

internal fun resolveSpeechContext(
    parts: MutableList<SpeechStructure>,
    settings: SpeechTextSettings,
    incoming: SpeechContext,
    unfinished: Boolean,
    resolve: ((String, SpeechContextDirection) -> SpeechPersonVoice?)?,
    checkCancelled: () -> Unit,
): SpeechStructureResult {
    var nextVoice = incoming.recentVoices.getOrNull(0)
    var otherVoice = incoming.recentVoices.getOrNull(1)
    var rememberedVoice = false

    fun propagate(index: Int, personVoice: SpeechPersonVoice) {
        for (previous in index - 1 downTo 0) {
            checkCancelled()
            val part = parts[previous]
            if (!part.dialogue || part.speaker != null) break
            parts[previous] = part.copy(personVoice = personVoice)
            if (part.changeVoice) break
        }
    }
    fun search(indices: IntProgression, direction: SpeechContextDirection): SpeechPersonVoice? {
        for (index in indices) {
            checkCancelled()
            val part = parts[index]
            if (part.dialogue) break
            if (part.speaker != null) return part.personVoice
            val personVoice = resolve?.invoke(part.content.text, direction) ?: continue
            parts[index] = part.copy(personVoice = personVoice)
            return personVoice
        }
        return null
    }
    for (index in parts.indices) {
        checkCancelled()
        var part = parts[index]
        if (!part.dialogue) continue
        val text = part.content.text
        val markers = if (text.length > 5) speakerMarker.findAll(text).toList() else emptyList()
        val marker = markers.firstOrNull()
        val hint = marker?.value?.let { it.substring(2, it.length - 2).trim { char -> Character.isWhitespace(char) } }
            ?.takeIf { it.isNotEmpty() }
        part = part.copy(speakerHint = hint,
            beforeText = (index - 1 downTo 0).asSequence().map { parts[it] }.takeWhile { !it.dialogue }
                .map { it.content.text }.toList(),
            afterText = (index + 1 until parts.size).asSequence().map { parts[it] }.takeWhile { !it.dialogue }
                .map { it.content.text }.toList())
        val rememberedText = if (index == 0 && settings.recordText)
            incoming.previousText.takeWhile { !it.dialogue }.map { it.text } else emptyList()
        if (resolve != null) {
            val explicit = hint?.let { resolve(it, SpeechContextDirection.Explicit) }
            if (explicit != null) {
                val output = SpeechMappedText.Builder(text.length)
                var copied = 0
                for (match in markers) {
                    checkCancelled()
                    output.append(part.content, copied, match.range.first)
                    copied = match.range.last + 1
                }
                output.append(part.content, copied, text.length)
                part = part.copy(content = output.build(part.content), personVoice = explicit, changeVoice = true)
                propagate(index, explicit)
            } else {
                val previous = if (index > 0) parts[index - 1].let {
                    SpeechContextText(it.content.text, it.dialogue, it.personVoice)
                } else incoming.previousText.firstOrNull()
                var personVoice = previous?.takeIf { !part.changeVoice && it.dialogue &&
                    !it.personVoice?.person.isNullOrEmpty() }?.personVoice
                if (personVoice == null) personVoice = search(index - 1 downTo 0, SpeechContextDirection.Previous)
                if (personVoice == null) {
                    personVoice = search(index + 1 until parts.size, SpeechContextDirection.Next)
                    if (personVoice != null) propagate(index, personVoice)
                    else if (index == 0 && settings.recordText) {
                        part = part.copy(rememberedText = rememberedText)
                        for (context in incoming.previousText) {
                            checkCancelled()
                            if (context.dialogue) break
                            personVoice = context.personVoice?.takeIf { it.person.isNotEmpty() }
                                ?: resolve(context.text, SpeechContextDirection.Next)
                            if (personVoice != null) break
                        }
                    }
                }
                if (personVoice == null && (parts.getOrNull(index - 1)?.dialogue == false ||
                    parts.getOrNull(index + 1)?.dialogue == false)) personVoice = SpeechPersonVoice("", "")
                part = part.copy(personVoice = personVoice)
            }
            if (settings.recordVoice && part.changeVoice) {
                rememberedVoice = true
                val voice = part.personVoice?.voice
                var rotate = false
                when {
                    voice == null -> {
                        part = part.copy(previousVoice = nextVoice)
                        rotate = true
                    }
                    voice == nextVoice -> rotate = true
                    voice == otherVoice -> Unit
                    nextVoice == null -> {
                        nextVoice = voice
                        rotate = true
                    }
                    otherVoice == null -> otherVoice = voice
                    else -> {
                        nextVoice = voice
                        rotate = true
                    }
                }
                if (rotate) {
                    val selected = nextVoice
                    nextVoice = otherVoice
                    otherVoice = selected
                }
            }
        } else part = part.copy(rememberedText = rememberedText)
        parts[index] = part
    }
    val previousText = if (parts.last().dialogue) listOf(parts.last())
        else parts.asReversed().takeWhile { !it.dialogue }
    val context = SpeechContext(openDialogue = unfinished && settings.continueDialogue,
        previousText = previousText.map { SpeechContextText(it.content.text, it.dialogue, it.personVoice) },
        recentVoices = if (rememberedVoice) listOf(nextVoice, otherVoice) else incoming.recentVoices)
    return SpeechStructureResult(parts.toList(), context)
}
