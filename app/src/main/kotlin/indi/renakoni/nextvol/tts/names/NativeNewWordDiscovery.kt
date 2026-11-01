package indi.renakoni.nextvol.tts.names

import java.io.Reader
import kotlin.math.max
import kotlin.math.min

/** Source implementation of Ngr.discovery(file, 2, 5, 1000, ""); no original .so is loaded. */
class NativeNewWordDiscovery(private val stopwords: Set<String>) {
    private class Node {
        val children = NativeDiscoveryMap<Int, Node> { it.toLong() }
        var terminal = false
        var count = 0
        var mutualInformation = 0.0
    }

    private class Trie {
        val forward = Node()
        val backward = Node()
        var distinct = 0

        fun add(points: IntArray, checkCancelled: () -> Unit) {
            if (points.size <= 1) return
            for (start in points.indices) {
                if (start and 255 == 0) checkCancelled()
                var node = forward
                for (end in start until min(start + 6, points.size)) {
                    if (isSeparator(points[end])) break
                    node = node.children.getOrPut(points[end]) { Node() }
                    if (node.count == 0) distinct++
                    node.terminal = true
                    node.count++
                    var reversed = backward
                    for (index in end downTo start) reversed = reversed.children.getOrPut(points[index]) { Node() }
                    reversed.terminal = true
                    reversed.count = node.count
                }
            }
        }

        fun set(points: IntArray, count: Int) {
            var first = forward
            var second = backward
            for (index in points.indices) {
                first = first.children.getOrPut(points[index]) { Node() }
                second = second.children.getOrPut(points[points.lastIndex - index]) { Node() }
            }
            first.count = count
            second.count = count
            first.terminal = true
            second.terminal = true
        }
    }

    private data class ScoredWord(val name: String, val score: Double)

    fun discover(reader: Reader, onlyNew: Boolean, dictionary: Map<String, Int>,
                 checkCancelled: () -> Unit = {}): List<NewWordCandidate> {
        var trie = Trie()
        var lines = 0
        val accumulated = NativeDiscoveryMap<String, Double>(::nativeDiscoveryWordHash)

        fun finishChunk() {
            val chunk = removeTails(score(trie, checkCancelled), checkCancelled)
            for (word in chunk.take(10000)) {
                checkCancelled()
                accumulated[word.name] = (accumulated[word.name] ?: 0.0) + word.score
            }
            trie = Trie()
        }

        for (line in discoveryLines(reader, splitCarriageReturn = false, checkCancelled)) {
            checkCancelled()
            trie.add(codePoints(line, checkCancelled), checkCancelled)
            if (++lines % 10000 == 0) finishChunk()
        }
        finishChunk()
        val scored = ArrayList<ScoredWord>()
        for ((name, value) in accumulated) {
            checkCancelled()
            scored.add(ScoredWord(name, value))
        }
        sort(scored, checkCancelled)
        val selected = removeTails(scored, checkCancelled).take(1000)
        if (selected.isEmpty()) return emptyList()
        val scale = 100.0 / nativeDiscoveryLog2(selected.first().score * 100.0)
        val result = ArrayList<NewWordCandidate>()
        for (word in selected) {
            checkCancelled()
            if (onlyNew && (dictionary[word.name] ?: 0) > 0) continue
            result.add(NewWordCandidate(word.name, max(0, (scale * nativeDiscoveryLog2(word.score * 100.0)).toInt())))
            if (result.size == 300) break
        }
        return result
    }

    private fun score(trie: Trie, checkCancelled: () -> Unit): MutableList<ScoredWord> {
        val path = IntArray(6)
        val probabilities = DoubleArray(6)

        fun mutualInformation(node: Node, depth: Int, previousBest: Double, previousSplit: Int) {
            checkCancelled()
            val probability = node.count.toDouble() / trie.distinct
            var best = previousBest
            var split = previousSplit
            var value = 0.0
            if (depth > 0) {
                // Even the backward traversal looks up its suffix in the FORWARD trie.
                var suffix: Node? = trie.forward
                for (index in split + 1..depth) suffix = suffix?.children?.get(path[index])
                value = probability * nativeDiscoveryLog2(probability /
                    (probabilities[split] * ((suffix?.count ?: 0).toDouble() / trie.distinct)))
                if (value >= 0.0) {
                    if (value >= best) {
                        best = value
                        split = depth
                    }
                } else value = 0.0
            }
            node.mutualInformation = value
            probabilities[depth] = probability
            for ((point, child) in node.children) {
                path[depth + 1] = point
                mutualInformation(child, depth + 1, best, split)
            }
        }

        for (root in listOf(trie.forward, trie.backward)) {
            for ((point, node) in root.children) {
                path[0] = point
                mutualInformation(node, 0, -1.0, 0)
            }
        }

        val result = ArrayList<ScoredWord>()
        fun collect(node: Node, length: Int) {
            checkCancelled()
            if (length in 2..5) {
                val name = String(path, 0, length)
                if (name !in stopwords) {
                    var backward = trie.backward
                    for (index in length - 1 downTo 0) backward = backward.children.getValue(path[index])
                    val mutual = if (length <= 2) node.mutualInformation
                        else min(node.mutualInformation, backward.mutualInformation)
                    val characters = NativeDiscoveryMap<Int, Int> { it.toLong() }
                    for (index in 0 until length) characters[path[index]] = (characters[path[index]] ?: 0) + 1
                    val internalEntropy = entropy(characters.values, length)
                    val neighborEntropy = min(neighborEntropy(node, checkCancelled), neighborEntropy(backward, checkCancelled))
                    if (mutual != 0.0 && internalEntropy != 0.0 && neighborEntropy != 0.0) {
                        result.add(ScoredWord(name, mutual * internalEntropy * node.count * neighborEntropy))
                    }
                }
            }
            if (length < 5) {
                for ((point, child) in node.children) {
                    path[length] = point
                    collect(child, length + 1)
                }
            }
        }
        for ((point, node) in trie.forward.children) {
            path[0] = point
            collect(node, 1)
        }
        sort(result, checkCancelled)
        return result
    }

    private fun removeTails(words: List<ScoredWord>, checkCancelled: () -> Unit): List<ScoredWord> {
        if (words.size < 2) return words
        val known = Trie()
        val orderedStopwords = NativeDiscoveryMap<String, Boolean>(::nativeDiscoveryWordHash)
        for (word in stopwords) {
            checkCancelled()
            orderedStopwords[word] = true
        }
        for ((word, _) in orderedStopwords) {
            checkCancelled()
            known.set(codePoints(word, checkCancelled), 1000000)
        }
        val best = words.first().score
        val result = ArrayList<ScoredWord>()
        for (word in words) {
            checkCancelled()
            val points = codePoints(word.name, checkCancelled)
            val related = max(max(prefixFrequency(known.forward, points), prefixFrequency(known.backward, points.reversedArray())),
                max(superwordFrequency(known.forward, points), superwordFrequency(known.backward, points.reversedArray())))
            val scaled = (1000000.0 / best * word.score).toInt()
            if (related < 1 || related <= scaled * 10) {
                result.add(word)
                known.set(points, scaled)
            }
        }
        return result
    }

    private fun prefixFrequency(root: Node, word: IntArray): Int {
        var node = root
        for (point in word) node = node.children[point] ?: return node.count
        return 0
    }

    private fun superwordFrequency(root: Node, word: IntArray): Int {
        var node = root
        for (point in word) node = node.children[point] ?: return 0
        while (!node.terminal) {
            // Native follows only the first child in its libc++ unordered_map chain.
            node = node.children.values.firstOrNull() ?: return 0
        }
        return node.count
    }

    private fun neighborEntropy(node: Node, checkCancelled: () -> Unit): Double {
        var result = 0.0
        var index = 0
        for (child in node.children.values) {
            if (index++ and 1023 == 0) checkCancelled()
            if (child.count == 0) continue
            val probability = child.count.toDouble() / node.count
            result -= probability * nativeDiscoveryLog2(probability)
        }
        return result
    }

    private fun entropy(counts: Iterable<Int>, total: Int): Double {
        var result = 0.0
        for (count in counts) {
            if (count == 0) continue
            val probability = count.toDouble() / total
            result -= probability * nativeDiscoveryLog2(probability)
        }
        return result
    }

    private fun sort(words: MutableList<ScoredWord>, checkCancelled: () -> Unit) {
        var comparisons = 0
        words.sortWith { first, second ->
            if (comparisons++ and 1023 == 0) checkCancelled()
            second.score.compareTo(first.score)
        }
    }

    private companion object {
        const val PUNCTUATION = ",.!?;:()\"，。！？；：（）“”、\n…「」"

        fun isSeparator(point: Int): Boolean = point == 32 || point in 9..13 ||
            point <= Char.MAX_VALUE.code && point.toChar() in PUNCTUATION

        fun codePoints(text: String, checkCancelled: () -> Unit): IntArray {
            val points = IntArray(text.length)
            var index = 0
            var size = 0
            while (index < text.length) {
                if (size and 1023 == 0) checkCancelled()
                val point = text.codePointAt(index)
                points[size++] = point
                index += Character.charCount(point)
            }
            return if (size == points.size) points else points.copyOf(size)
        }
    }
}
