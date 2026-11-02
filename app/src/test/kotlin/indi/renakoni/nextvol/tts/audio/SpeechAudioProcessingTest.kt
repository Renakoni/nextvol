package indi.renakoni.nextvol.tts.audio

import android.media.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechAudioProcessingTest {
    @Test fun supportedPcmWidthsKeepSignedSamplesAndAverageChannels() {
        fun buffer(vararg bytes: Int) = ByteBuffer.wrap(bytes.map(Int::toByte).toByteArray())
        assertArrayEquals(shortArrayOf(-32768, 0, 32512),
            monoSpeechPcm(buffer(0, 128, 255), 1, AudioFormat.ENCODING_PCM_8BIT))
        assertArrayEquals(shortArrayOf(-16384, 16384), monoSpeechPcm(
            buffer(0, 128, 0, 0, 0, 64, 0, 64), 2, AudioFormat.ENCODING_PCM_16BIT))
        assertArrayEquals(shortArrayOf(-16384, 16384), monoSpeechPcm(
            buffer(0, 0, 128, 0, 0, 0, 0, 0, 64, 0, 0, 64), 2, AudioFormat.ENCODING_PCM_24BIT_PACKED))
        val integers = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Int.MIN_VALUE).putInt(0).putInt(0x40000000).putInt(0x40000000)
        integers.flip()
        assertArrayEquals(shortArrayOf(-16384, 16384), monoSpeechPcm(integers, 2, AudioFormat.ENCODING_PCM_32BIT))
        val floats = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-1f).putFloat(0f).putFloat(2f).putFloat(2f)
        floats.flip()
        assertArrayEquals(shortArrayOf(-16384, 32767), monoSpeechPcm(floats, 2, AudioFormat.ENCODING_PCM_FLOAT))
    }

    @Test fun timingUsesSilenceBlockAnchorsInsteadOfCompressingEveryWordByTheWholeFileRatio() {
        // The middle second becomes 250 ms; the two voiced seconds keep their duration.
        val audio = ProcessedSpeechAudio(16000, 16000, 48000, listOf(
            SpeechAudioFrameAnchor(0, 0), SpeechAudioFrameAnchor(16000, 16000),
            SpeechAudioFrameAnchor(32000, 20000), SpeechAudioFrameAnchor(48000, 36000)))
        assertEquals(500L, audio.mapTimeMs(500))
        assertEquals(1125L, audio.mapTimeMs(1500))
        assertEquals(1750L, audio.mapTimeMs(2500))
        assertEquals(2250L, audio.durationMs)
    }
}
