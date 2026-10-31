package indi.renakoni.nextvol.tts

/** Each output UTF-16 unit retains the original code-point range that produced it. */
internal class SpeechMappedText private constructor(
    val text: String, private val starts: IntArray, private val ends: IntArray,
    private val emptyOrigin: Pair<Int, Int> = 0 to 0,
) {
    fun slice(start: Int, end: Int) = SpeechMappedText(text.substring(start, end),
        starts.copyOfRange(start, end), ends.copyOfRange(start, end), if (start == end) range(start, end) else 0 to 0)

    fun range(start: Int = 0, end: Int = text.length): Pair<Int, Int> {
        if (start == end) {
            if (text.isEmpty()) return emptyOrigin
            val anchor = start.coerceAtMost(text.lastIndex)
            return starts[anchor] to ends[anchor]
        }
        var first = starts[start]
        var last = ends[start]
        for (index in start + 1 until end) {
            first = minOf(first, starts[index])
            last = maxOf(last, ends[index])
        }
        return first to last
    }

    fun anchor(index: Int) = starts[index.coerceIn(0, text.lastIndex)]

    class Builder(initialSize: Int) {
        private val text = StringBuilder(initialSize)
        private var starts = IntArray(initialSize)
        private var ends = IntArray(initialSize)

        private fun reserve(count: Int) {
            if (text.length + count > starts.size) {
                val size = maxOf(text.length + count, starts.size * 2, 16)
                starts = starts.copyOf(size)
                ends = ends.copyOf(size)
            }
        }

        fun append(input: SpeechMappedText, start: Int, end: Int) {
            reserve(end - start)
            input.starts.copyInto(starts, text.length, start, end)
            input.ends.copyInto(ends, text.length, start, end)
            text.append(input.text, start, end)
        }

        fun append(value: String, origin: Pair<Int, Int>) {
            reserve(value.length)
            starts.fill(origin.first, text.length, text.length + value.length)
            ends.fill(origin.second, text.length, text.length + value.length)
            text.append(value)
        }

        val length get() = text.length
        // A later zero-width replacement can insert into a completely deleted input.
        fun build(input: SpeechMappedText) = SpeechMappedText(text.toString(), starts.copyOf(text.length),
            ends.copyOf(text.length), if (text.isEmpty()) input.range() else 0 to 0)
    }

    companion object {
        fun original(text: String): SpeechMappedText {
            val starts = IntArray(text.length)
            val ends = IntArray(text.length)
            var index = 0
            while (index < text.length) {
                val end = index + Character.charCount(text.codePointAt(index))
                starts.fill(index, index, end)
                ends.fill(end, index, end)
                index = end
            }
            return SpeechMappedText(text, starts, ends)
        }
    }
}
