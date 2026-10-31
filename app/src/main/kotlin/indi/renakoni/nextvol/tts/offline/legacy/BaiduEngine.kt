package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.baidu.tts.jni.ETtsError
import com.baidu.tts.jni.EmbeddedSynthesizerEngine
import com.baidu.tts.jni.EmbeddedSynthesizerEnginf
import com.baidu.tts.jni.EmbeddedSynthesizerEnginl
import com.baidu.tts.jni.EmbeddedSynthesizerEnginm
import com.baidu.tts.jni.IEmbeddedSynthesizerEngine
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Maps the package's Baidu resource parameters to its embedded JNI engine. */
internal class BaiduEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private val engine: IEmbeddedSynthesizerEngine
    private var handle = 0L
    private var domainLoaded = false
    @Volatile private var cancelled = false
    override val sampleRate: Int

    init {
        val parameters = voice.parameters.split(',').map(String::trim)
        fun parameter(index: Int) = parameters.getOrNull(index)?.takeIf(String::isNotEmpty)
        val appId = parameter(0) ?: "90013"
        val packageName = parameter(1) ?: "com.baidu.BaiduMap"
        val resources = File(directory, appId)
        fun resource(name: String): File {
            val file = File(resources, name)
            require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile) {
                "Missing or escaped Baidu resource: $appId/$name"
            }
            return file
        }
        val text = resource("${parameter(4) ?: "text"}.dat")
        val license = resource("licence.dat")
        var speech = resource("acoustic/${voice.code}.voc.dat")
        var extension = parameter(2)?.let { resource("acoustic/$it.ext.dat") }
        var speakerId = parameter(3)
        if (appId == "6082724" && extension != null) {
            val original = speech
            speech = extension
            extension = original
            if (speakerId == null) speakerId = "5"
        }
        engine = when (appId) {
            "90013" -> EmbeddedSynthesizerEnginm()
            "9294715" -> EmbeddedSynthesizerEnginf()
            "37111357" -> EmbeddedSynthesizerEnginl()
            else -> EmbeddedSynthesizerEngine()
        }
        val runtimeParameters = offlineRuntimeLicenses(context).optJSONObject("baidu")?.optJSONObject(appId)
        if (appId == "9294715" || appId == "37111357") {
            require(runtimeParameters != null) { "Baidu runtime authorization parameters are missing for $appId" }
        }
        // Keep SDK verification intact; private runtime defaults never enter source or logs.
        val authorization = engine.verifyLicense(packageName, runtimeParameters?.optInt("mode", 1) ?: 1, appId,
            runtimeParameters?.optString("device", identity) ?: identity, runtimeParameters?.optString("extra").orEmpty(),
            license.absolutePath, ByteArray(32))
        check(authorization != null && authorization.ret >= 0) {
            "Baidu could not initialize the package license for $appId: ${authorization?.ret ?: "no result"}"
        }
        val modelAuthorization = try {
            engine.modelParameters(speech.absolutePath)?.let { JSONObject(it).optString("authorize") }.orEmpty()
        } catch (_: JSONException) {
            ""
        }
        val handles = LongArray(1)
        try {
            val result = engine.bdTTSEngineInit(packageName, text.pathBytes(), speech.pathBytes(), extension?.pathBytes(),
                byteArrayOf(0), modelAuthorization, handles)
            handle = handles[0]
            checkResult("initialize", result)
            check(handle != 0L) { "Baidu initialization returned no engine handle" }
            speakerId?.toLongOrNull()?.let {
                checkCode("speaker", engine.bdTTSSetParam(handle, 9, it))
                checkCode("style", engine.bdTTSSetParam(handle, 10, it))
            }
            val domain = File(resources, "acoustic/${voice.code}.res.dat")
            if (domain.isFile) {
                checkCode("domain", engine.bdTTSDomainDataInit(resource("acoustic/${voice.code}.res.dat").pathBytes(), handle))
                domainLoaded = true
            }
            sampleRate = engine.modelSampleRate(speech.pathBytes()).takeIf { it > 0 && it <= Int.MAX_VALUE.toLong() }?.toInt()
                ?: voice.sampleRate.takeIf { it > 0 } ?: error("Baidu did not report an audio sample rate")
        } catch (failure: Throwable) {
            if (handle != 0L) {
                if (domainLoaded) engine.bdTTSDomainDataUninit(handle)
                engine.bdTTSEngineUninit(handle)
                handle = 0L
            }
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(handle != 0L) { "Baidu engine is closed" }
        cancelled = false
        var outputFailure: Throwable? = null
        engine.setOnNewDataListener { audio, _ ->
            if (!cancelled && outputFailure == null) {
                try { emit(audio, audio.size) }
                catch (failure: Throwable) { outputFailure = failure; cancelled = true }
            }
            if (cancelled) -1 else 0
        }
        try {
            checkCode("encoding", engine.bdTTSSetParam(handle, 0, 0))
            checkCode("volume", engine.bdTTSSetParamFloat(handle, 1, voice.volume * 5f))
            checkCode("speed", engine.bdTTSSetParamFloat(handle, 2, speed * voice.speed * 5f))
            checkCode("pitch", engine.bdTTSSetParamFloat(handle, 3, voice.pitch * 5f))
            val input = text.toByteArray(charset("GBK"))
            val result = engine.bdTTSSynthesis(handle, input, input.size, 0)
            outputFailure?.let { throw it }
            if (cancelled) throw CancellationException("Baidu synthesis cancelled")
            // The vendor's offline adapter also accepts these synthesis statuses.
            if (result?.ret != 530 && result?.ret != 531) checkResult("synthesize", result)
        } finally { engine.setOnNewDataListener(null) }
    }

    override fun cancel() { cancelled = true }

    @Synchronized override fun close() {
        if (handle != 0L) {
            if (domainLoaded) engine.bdTTSDomainDataUninit(handle)
            engine.bdTTSEngineUninit(handle)
            handle = 0L
        }
    }

    private fun File.pathBytes() = absolutePath.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    private fun checkCode(operation: String, result: Int) {
        check(result == 0) { "Baidu $operation failed: $result" }
    }
    private fun checkResult(operation: String, result: ETtsError?) {
        check(result != null && result.ret == 0) { "Baidu $operation failed (${result?.ret}): ${result?.message.orEmpty()}" }
    }

    companion object {
        private val identity = UUID.randomUUID().toString()
    }
}
