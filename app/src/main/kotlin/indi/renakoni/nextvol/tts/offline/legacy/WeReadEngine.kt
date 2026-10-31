package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.qq.wx.offlinevoice.synthesizer.SynthesizerNative
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import java.io.File

internal class WeReadEngine(@Suppress("UNUSED_PARAMETER") context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private val engine = SynthesizerNative()
    @Volatile private var cancelled = false
    private var closed = false
    override val sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 16000

    init {
        libraries
        val result = engine.init(directory.canonicalPath.toByteArray(Charsets.UTF_8))
        if (result != 0) {
            engine.destroy()
            error("WeRead initialization failed: $result")
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(!closed) { "WeRead engine is closed" }
        cancelled = false
        engine.setVoiceName(voice.code)
        engine.setSpeed(speed * voice.speed)
        engine.setVolume(voice.volume)
        try {
            val input = text.toByteArray(Charsets.UTF_8)
            var prepared = engine.prepareUTF8(input)
            repeat(2) {
                if (prepared != 0 && !cancelled) {
                    engine.setVoiceName(voice.code)
                    prepared = engine.prepareUTF8(input)
                }
            }
            check(prepared == 0) { "WeRead preparation failed: $prepared" }
            val samples = ShortArray(64000)
            val length = IntArray(1)
            while (!cancelled) {
                length[0] = 0
                val result = engine.synthesize(samples, samples.size, length, 1)
                check(result != -1) { "WeRead synthesis failed: $result" }
                val count = length[0]
                check(count in 0..samples.size) { "WeRead returned an invalid audio length: $count" }
                if (count == 0) break
                val bytes = ByteArray(count * 2)
                repeat(count) { index ->
                    bytes[index * 2] = samples[index].toByte()
                    bytes[index * 2 + 1] = (samples[index].toInt() shr 8).toByte()
                }
                if (!cancelled) emit(bytes, bytes.size)
            }
            if (cancelled) throw CancellationException("WeRead synthesis cancelled")
        } finally { engine.reset() }
    }

    override fun cancel() { cancelled = true }

    @Synchronized override fun close() {
        if (!closed) {
            closed = true
            engine.destroy()
        }
    }

    companion object {
        private val libraries by lazy { listOf("weread-tts", "hwTTS").forEach(System::loadLibrary) }
    }
}
