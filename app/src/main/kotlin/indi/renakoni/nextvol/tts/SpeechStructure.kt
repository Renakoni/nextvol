package indi.renakoni.nextvol.tts

internal data class SpeechStructure(val content: SpeechMappedText, val dialogue: Boolean, val speaker: String? = null)

/** Basic quote/explicit-name boundary between replacement stages. Contextual dialogue belongs to #807. */
internal fun speechStructure(input: SpeechMappedText, checkCancelled: () -> Unit): List<SpeechStructure> {
    val result = mutableListOf<SpeechStructure>()
    var start = 0
    var dialogue = false
    fun add(end: Int) {
        if (start == end) return
        var content = input.slice(start, end)
        var speaker: String? = null
        if (dialogue) {
            val first = content.text.indexOf("<<")
            val last = if (first >= 0) content.text.indexOf(">>", first + 2) else -1
            speaker = if (last >= 0) content.text.substring(first + 2, last).trim().takeIf { it.isNotEmpty() } else null
            if (speaker != null) {
                val output = SpeechMappedText.Builder(content.text.length)
                var copied = 0
                var markerStart = first
                var markerEnd = last
                while (markerStart >= 0 && markerEnd >= 0) {
                    checkCancelled()
                    output.append(content, copied, markerStart)
                    copied = markerEnd + 2
                    markerStart = content.text.indexOf("<<", copied)
                    markerEnd = if (markerStart >= 0) content.text.indexOf(">>", markerStart + 2) else -1
                }
                output.append(content, copied, content.text.length)
                content = output.build(content)
            }
        }
        result += SpeechStructure(content, dialogue, speaker)
    }
    for (index in input.text.indices) {
        checkCancelled()
        when (input.text[index]) {
            '“', '"' -> if (!dialogue) {
                add(index)
                start = index
                dialogue = true
            } else if (input.text[index] == '"') {
                add(index + 1)
                start = index + 1
                dialogue = false
            }
            '”' -> if (dialogue) {
                add(index + 1)
                start = index + 1
                dialogue = false
            }
            '\n' -> {
                add(index)
                start = index + 1
                dialogue = false
            }
        }
    }
    add(input.text.length)
    return result
}
