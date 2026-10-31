package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.xiaomi.ai.local.JniTts
import com.xiaomi.ai.local.TtsOptions
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import indi.renakoni.nextvol.tts.speechSegments
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal class XiaomiEngine(@Suppress("UNUSED_PARAMETER") context: Context, directory: File, voice: OfflineVoiceConfig) : OfflineEngine {
    private var handle = 0L
    @Volatile private var cancelled = false
    override val sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 16000

    init {
        val model = File(directory, voice.code)
        require(model.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && model.isDirectory) {
            "Missing or escaped Xiaomi voice directory"
        }
        val options = TtsOptions(voice.parameters.toInt())
        val result = JniTts.init(model.canonicalPath)
        check(result == 0 || result == 107) { "Xiaomi initialization failed: $result" }
        try {
            handle = JniTts.createTtsInstance()
            check(handle != 0L && JniTts.createEngine(handle, options)) { "Xiaomi engine could not be created" }
        } catch (failure: Throwable) {
            if (handle != 0L) JniTts.deleteTtsInstance(handle)
            handle = 0L
            JniTts.release()
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(handle != 0L) { "Xiaomi engine is closed" }
        cancelled = false
        val outputFailure = AtomicReference<Throwable?>()
        // The original adapter bounds Xiaomi requests to 250 UTF-16 units.
        for (segment in speechSegments(text, maxLength = 250, firstLength = 250)) {
            if (cancelled) throw CancellationException("Xiaomi synthesis cancelled")
            val success = JniTts.synthesis(handle, segment.text) { bytes, _ ->
                if (cancelled) 1 else try {
                    emit(bytes, bytes.size)
                    0
                } catch (failure: Throwable) {
                    outputFailure.compareAndSet(null, failure)
                    cancelled = true
                    1
                }
            }
            outputFailure.get()?.let { throw it }
            if (cancelled) throw CancellationException("Xiaomi synthesis cancelled")
            check(success) { "Xiaomi synthesis failed" }
        }
    }

    override fun cancel() { cancelled = true }

    @Synchronized override fun close() {
        if (handle != 0L) {
            val current = handle
            handle = 0L
            try { JniTts.resetEngine(current) }
            finally {
                try { JniTts.deleteTtsInstance(current) }
                finally { JniTts.release() }
            }
        }
    }
}
