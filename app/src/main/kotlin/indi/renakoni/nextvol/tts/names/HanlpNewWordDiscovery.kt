package indi.renakoni.nextvol.tts.names

import java.io.Reader
import java.util.PriorityQueue
import java.util.TreeMap
import java.util.regex.Pattern
import kotlin.math.ln
import kotlin.math.min

/** The optional MultiTTS HanLP route, including its 340,000-character chunk reader. */
class HanlpNewWordDiscovery {
    private class Word(val name: String) {
        var frequency = 0
        var probability = 0f
        val left = TreeMap<Char, Int>()
        val right = TreeMap<Char, Int>()
    }

    fun discover(reader: Reader, onlyNew: Boolean, dictionary: Map<String, Int>,
                 checkCancelled: () -> Unit = {}): List<NewWordCandidate> {
        val words = TreeMap<String, Word>()
        val accumulated = HashMap<String, Int>()
        var totalLength = 0
        var chunkLength = 0L

        fun finishChunk() {
            for (word in words.values) {
                checkCancelled()
                word.probability = word.frequency.toFloat() / totalLength.toFloat()
            }
            val heap = PriorityQueue<Word>(300) { first, second ->
                first.probability.compareTo(second.probability)
            }
            for (word in words.values) {
                checkCancelled()
                if (word.name.trim { it <= ' ' }.length < 2) continue
                val entropy = min(entropy(word.left, word.frequency), entropy(word.right, word.frequency))
                var aggregation = Float.MAX_VALUE
                for (split in 1 until word.name.length) {
                    aggregation = min(aggregation, word.probability /
                        words.getValue(word.name.substring(0, split)).probability /
                        words.getValue(word.name.substring(split)).probability)
                }
                if (entropy < 0.5f || aggregation < 100f || onlyNew && (dictionary[word.name] ?: 0) > 0) continue
                if (heap.size < 300) heap.add(word)
                else if (word.probability > heap.peek().probability) {
                    heap.poll()
                    heap.add(word)
                }
            }
            val selected = ArrayList<Word>(heap.size)
            while (heap.isNotEmpty()) selected.add(heap.poll())
            // MaxHeap.toList inserts each minimum at the front before the outer HashMap merge.
            for (word in selected.asReversed()) {
                accumulated[word.name] = (accumulated[word.name] ?: 0) + word.frequency
            }
            words.clear()
            totalLength = 0
            chunkLength = 0
        }

        for (raw in discoveryLines(reader, splitCarriageReturn = true, checkCancelled)) {
            checkCancelled()
            chunkLength += raw.length
            val line = punctuation.matcher(DiscoveryText(raw, checkCancelled)).replaceAll("\u0000")
            totalLength += line.length
            for (start in line.indices) {
                if (start and 255 == 0) checkCancelled()
                for (end in start + 1..min(start + 5, line.length)) {
                    val name = line.substring(start, end)
                    if ('\u0000' in name) continue
                    val word = words.getOrPut(name) { Word(name) }
                    word.frequency++
                    val left = if (start == 0) '\u0000' else line[start - 1]
                    val right = if (end == line.length) '\u0000' else line[end]
                    word.left[left] = (word.left[left] ?: 0) + 1
                    word.right[right] = (word.right[right] ?: 0) + 1
                }
            }
            if (chunkLength > 340000) finishChunk()
        }
        finishChunk()
        val result = ArrayList<NewWordCandidate>(accumulated.size)
        for ((name, frequency) in accumulated) {
            checkCancelled()
            result.add(NewWordCandidate(name, frequency))
        }
        var comparisons = 0
        result.sortWith { first, second ->
            if (comparisons++ and 1023 == 0) checkCancelled()
            second.value - first.value
        }
        checkCancelled()
        return result.take(300)
    }

    private fun entropy(neighbors: Map<Char, Int>, frequency: Int): Float {
        var value = 0f
        for (count in neighbors.values) {
            val probability = (count.toFloat() / frequency.toFloat()).toDouble()
            value = (value.toDouble() - ln(probability) * probability).toFloat()
        }
        return value
    }

    private companion object {
        val punctuation: Pattern = Pattern.compile("[\\s\\d,.<>/?:;'\"\\[\\]{}()\\|~!@#$%^&*\\-_=+，。《》、？：；“”‘’｛｝【】（）…￥！—┄－]+")
    }
}
