package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.tts.audio.PcmAudioProcessor
import indi.renakoni.nextvol.tts.audio.processSpeechAudio
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal suspend fun decodeSovitsReference(file: File, onPcm: (ByteArray) -> Unit = {}): Int {
    var byteCount = 0L
    var audible = false
    val audio = processSpeechAudio(file, null, minimumSampleRate = 1) { rate, bytes ->
        byteCount += bytes.size
        require(byteCount <= 2L * rate * 30) { "Reference audio exceeds 30 seconds" }
        if (!audible) audible = bytes.any { it != 0.toByte() }
        onPcm(bytes)
    }
    require(audible) { "Silent reference audio" }
    return audio.sampleRate
}

internal suspend fun readSovitsReference(file: File): Pair<FloatArray, FloatArray> {
    val pcm = ByteArrayOutputStream()
    val rate = decodeSovitsReference(file, pcm::write)
    fun resample(samples: ShortArray, from: Int, to: Int) = PcmAudioProcessor(from, to).use {
        it.process(samples) + it.finish()
    }
    val input = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val mono = ShortArray(input.remaining()).also { input.get(it) }
    val resampled = resample(mono, rate, 32000)
    // Both V2 Pro encoders see the same reference with a 300 ms silent tail.
    val pcm32 = resampled.copyOf(resampled.size + 9600)
    val pcm16 = resample(pcm32, 32000, 16000)
    return FloatArray(pcm32.size) { pcm32[it] / 32768f } to FloatArray(pcm16.size) { pcm16[it] / 32768f }
}
