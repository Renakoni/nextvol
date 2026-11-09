package hnovel.speech

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OnlineSpeechAudioTest {
    @Test fun successfulLongSdkWaveIsReusedAndSegmentedPcmIsPreserved() = runBlocking {
        val pcm = ByteArray(24000 * 2 * 44) { (it % 251).toByte() }
        val wave = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
            put("data".toByteArray()).putInt(pcm.size).put(pcm)
        }.array()
        val client = OnlineSpeechClient(buildJsonObject { putJsonObject("engine") { put("code", "azure") } },
            { error("Unexpected HTTP request") }, createSdkBackend = { _, _, _ -> object : OnlineSpeechBackend {
                override suspend fun synthesize(text: String, rate: Float, volume: Float) = wave
            } })
        assertSame(wave, client.synthesize("abcd", 1f, 1f, 4, null))
        val merged = client.synthesize("abcdefgh", 1f, 1f, 4, null)
        val buffer = ByteBuffer.wrap(merged).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + pcm.size * 2, merged.size)
        assertEquals(merged.size - 8, buffer.getInt(4))
        assertEquals(pcm.size * 2, buffer.getInt(40))
        assertArrayEquals(pcm, merged.copyOfRange(44, 44 + pcm.size))
        assertArrayEquals(pcm, merged.copyOfRange(44 + pcm.size, merged.size))
    }

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
