package hnovel.speech

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OnlineSpeechAudioTest {
    @Test fun segmentedWavKeepsAllPcmFramesAndOneUpdatedHeader() {
        fun wave(samples: ShortArray, rate: Int = 24000): ByteArray {
            val buffer = ByteBuffer.allocate(54 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVE".toByteArray())
            buffer.put("JUNK".toByteArray()).putInt(1).put(7).put(0)
            buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            buffer.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            buffer.put("data".toByteArray()).putInt(samples.size * 2)
            samples.forEach { buffer.putShort(it) }
            return buffer.array()
        }
        val first = wave(shortArrayOf(1, -2, 3))
        val second = wave(shortArrayOf(4, -5))
        val merged = onlineSpeechBytes(listOf(first, second))
        val buffer = ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(54, merged.size)
        assertEquals(46, buffer.getInt(4))
        assertEquals("data", String(merged, 36, 4))
        assertEquals(10, buffer.getInt(40))
        assertArrayEquals(shortArrayOf(1, -2, 3, 4, -5), ShortArray(5) { buffer.getShort(44 + it * 2) })
        val mismatch = assertThrows(HttpSpeechException::class.java) {
            onlineSpeechBytes(listOf(first, wave(shortArrayOf(6), 16000)))
        }
        assertEquals(HttpSpeechError.InvalidAudio, mismatch.error)
    }
}
