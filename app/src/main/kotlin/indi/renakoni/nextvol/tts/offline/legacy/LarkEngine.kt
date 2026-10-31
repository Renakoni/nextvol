package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.iflytek.tts.TtsService.AudioData
import com.iflytek.tts.TtsService.Tts
import com.tencent.btts.jni.SynthesizerNative
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal class LarkEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val directory: File,
    private val voice: OfflineVoiceConfig,
) : OfflineEngine {
    private val modern = voice.parameters == "1"
    private var engine: SynthesizerNative? = null
    private var callback: com.tencent.btts.jni.a? = null
    private var speaker = ""
    private var closed = false
    @Volatile private var cancelled = false
    @Volatile private var request = ""
    @Volatile private var complete: CountDownLatch? = null
    @Volatile private var output: ((ByteArray, Int) -> Unit)? = null
    private val failure = AtomicReference<Throwable?>()
    override val sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 16000

    private fun resource(name: String): File {
        val file = File(directory, name)
        require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile) {
            "Missing or escaped Lark resource: $name"
        }
        return file
    }

    private fun initialize() {
        if (modern) {
            val resources = arrayOf(resource("larktrf.trf").absolutePath, resource("libspeaker_xiaowu.so").absolutePath)
            val model = resource("acoustic/${voice.code}.trf")
            val current = SynthesizerNative()
            current.setDebugLevel(5)
            val listener = object : com.tencent.btts.jni.a() {
                override fun onCallback(pcm: ByteArray, first: Boolean, last: Boolean, id: String): Int {
                    if (id == request) emit(pcm, pcm.size)
                    return 0
                }
                override fun onError(code: Int, message: String?, id: String): Int {
                    if (id == request) {
                        if (code != 0 && code != 30) failure.compareAndSet(null, IllegalStateException("Lark synthesis failed ($code): $message"))
                        complete?.countDown()
                    }
                    return 0
                }
            }
            try {
                callback = listener
                checkStatus("initialize", current.initializeForFiles(resources, listener))
                checkStatus("load voice", current.appendSpeakersForFiles(arrayOf(model.absolutePath)))
                speaker = current.availableSpeaker?.firstOrNull { voice.code.startsWith(it) }
                    ?: error("Lark did not expose the installed voice: ${voice.code}")
                engine = current
            } catch (failure: Throwable) {
                current.destroy()
                callback = null
                throw failure
            }
        } else {
            val resources = arrayOf(resource("libTtsVoiceCommon.so").absolutePath, resource("acoustic/${voice.code}.irf").absolutePath)
            Tts.JniStop()
            Tts.JniSetParam(256, 1)
            Tts.JniSetParam(1280, 3)
            Tts.JniSetParam(1284, 0)
            Tts.JniSetParam(1282, 0)
            checkStatus("initialize", Tts.JniCreate(resources))
            AudioData.setDataArriveListener { audio, length -> emit(audio, length) }
        }
    }

    override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) = synchronized(runtime) {
        check(!closed) { "Lark engine is closed" }
        cancelled = false
        failure.set(null)
        output = emit
        complete = CountDownLatch(1)
        request = UUID.randomUUID().toString()
        try {
            synchronized(lifecycle) {
                if (active !== this) {
                    active?.releaseNative()
                    active = null
                    initialize()
                    active = this
                }
            }
            if (cancelled) throw CancellationException("Lark synthesis cancelled")
            if (modern) {
                val current = checkNotNull(engine)
                val speakerId = speaker.takeIf { it.startsWith("0x") }?.substring(2)?.toIntOrNull(16)
                val rate = (speed * voice.speed * 50f).toInt()
                val volume = (voice.volume * 50f).toInt()
                val result = if (speakerId == null) current.synthesizeByName(text, 0, speaker, rate, volume, request)
                    else current.synthesize(text, 0, speakerId, rate, volume, request)
                checkStatus("start synthesis", result)
                complete?.await()
            } else {
                Tts.JniSetParam(1282, (speed * voice.speed * 32700f - 32768f).toInt().coerceIn(-32768, 32767))
                Tts.JniSetParam(1284, (voice.volume * 31350f - 32768f).toInt().coerceIn(-32768, 30000))
                // JniSpeak runs ivTTS_Run synchronously; JniIsPlaying becomes 2 after completion.
                val result = Tts.JniSpeak(text)
                if (!cancelled && failure.get() == null) checkStatus("synthesize", result)
            }
            failure.get()?.let { throw it }
            if (cancelled) throw CancellationException("Lark synthesis cancelled")
        } finally {
            if (cancelled || failure.get() != null) {
                if (modern) engine?.cancel(request) else Tts.JniStop()
            }
            output = null
            complete = null
            request = ""
        }
    }

    private fun emit(audio: ByteArray, length: Int) {
        if (cancelled || failure.get() != null) return
        try { output?.invoke(audio, length) }
        catch (problem: Throwable) {
            failure.compareAndSet(null, problem)
            cancelled = true
            complete?.countDown()
        }
    }

    override fun cancel() {
        cancelled = true
        synchronized(lifecycle) {
            if (active === this) {
                if (modern) request.takeIf(String::isNotEmpty)?.let { engine?.cancel(it) }
                else Tts.JniStop()
            }
        }
        complete?.countDown()
    }

    override fun close() {
        cancel()
        synchronized(runtime) {
            synchronized(lifecycle) {
                if (active === this) {
                    releaseNative()
                    active = null
                }
            }
            closed = true
        }
    }

    private fun releaseNative() {
        if (modern) {
            engine?.destroy()
            engine = null
            callback = null
        } else {
            Tts.JniStop()
            AudioData.setDataArriveListener(null)
            Tts.JniDestroy()
        }
    }

    private fun checkStatus(operation: String, result: Int) {
        check(result == 0) { "Lark $operation failed: $result" }
    }

    companion object {
        private val runtime = Any()
        private val lifecycle = Any()
        @Volatile private var active: LarkEngine? = null
    }
}
