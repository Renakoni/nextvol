package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.iflytek.speech.ITtsListener
import com.iflytek.speech.NativeHandle
import com.iflytek.speech.libisstts
import com.iflytek.speech.libxsstts
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class IssEngine(@Suppress("UNUSED_PARAMETER") context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine, ITtsListener {
    private val parameters = voice.parameters.split(',').map(String::trim)
    private val newer = parameters.getOrNull(1) == "new"
    private val handle = NativeHandle()
    private var resourcesLoaded = false
    @Volatile private var cancelled = false
    override val sampleRate = 24000

    init {
        val speaker = parameters.first().toIntOrNull() ?: error("IssTTS package does not specify a numeric speaker")
        val version = if (newer) "40" else "30"
        val resources = File(directory, "$version/xTTSRes")
        require(resources.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && resources.isDirectory) {
            "Missing or escaped IssTTS resource directory: $version/xTTSRes"
        }
        val authorizationDirectory = File(directory, "$version/iflyrobot")
        require(authorizationDirectory.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Escaped IssTTS authorization directory" }
        authorizationDirectory.mkdirs()
        check(authorizationDirectory.isDirectory) { "Cannot create IssTTS authorization directory" }
        val machine = JSONObject().put("key", JSONArray().put("imei_info")).put("imei_info", "0")
            .put("path", File(authorizationDirectory, if (newer) "lesl_tts" else "lesl").absolutePath).toString()
        checkResult("configure authorization", if (newer) libisstts.setMachineCode(machine) else libxsstts.setMachineCode(machine))
        try {
            checkResult("initialize resources", if (newer) libisstts.initRes(resources.absolutePath + "/", 0)
                else libxsstts.initRes(resources.absolutePath + "/", 0))
            resourcesLoaded = true
            if (newer) checkResult("create", libisstts.create(handle, this)) else libxsstts.create(handle, this)
            checkResult("create", handle.err_ret)
            check(handle.native_point != 0L) { "IssTTS returned no engine handle" }
            setParameter(1289, voice.type.takeIf { it in 0..2 } ?: 0)
            setParameter(1280, speaker)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(handle.native_point != 0L) { "IssTTS engine is closed" }
        cancelled = false
        setParameter(1282, control(speed * voice.speed))
        setParameter(1283, control(voice.pitch))
        setParameter(1284, control(voice.volume))
        val audio = ByteArray(16384)
        val length = IntArray(1)
        var restarted = false
        var emitted = false
        try {
            start(text)
            while (!cancelled) {
                length[0] = 0
                val result = if (newer) libisstts.getAudioData(handle, audio, audio.size, length) else {
                    libxsstts.getAudioData(handle, audio, audio.size, length)
                    handle.err_ret
                }
                if (result == 10003 && !restarted && !emitted) {
                    restarted = true
                    start(text)
                    continue
                }
                if (cancelled) break
                if (handle.err_ret == 10004) break
                checkResult("read audio", result)
                checkResult("synthesize", handle.err_ret)
                check(length[0] in 0..audio.size && length[0] % 2 == 0) { "IssTTS returned an invalid PCM length" }
                if (length[0] > 0) {
                    emit(audio, length[0])
                    emitted = true
                }
            }
            if (cancelled) throw CancellationException("IssTTS synthesis cancelled")
        } finally {
            if (newer) libisstts.stop(handle) else libxsstts.stop(handle)
        }
    }

    override fun cancel() { cancelled = true }

    @Synchronized override fun close() {
        if (handle.native_point != 0L) {
            if (newer) libisstts.destroy(handle) else libxsstts.destroy(handle)
            handle.native_point = 0
        }
        if (resourcesLoaded) {
            resourcesLoaded = false
            if (newer) libisstts.unInitRes() else libxsstts.unInitRes()
        }
    }

    override fun onProgress(current: Int, total: Int) = Unit
    override fun onTtsMsgProc(message: Int, parameter: Int, detail: String?) = Unit

    private fun start(text: String) {
        if (newer) checkResult("start", libisstts.start(handle, text)) else libxsstts.start(handle, text)
        checkResult("start", handle.err_ret)
    }
    private fun setParameter(parameter: Int, value: Int) {
        if (newer) checkResult("set parameter $parameter", libisstts.setParam(handle, parameter, value))
        else libxsstts.setParam(handle, parameter, value)
        checkResult("set parameter $parameter", handle.err_ret)
    }
    private fun control(value: Float) = ((value * 30f - 30f) * 468f).toInt().coerceIn(-32768, 32767)
    private fun checkResult(operation: String, result: Int) {
        check(result == 0) { "IssTTS $operation failed: $result" }
    }
}
