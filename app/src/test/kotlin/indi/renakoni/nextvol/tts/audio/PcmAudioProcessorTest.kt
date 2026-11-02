package indi.renakoni.nextvol.tts.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PcmAudioProcessorTest {
    @Test fun zeroSpeedCannotEnterSonicButZeroVolumeRemainsValid() {
        assertThrows(AudioProcessingException::class.java) { PcmAudioProcessor(16000, 16000, speed = 0f) }
        PcmAudioProcessor(16000, 16000, volume = 0f).use {
            val output = it.process(ShortArray(16000) { 1000 }) + it.finish()
            assertArrayEquals(ShortArray(16000), output)
        }
    }

    @Test fun defaultsKeepPcmAndQuietBlocksShortenOnlyWhenEnabled() {
        val quiet = shortArrayOf(-512, -1, 0, 512, 4, -3, 1, 0)
        PcmAudioProcessor(16000, 16000).use {
            assertArrayEquals(quiet, it.process(quiet))
            assertTrue(it.finish().isEmpty())
        }
        PcmAudioProcessor(16000, 16000, checkSilence = true).use {
            assertArrayEquals(ShortArray(2), it.process(quiet))
            assertEquals(8, it.lastUncompressedFrames)
            val audible = quiet.copyOf().apply { this[7] = 513 }
            assertArrayEquals(audible, it.process(audible))
            assertArrayEquals(quiet, it.process(quiet, intentionalPause = true))
            assertEquals(8, it.lastUncompressedFrames)
            assertTrue(it.finish().isEmpty())
        }
    }

    @Test fun largeImportedVolumeSaturatesWithoutReversingPolarity() {
        PcmAudioProcessor(16000, 16000, volume = 64f).use {
            val output = it.process(ShortArray(16000) { 10000 }) + it.finish()
            assertArrayEquals(ShortArray(16000) { 32767 }, output)
        }
    }

    @Test fun sonicAppliesRatePitchVolumeAndResamplingWithoutLosingStreamTail() {
        val input = ShortArray(32000) { (sin(2 * PI * 400 * it / 16000) * 10000).toInt().toShort() }
        val output = mutableListOf<Short>()
        PcmAudioProcessor(16000, 24000, speed = 1.25f, pitch = 1.2f, volume = 0.5f).use { processor ->
            input.asList().chunked(1024).forEach { output += processor.process(it.toShortArray()).asList() }
            val tail = processor.finish()
            assertTrue(tail.isNotEmpty())
            output += tail.asList()
        }
        assertTrue(abs(output.size - 38400) < 1000)
        val middle = output.subList(6000, output.size - 6000)
        val crossings = middle.zipWithNext().count { (left, right) -> left <= 0 && right > 0 }
        val frequency = crossings * 24000.0 / middle.size
        assertEquals(480.0, frequency, 25.0)
        val rms = sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
        assertEquals(10000 / sqrt(2.0) * 0.5, rms, 600.0)
    }

    @Test fun closeDiscardsBufferedAudioAndPreventsFurtherOutput() {
        val processor = PcmAudioProcessor(16000, 24000, pitch = 1.2f)
        processor.process(ShortArray(8) { 1000 })
        processor.close()
        processor.close()
        assertThrows(IllegalStateException::class.java) { processor.finish() }
    }
}
