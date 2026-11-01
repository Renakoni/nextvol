package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.names.*
import java.io.File
import java.io.StringReader
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class NewWordDiscoveryTest {
    private val assets = File("src/main/assets/speech/names")
    private val regex by lazy { File(assets, "p6-default-person-name-regex.txt").readText() }
    private val realtimeStops by lazy { File(assets, "p6-stopwords.txt").readLines().toSet() }
    private val nativeStops by lazy { File(assets, "native-stopwords.txt").readLines().toSet() }
    private val dictionary by lazy {
        GZIPInputStream(File(assets, "dictionary-frequency.tsv.gz").inputStream()).bufferedReader().use { reader ->
            reader.lineSequence().associate { line ->
                val split = line.lastIndexOf('\t')
                line.substring(0, split) to line.substring(split + 1).toInt()
            }
        }
    }
    private val segments = listOf("林逸来到门前。", "林逸转身离开。", "林逸笑着点头。", "林逸站在窗边。")

    @Test fun realtimeMatchesOriginalSubmissionBoundariesAndWindowDirection() {
        // Original P6 runtime: ev_15f5ad3d97f97066ee8570274fc1cbcb623f3172714c47b68c1950e3dfe2f80b.
        val incremental = RealtimeNameDiscoverer(regex, realtimeStops)
        val outputs = segments.map { incremental.submit(it, false) }
        assertTrue(outputs.first().isEmpty())
        assertArrayEquals(doubleArrayOf(20.86580927584611, 52.417127361976846, 83.46323710338444),
            outputs.drop(1).map { it.single().score }.toDoubleArray(), 1e-12)
        assertEquals(listOf("林逸", "林逸", "林逸"), outputs.drop(1).map { it.single().name })

        val text = segments.joinToString("")
        val complete = RealtimeNameDiscoverer(regex, realtimeStops).submit(text, false)
        assertEquals(listOf("林逸", "林逸", "林逸"), complete.map { it.name })
        assertArrayEquals(doubleArrayOf(2.086580927584611, 0.30369443862366347, 0.03385594922926082),
            complete.map { it.score }.toDoubleArray(), 1e-12)
        val reversed = RealtimeNameDiscoverer(regex, realtimeStops).submit(text, true)
        assertEquals("林逸", reversed.single().name)
        assertEquals(0.03385594922926082, reversed.single().score, 1e-12)
        assertTrue(RealtimeNameDiscoverer(regex, realtimeStops + "逸").submit(text, false).isEmpty())
    }

    @Test fun clearedAndPrefixMatchingPatternsAvoidTheOriginalNegativeWindowStart() {
        assertTrue(RealtimeNameDiscoverer("", emptySet()).submit("林逸来到门前。", false).isEmpty())
        assertTrue(RealtimeNameDiscoverer("\\^林逸", emptySet()).submit("林逸来到门前。", false).isEmpty())
    }

    @Test fun nativeReturnsOriginalScoresAndFiltersOnlyExactDictionaryEntries() {
        val text = ("今天天气晴朗，明天天气转阴。大家谈论天气变化，好天气让人开心。" +
            "林逸抬头，路边的林逸停下脚步。有人叫林逸，众人请林逸进门。").repeat(8)
        val discovery = NativeNewWordDiscovery(nativeStops)
        assertEquals(listOf(NewWordCandidate("林逸", 100), NewWordCandidate("天气", 96)),
            discovery.discover(text.reader(), false, dictionary))
        assertEquals(listOf(NewWordCandidate("林逸", 100)), discovery.discover(text.reader(), true, dictionary))
        val overlap = ("东方青岚走来，少年东方青岚笑了。大家望着东方青岚，随后东方青岚坐下。" +
            "青岚抬头，门外青岚回话。有人叫青岚，大家请青岚入座。").repeat(6)
        // The original maximum really is 99 here; forcing 100 changes its log2/truncation behavior.
        assertEquals(listOf(NewWordCandidate("青岚", 99), NewWordCandidate("东方青岚", 90)),
            discovery.discover(overlap.reader(), false, dictionary))
    }

    @Test fun backwardMutualInformationStillLooksUpTheForwardSuffix() {
        // This frozen original input distinguishes forward lookup (0) from backward lookup (1).
        val result = NativeNewWordDiscovery(nativeStops).discover(fixture("reverse-mi.txt").reader(), false, emptyMap())
        assertEquals(107, result.size)
        assertEquals(0, result.single { it.name == "辛庚戊" }.value)
    }

    @Test fun nativeTrieChildrenKeepLibcxxCollisionAndRehashOrder() {
        // Frozen LLVM libc++ 18.1.8 output; this differs from reverse insertion order.
        val children = NativeDiscoveryMap<Int, Int> { it.toLong() }
        listOf(1, 3, 5, 7, 9, 11, 13, 15, 2, 4, 6, 8).forEachIndexed { index, key -> children[key] = index }
        val order = listOf(8, 6, 4, 15, 2, 13, 11, 9, 7, 5, 3, 1)
        assertEquals(order, children.map { it.first })
        children[2] = 99
        assertEquals(order, children.map { it.first })
        assertEquals(99, children[2])
    }

    @Test fun hanlpKeepsOriginalFloatThresholdsAndReturnsFrequencies() {
        val text = fixture("hanlp-context.txt")
        val discovery = HanlpNewWordDiscovery()
        assertEquals(mapOf("林逸" to 32, "天气" to 32),
            discovery.discover(text.reader(), false, dictionary).associate { it.name to it.value })
        assertEquals(listOf(NewWordCandidate("林逸", 32)), discovery.discover(text.reader(), true, dictionary))
        assertEquals(3358, dictionary["中国"])
        assertEquals(1000, dictionary["艾莉丝"])
    }

    @Test fun hanlpResetsContextAtTheOriginalReaderBoundary() {
        val noise = fixture("hanlp-context.txt").substringBefore("今天天气晴朗")
        val first = noise + "甲林逸来。甲林逸去。".repeat(16)
        val second = noise + "乙林逸来。乙林逸去。".repeat(16)
        val discovery = HanlpNewWordDiscovery()
        assertEquals(64, discovery.discover("$first\n$second".reader(), false, emptyMap()).single { it.name == "林逸" }.value)
        val separated = first + "\n" + "。".repeat(340001 - first.length) + "\n" + second
        assertFalse(discovery.discover(separated.reader(), false, emptyMap()).any { it.name == "林逸" })
    }

    @Test fun cancellationInterruptsLongLinesAfterReadingWithoutClosingTheCallersReader() {
        for (native in listOf(true, false)) {
            val reader = object : StringReader("甲".repeat(20000)) {
                var reachedEnd = false
                var closed = false
                override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { if (it < 0) reachedEnd = true }
                override fun close() { closed = true; super.close() }
            }
            var processingChecks = 0
            val cancel = {
                if (reader.reachedEnd && ++processingChecks == 5) throw CancellationException()
            }
            assertThrows(CancellationException::class.java) {
                if (native) NativeNewWordDiscovery(nativeStops).discover(reader, false, emptyMap(), cancel)
                else HanlpNewWordDiscovery().discover(reader, false, emptyMap(), cancel)
            }
            assertEquals(5, processingChecks)
            assertFalse(reader.closed)
        }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            RealtimeNameDiscoverer("林逸", emptySet()).submit("甲".repeat(20000), false) {
                if (++checks == 5) throw CancellationException()
            }
        }
    }

    private fun fixture(name: String): String = javaClass.getResource("/speech/new-words/$name")!!.readText()
}
