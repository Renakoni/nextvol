package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.sogou.speech.proxy.NativeProxy
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File

/** Sogou's internal service is a native library handle, not an Android TTS service. */
internal class SogouEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private val proxy = NativeProxy()
    private val library = File(context.applicationInfo.nativeLibraryDir, "libsgtts.so").absolutePath
    private var handle = 0L
    @Volatile private var cancelled = false
    override val sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 16000

    init {
        fun resource(name: String): File {
            val file = File(directory, name)
            require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile) {
                "Missing or escaped Sogou resource: $name"
            }
            return file
        }
        val frontModel = resource("dict_zh.dat")
        val backendModel = resource("acoustic/${voice.code}.bin")
        val englishDictionary = resource("engdict.dat")
        loadLibraries()
        val configuration = JSONObject().put("enable_log", false)
        handle = proxy.native_searchService(library, configuration.toString())
        check(handle != 0L) { "Sogou could not load its native service: $library" }
        try {
            configuration.put("language", "zh-cmn-Hans-CN")
                .put("frontModel", frontModel.absolutePath)
                .put("backendModel", backendModel.absolutePath)
                .put("engDict", englishDictionary.absolutePath)
            val result = proxy.native_connect(handle, 1, configuration.toString())
            check(result == 0) { "Sogou initialization failed: $result" }
        } catch (failure: Throwable) {
            proxy.native_destroyService(handle, library)
            handle = 0L
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(handle != 0L) { "Sogou engine is closed" }
        cancelled = false
        val configuration = JSONObject()
            .put("speaking_rate", speed * voice.speed)
            .put("volume", voice.volume)
            .put("do_split", false)
            .put("backend_thread_num", 1)
        val request = JSONObject().put("config", configuration).put("text", text)
        val result = proxy.native_write(handle, request.toString())
        check(result == 0) { "Sogou synthesis could not start: $result" }
        val status = IntArray(1)
        var outputFailure: Throwable? = null
        // The runtime must finish/drain its request before its model resources can be released.
        while (true) {
            val pcm = proxy.native_read(handle, status)
            if (status[0] <= 0) {
                check(status[0] == 0) { "Sogou synthesis failed (${status[0]}): ${pcm?.toString(Charsets.UTF_8).orEmpty()}" }
                break
            }
            check(pcm != null) { "Sogou returned no audio for status ${status[0]}" }
            if (!cancelled && outputFailure == null) {
                try { emit(pcm, pcm.size) }
                catch (failure: Throwable) { outputFailure = failure; cancelled = true }
            }
        }
        outputFailure?.let { throw it }
        if (cancelled) throw CancellationException("Sogou synthesis cancelled")
    }

    override fun cancel() { cancelled = true }

    @Synchronized override fun close() {
        if (handle != 0L) {
            proxy.native_destroyService(handle, library)
            handle = 0L
        }
    }

    companion object {
        private val libraries by lazy {
            listOf("omp", "evalite_uapi", "sgsplit", "sgtts", "sgproxy_lib", "sgtts_lib").forEach(System::loadLibrary)
        }
        private fun loadLibraries() = libraries
    }
}
