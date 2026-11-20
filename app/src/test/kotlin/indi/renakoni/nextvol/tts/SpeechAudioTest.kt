package indi.renakoni.nextvol.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechAudioTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun truncatedEmptyAndIncorrectPcmOutputIsRejectedBeforePlayback() {
        val valid = wav()
        val invalid = listOf(
            ByteArray(0), valid.copyOf(43), valid.copyOf(valid.size - 1),
            wav(samples = 0), wav(blockSize = 1), wav(format = 2), wav(sampleRate = 0),
            valid.clone().also { it[0] = 0 },
            valid.clone().also { it[40] = 100 },
        )
        for (bytes in invalid) {
            val file = files.newFile()
            file.writeBytes(bytes)
            assertEquals(SpeechError.InvalidAudio, assertThrows(SpeechException::class.java) {
                validateSpeechWav(file)
            }.error)
        }
    }

    private fun wav(samples: Int = 16, blockSize: Short = 2, format: Short = 1, sampleRate: Int = 22050): ByteArray =
        ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(format); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
            putShort(blockSize); putShort(16); put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(100) }
        }.array()
}
