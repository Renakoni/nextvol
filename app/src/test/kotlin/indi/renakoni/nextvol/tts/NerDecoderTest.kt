package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.tts.names.NerDecoder
import indi.renakoni.nextvol.tts.names.SpeechNameCandidate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NerDecoderTest {
    @Test fun modelEmissionsReproduceOriginalNamesAndLocateEachOccurrence() {
        // Emissions from the bundled model; expected names independently observed in MultiTTS 1.8.3.
        val fixture = javaClass.getResource("/speech/ner-emissions.json")!!.readText()
        val root = Json.parseToJsonElement(fixture).jsonObject
        val transitions = root.getValue("transitions").jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        val decoder = NerDecoder(emptyMap(), transitions)
        for (case in root.getValue("cases").jsonArray.map { it.jsonObject }) {
            val text = case.getValue("text").jsonPrimitive.content
            val chunks = NerDecoder.chunks(text).toList()
            val frames = case.getValue("chunks").jsonArray
            assertEquals(frames.size, chunks.size)
            val actual = chunks.flatMapIndexed { index, range ->
                val frame = frames[index].jsonObject
                assertEquals(frame.getValue("start").jsonPrimitive.int, range.first)
                decoder.decode(text.substring(range), frame.getValue("emissions").jsonArray
                    .map { it.jsonPrimitive.float }.toFloatArray(), range.first)
            }
            val expected = case.getValue("expected").jsonArray.map { entry ->
                val value = entry.jsonObject
                SpeechNameCandidate(value.getValue("name").jsonPrimitive.content,
                    value.getValue("start").jsonPrimitive.int, value.getValue("end").jsonPrimitive.int)
            }
            assertEquals(text, expected, actual)
            actual.forEach { assertEquals(it.name, text.substring(it.start, it.end)) }
        }
    }

    @Test fun supplementaryCharactersUseTwoUnknownSlotsAsInTheOriginalJni() {
        val decoder = NerDecoder(mapOf('明'.code to 42), DoubleArray(9))
        assertArrayEquals(intArrayOf(1, 1, 42) + IntArray(27), decoder.encode("😀明"))
    }
}
