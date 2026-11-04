package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.bytedance.speech.speechengine.bridge.SpeechBridgeCallback
import com.bytedance.speech.speechengine.bridge.SpeechEngineBridge
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

/** MultiTTS n4.a's offline-only SpeechEngine route, without an SDK audio player. */
internal class BytedanceEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine, SpeechBridgeCallback {
    private val bridge = SpeechEngineBridge()
    private var handle = 0L
    @Volatile private var cancelled = false
    @Volatile private var completion: CountDownLatch? = null
    @Volatile private var output: ((ByteArray, Int) -> Unit)? = null
    @Volatile private var failure: Throwable? = null
    override val sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 24000

    init {
        BytedanceEnvironment.prepare(context)
        libraries
        val code = voice.code.substringBefore('-') + "_streaming"
        val root = if (File(directory, code).isDirectory) directory else File(directory, "midu")
        val model = File(root, code).takeIf { it.isDirectory } ?: File(directory, "fanqie/$code")
        try {
            handle = bridge.createEngineToNative(this)
            check(handle != 0L) { "ByteDance could not create an engine" }
            integer("tts_work_mode", 2048)
            string("engine_name", "tts")
            string("authenticate_type", "pre_bind")
            string("license_directory", root.absolutePath + "/")
            string("tts_scenario", "novel")
            boolean("prevent_player_creation", true)
            string("tts_text_type", "plain")
            boolean("tts_enable_progress", true)
            string("tts_off_resource_path", JSONObject()
                .put("base", File(root, "zh-cn").absolutePath)
                .put("voice", JSONArray().put(JSONObject().put(code, model.absolutePath))).toString())
            integer("tts_rate", sampleRate)
            integer("tts_pitch", 10)
            boolean("use_alog", false)
            string("log_level", "WARN")
            boolean("tts_enable_player", false)
            boolean("tts_enable_dump", false)
            boolean("enable_ws_reconnect", false)
            boolean("tts_limit_cpu_usage", false)
            integer("tts_data_callback_mode", 2)
            string("tracking_data_path", context.filesDir.absolutePath)
            string("settings_file_dir", context.filesDir.absolutePath)
            val result = bridge.initEngineToNative(handle, null)
            check(result == 0) { "ByteDance offline initialization failed: $result" }
            string("tts_voice_offline", "other")
            string("tts_voice_type_offline", code)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(handle != 0L)
        if (cancelled) throw CancellationException("ByteDance synthesis cancelled")
        val done = CountDownLatch(1)
        failure = null
        output = emit
        completion = done
        try {
            // Cancellation may have arrived just before the latch was installed.
            if (cancelled) throw CancellationException("ByteDance synthesis cancelled")
            // Like MultiTTS, allow START to report "already working" between texts.
            bridge.sendDirectiveToNative(handle, 1000, "")
            integer("tts_speed", (speed * voice.speed * 18f).toInt())
            integer("tts_volume", (voice.volume * 15f).toInt())
            string("tts_text", text)
            directive(1400)
            done.await()
            failure?.let { throw it }
            if (cancelled) throw CancellationException("ByteDance synthesis cancelled")
        } finally {
            output = null
            completion = null
            if (failure != null) bridge.sendDirectiveToNative(handle, 2001, "")
        }
    }

    override fun onSpeechMessage(type: Int, data: ByteArray?, length: Int) {
        if (cancelled || output == null) return
        when (type) {
            1400 -> if (data != null && length > 0) {
                try { output?.invoke(data, length) }
                catch (error: Throwable) { failure = error; completion?.countDown() }
            }
            1409 -> completion?.countDown()
            1003 -> {
                failure = IllegalStateException("ByteDance: " + data?.toString(Charsets.UTF_8).orEmpty())
                completion?.countDown()
            }
        }
    }

    override fun cancel() {
        cancelled = true
        output = null
        try { if (handle != 0L) bridge.sendDirectiveToNative(handle, 2001, "") }
        finally { completion?.countDown() }
    }

    override fun close() {
        if (handle != 0L) {
            try { cancel() } finally {
                bridge.destroyEngineToNative(handle)
                handle = 0L
            }
        }
    }

    private fun string(key: String, value: String) = bridge.setOptionStringToNative(handle, key, value)
    private fun integer(key: String, value: Int) = bridge.setOptionIntToNative(handle, key, value)
    private fun boolean(key: String, value: Boolean) = bridge.setOptionBooleanToNative(handle, key, value)
    private fun directive(value: Int) {
        val result = bridge.sendDirectiveToNative(handle, value, "")
        check(result == 0) { "ByteDance directive $value failed: $result" }
    }

    companion object {
        private val libraries by lazy { System.loadLibrary("speechengine") }
    }
}
