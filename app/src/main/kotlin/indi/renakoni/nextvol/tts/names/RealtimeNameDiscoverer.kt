package indi.renakoni.nextvol.tts.names

import java.util.regex.Pattern
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min

data class RealtimeNameCandidate(val name: String, val score: Double)

/** MultiTTS P6 statistics for one caller-owned session. A cancelled instance must be discarded. */
class RealtimeNameDiscoverer(regex: String, stopwords: Set<String>) {
    private class Node {
        val children = HashMap<Char, Node>()
        var terminal = false
        var count = 0
    }

    private val pattern = Pattern.compile(regex)
    private val forward = Node()
    private val backward = Node()
    private val stopForward = Node()
    private val stopBackward = Node()
    private var marker = -1

    init {
        for (word in stopwords) {
            put(stopForward, word, 0)
            put(stopBackward, word.reversed(), 0)
        }
    }

    fun submit(text: String, reverseWindows: Boolean, checkCancelled: () -> Unit = {}): List<RealtimeNameCandidate> {
        checkCancelled()
        if (pattern.pattern().isEmpty()) return emptyList()
        marker = (marker + 1) % 8
        val decorated = "^_-+.*#%"[marker] + text + "$"
        val matcher = pattern.matcher(DiscoveryText(decorated, checkCancelled))
        val windows = ArrayList<String>()
        while (matcher.find()) {
            checkCancelled()
            windows.add(decorated.substring((matcher.start() - 1).coerceAtLeast(0), min(matcher.end() + 3, decorated.length)))
        }
        if (reverseWindows) windows.reverse()
        val result = ArrayList<RealtimeNameCandidate>()
        var priority = 100.0
        for (window in windows) {
            checkCancelled()
            for (length in 2..6) {
                for (start in 0..window.length - length) {
                    if (start and 255 == 0) checkCancelled()
                    val word = window.substring(start, start + length)
                    val count = (find(forward, word)?.count ?: 0) + 1
                    put(forward, word, count)
                    put(backward, word.reversed(), count)
                }
            }
            for (length in 2..5) {
                for (start in 1 until window.length - length) {
                    if (start and 255 == 0) checkCancelled()
                    val word = window.substring(start, start + length)
                    if (!pattern.matcher(word).matches() || hasStopPrefix(stopForward, word) ||
                        hasStopPrefix(stopBackward, word.reversed())) continue
                    val right = find(forward, word)!!
                    val left = find(backward, word.reversed())!!
                    val score = min(entropy(left), entropy(right)) * log10(right.count.toDouble())
                    if (score > 0.0) result.add(RealtimeNameCandidate(word, score * priority))
                }
            }
            priority /= 10.0
        }
        // Preserve P6's integer-cast comparator and repeated candidates from different windows.
        var comparisons = 0
        result.sortWith { first, second ->
            if (comparisons++ and 1023 == 0) checkCancelled()
            (second.score - first.score).toInt()
        }
        checkCancelled()
        return result
    }

    private fun put(root: Node, word: String, count: Int) {
        var node = root
        for (character in word) node = node.children.getOrPut(character) { Node() }
        node.terminal = true
        node.count = count
    }

    private fun find(root: Node, word: String): Node? {
        var node = root
        for (character in word) node = node.children[character] ?: return null
        return node
    }

    private fun hasStopPrefix(root: Node, word: String): Boolean {
        var node = root
        for (character in word) {
            node = node.children[character] ?: return false
            if (node.terminal) return true
        }
        return false
    }

    private fun entropy(node: Node): Double {
        var entropy = 0.0
        for (child in node.children.values) {
            if (!child.terminal) continue
            val probability = child.count.toDouble() / node.count
            entropy -= ln(probability) * probability
        }
        return entropy
    }
}
