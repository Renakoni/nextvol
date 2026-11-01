package indi.renakoni.nextvol.tts.names

/** UTF-16 ranges in the supplied text, including separate occurrences of the same name. */
data class SpeechNameCandidate(val name: String, val start: Int, val end: Int)

/** MultiTTS 1.8.3 widens UTF-16 units: 30 slots, PAD=0, UNK=1, O/B/I tags 0/1/2. */
internal class NerDecoder(private val vocabulary: Map<Int, Int>, private val transitions: DoubleArray) {
    fun encode(text: String): IntArray {
        val ids = IntArray(30)
        for (position in 0 until minOf(text.length, ids.size)) ids[position] = vocabulary[text[position].code] ?: 1
        return ids
    }

    fun decode(text: String, emissions: FloatArray, origin: Int): List<SpeechNameCandidate> {
        // Decode padded positions too: they affect backtracking, as in the original native implementation.
        var scores = emissions.copyOfRange(0, 3)
        val previous = Array(30) { IntArray(3) }
        for (position in 1 until 30) {
            scores = FloatArray(3) { tag ->
                var best = -Float.MAX_VALUE
                for (prior in 0 until 3) {
                    val score = (scores[prior].toDouble() + transitions[prior * 3 + tag]).toFloat()
                    if (score > best) {
                        best = score
                        previous[position][tag] = prior
                    }
                }
                best + emissions[position * 3 + tag]
            }
        }
        val tags = IntArray(30)
        tags[29] = scores.indices.maxBy { scores[it] }
        for (position in 29 downTo 1) tags[position - 1] = previous[position][tags[position]]
        val result = mutableListOf<SpeechNameCandidate>()
        var start = -1
        var offset = 0
        var position = 0
        fun finish(end: Int) {
            if (start >= 0) result += SpeechNameCandidate(text.substring(start, end), origin + start, origin + end)
        }
        while (offset < text.length) {
            when (tags[position++]) {
                1 -> { finish(offset); start = offset }
                0 -> { finish(offset); start = -1 }
            }
            offset++
        }
        finish(offset)
        return result
    }

    companion object {
        fun vocabulary(lines: Sequence<String>): Map<Int, Int> = lines.associate { line ->
            val split = line.indexOf('\t')
            line.codePointAt(0) to line.substring(split + 1).toInt()
        }

        /** Same punctuation-aware boundaries as split_long_text; offsets remain in the original string. */
        fun chunks(text: String): Sequence<IntRange> = sequence {
            val punctuation = ",.!?;:\"、，。！？；：“”「」\n …"
            var start = 0
            var delimiter = -1
            for (offset in text.indices) {
                if (text[offset] in punctuation) delimiter = offset
                if (offset - start == 30) {
                    val end = if (delimiter < 0 || delimiter == offset) offset else delimiter + 1
                    yield(start until end)
                    start = end
                    delimiter = -1
                }
            }
            if (start < text.length) yield(start until text.length)
        }
    }
}
