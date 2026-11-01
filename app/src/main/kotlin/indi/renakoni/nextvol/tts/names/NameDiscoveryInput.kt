package indi.renakoni.nextvol.tts.names

import java.io.Reader

/** The native value is a normalized score; the HanLP value is an occurrence count. */
data class NewWordCandidate(val name: String, val value: Int)

internal fun discoveryLines(reader: Reader, splitCarriageReturn: Boolean, checkCancelled: () -> Unit): Sequence<String> = sequence {
    val buffer = CharArray(4096)
    val line = StringBuilder()
    var skipLineFeed = false
    while (true) {
        checkCancelled()
        val count = reader.read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            if (index and 1023 == 0) checkCancelled()
            val character = buffer[index]
            if (skipLineFeed) {
                skipLineFeed = false
                if (character == '\n') continue
            }
            if (character == '\n' || splitCarriageReturn && character == '\r') {
                yield(line.toString())
                line.setLength(0)
                skipLineFeed = splitCarriageReturn && character == '\r'
            } else {
                line.append(character)
            }
        }
    }
    if (line.isNotEmpty()) yield(line.toString())
}

/** Also checks during a long Pattern.find/replaceAll, rather than only between matches. */
internal class DiscoveryText(private val text: String, private val checkCancelled: () -> Unit) : CharSequence {
    private var reads = 0
    override val length get() = text.length
    override fun get(index: Int): Char {
        if (reads++ and 1023 == 0) checkCancelled()
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DiscoveryText(text.substring(startIndex, endIndex), checkCancelled)
    override fun toString() = text
}
