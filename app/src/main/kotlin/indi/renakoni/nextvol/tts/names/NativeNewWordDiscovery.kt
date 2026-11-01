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
        val orderedStopwords = NativeDiscoveryMap<String, IntArray>(::nativeDiscoveryWordHash)
        for (word in stopwords) {
            checkCancelled()
            orderedStopwords[word] = codePoints(word, checkCancelled)
        }

        fun finishChunk() {
            val chunk = removeTails(score(trie, checkCancelled), orderedStopwords.values, checkCancelled)
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
        val selected = removeTails(scored, orderedStopwords.values, checkCancelled).take(1000)
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

        // Both intermediate unordered_maps affect the vector order before native std::sort.
        val nodes = NativeDiscoveryMap<String, Node>(::nativeDiscoveryWordHash)
        fun collect(node: Node, length: Int) {
            checkCancelled()
            if (node.terminal) nodes[String(path, 0, length)] = node
            for ((point, child) in node.children) {
                path[length] = point
                collect(child, length + 1)
            }
        }
        collect(trie.forward, 0)
        val scores = NativeDiscoveryMap<String, Double>(::nativeDiscoveryWordHash)
        for ((name, node) in nodes) {
            checkCancelled()
            val points = codePoints(name, checkCancelled)
            if (points.size !in 2..5 || name in stopwords) continue
            val characters = NativeDiscoveryMap<Int, Int> { it.toLong() }
            for (point in points) characters[point] = (characters[point] ?: 0) + 1
            val internalEntropy = entropy(characters.values, points.size, checkCancelled)
            if (internalEntropy == 0.0 || node.mutualInformation == 0.0) continue
            var backward = trie.backward
            for (index in points.lastIndex downTo 0) backward = backward.children.getValue(points[index])
            val mutual = if (points.size <= 2) node.mutualInformation
                else min(node.mutualInformation, backward.mutualInformation)
            if (mutual == 0.0) continue
            val neighborEntropy = min(neighborEntropy(backward, checkCancelled), neighborEntropy(node, checkCancelled))
            if (neighborEntropy != 0.0) scores[name] = mutual * internalEntropy * node.count * neighborEntropy
        }
        val result = ArrayList<ScoredWord>()
        for ((name, value) in scores) {
            checkCancelled()
            result.add(ScoredWord(name, value))
        }
        sort(result, checkCancelled)
        return result
    }

    private fun removeTails(words: List<ScoredWord>, stopwordPoints: Iterable<IntArray>,
                            checkCancelled: () -> Unit): List<ScoredWord> {
        if (words.size < 2) return words
        val known = Trie()
        for (points in stopwordPoints) {
            checkCancelled()
            known.set(points, 1000000)
        }
        val best = words.first().score
        val result = ArrayList<ScoredWord>()
        for (word in words) {
            checkCancelled()
            val points = codePoints(word.name, checkCancelled)
            val reversed = points.reversedArray()
            val related = max(max(prefixFrequency(known.forward, points), prefixFrequency(known.backward, reversed)),
                max(superwordFrequency(known.forward, points), superwordFrequency(known.backward, reversed)))
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
        // getN1Prefix/SuffixWords copies terminal children into a fresh native map.
        val counts = NativeDiscoveryMap<Int, Int> { it.toLong() }
        var index = 0
        for ((point, child) in node.children) {
            if (index++ and 1023 == 0) checkCancelled()
            if (child.terminal) counts[point] = child.count
        }
        return entropy(counts.values, node.count, checkCancelled)
    }

    private fun entropy(counts: Iterable<Int>, total: Int, checkCancelled: () -> Unit): Double {
        var result = 0.0
        var index = 0
        for (count in counts) {
            if (index++ and 1023 == 0) checkCancelled()
            if (count == 0) continue
            val probability = count.toDouble() / total
            result -= probability * nativeDiscoveryLog2(probability)
        }
        return result
    }

    private fun sort(words: MutableList<ScoredWord>, checkCancelled: () -> Unit) {
        ScoreSort(words, checkCancelled).sort(0, words.size, 2 * (31 - Integer.numberOfLeadingZeros(words.size)), true)
    }

    /* Score-specific libc++ 18 introsort. Equal-score order feeds native tail filtering.
     * Adapted from LLVM __algorithm/sort.h and heap helpers; Apache-2.0 WITH LLVM-exception.
     * Full license: assets/speech/names/LICENSE-libcxx.txt.
     */
    private class ScoreSort(private val words: MutableList<ScoredWord>, private val checkCancelled: () -> Unit) {
        private var comparisons = 0

        private fun before(first: ScoredWord, second: ScoredWord): Boolean {
            if (comparisons++ and 1023 == 0) checkCancelled()
            return first.score > second.score
        }

        private fun swap(first: Int, second: Int) {
            val saved = words[first]
            words[first] = words[second]
            words[second] = saved
        }

        private fun sortThree(first: Int, second: Int, third: Int) {
            if (before(words[second], words[first])) swap(first, second)
            if (before(words[third], words[second])) swap(second, third)
            if (before(words[second], words[first])) swap(first, second)
        }

        private fun insert(index: Int, first: Int) {
            val value = words[index]
            var position = index
            while (position > first && before(value, words[position - 1])) {
                words[position] = words[position - 1]
                position--
            }
            words[position] = value
        }

        private fun insertionSort(first: Int, last: Int) {
            for (index in first + 1 until last) insert(index, first)
        }

        private fun insertionSortIncomplete(first: Int, last: Int): Boolean {
            if (last - first <= 5) {
                insertionSort(first, last)
                return true
            }
            sortThree(first, first + 1, first + 2)
            var moved = 0
            for (index in first + 3 until last) {
                if (before(words[index], words[index - 1])) {
                    insert(index, first)
                    if (++moved == 8) return index + 1 == last
                }
            }
            return true
        }

        private fun partitionLeft(first: Int, last: Int): Int {
            val pivot = words[first]
            var left = first
            var right = last
            if (before(pivot, words[last - 1])) {
                do { left++ } while (!before(pivot, words[left]))
            } else {
                do { left++ } while (left < right && !before(pivot, words[left]))
            }
            if (left < right) {
                do { right-- } while (before(pivot, words[right]))
            }
            while (left < right) {
                swap(left, right)
                do { left++ } while (!before(pivot, words[left]))
                do { right-- } while (before(pivot, words[right]))
            }
            words[first] = words[left - 1]
            words[left - 1] = pivot
            return left
        }

        private fun partitionRight(first: Int, last: Int): Pair<Int, Boolean> {
            val pivot = words[first]
            var left = first
            var right = last
            do { left++ } while (before(words[left], pivot))
            if (left == first + 1) {
                while (left < right) {
                    right--
                    if (before(words[right], pivot)) break
                }
            } else {
                do { right-- } while (!before(words[right], pivot))
            }
            val alreadyPartitioned = left >= right
            while (left < right) {
                swap(left, right)
                do { left++ } while (before(words[left], pivot))
                do { right-- } while (!before(words[right], pivot))
            }
            words[first] = words[left - 1]
            words[left - 1] = pivot
            return left - 1 to alreadyPartitioned
        }

        fun sort(start: Int, end: Int, initialDepth: Int, initiallyLeftmost: Boolean) {
            var first = start
            var last = end
            var depth = initialDepth
            var leftmost = initiallyLeftmost
            while (true) {
                val length = last - first
                if (length < 24) {
                    insertionSort(first, last)
                    return
                }
                if (depth-- == 0) {
                    heapSort(first, last)
                    return
                }
                val middle = first + length / 2
                if (length > 128) {
                    sortThree(first, middle, last - 1)
                    sortThree(first + 1, middle - 1, last - 2)
                    sortThree(first + 2, middle + 1, last - 3)
                    sortThree(middle - 1, middle, middle + 1)
                    swap(first, middle)
                } else sortThree(middle, first, last - 1)
                if (!leftmost && !before(words[first - 1], words[first])) {
                    first = partitionLeft(first, last)
                    continue
                }
                val (pivot, alreadyPartitioned) = partitionRight(first, last)
                if (alreadyPartitioned) {
                    val leftComplete = insertionSortIncomplete(first, pivot)
                    if (insertionSortIncomplete(pivot + 1, last)) {
                        if (leftComplete) return
                        last = pivot
                        continue
                    }
                    if (leftComplete) {
                        first = pivot + 1
                        continue
                    }
                }
                sort(first, pivot, depth, leftmost)
                first = pivot + 1
                leftmost = false
            }
        }

        private fun heapSort(first: Int, last: Int) {
            val length = last - first
            fun child(parent: Int, size: Int): Int {
                var index = parent * 2 + 1
                if (index + 1 < size && before(words[first + index], words[first + index + 1])) index++
                return index
            }
            for (start in (length - 2) / 2 downTo 0) {
                var position = start
                var next = child(position, length)
                val value = words[first + position]
                if (before(words[first + next], value)) continue
                do {
                    words[first + position] = words[first + next]
                    position = next
                    if (position > (length - 2) / 2) break
                    next = child(position, length)
                } while (!before(words[first + next], value))
                words[first + position] = value
            }
            for (size in length downTo 2) {
                val top = words[first]
                var hole = 0
                do {
                    val next = child(hole, size)
                    words[first + hole] = words[first + next]
                    hole = next
                } while (hole <= (size - 2) / 2)
                if (hole == size - 1) words[first + hole] = top
                else {
                    val value = words[first + size - 1]
                    words[first + size - 1] = top
                    while (hole > 0) {
                        val parent = (hole - 1) / 2
                        if (!before(words[first + parent], value)) break
                        words[first + hole] = words[first + parent]
                        hole = parent
                    }
                    words[first + hole] = value
                }
            }
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
