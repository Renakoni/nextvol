package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.iflytek.msc.MSC
import com.iflytek.msc.MSCSessionInfo
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

internal class MscEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private val voiceName: String
    private val resourcePaths: String
    private val legacy: Boolean
    private val textCharset: Charset
    private var initialized = false
    @Volatile private var cancelled = false
    override val sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 16000

    init {
        require(voice.type == 0) { "MscTTS voice is not an offline voice" }
        val parameters = voice.parameters.split(',').map(String::trim)
        legacy = parameters.getOrNull(1) == "xzhd"
        textCharset = if (legacy) Charset.forName("GB2312") else Charsets.UTF_8
        voiceName = parameters.getOrNull(2)?.takeIf(String::isNotEmpty) ?: voice.code
        val appId = parameters.firstOrNull()?.takeIf(String::isNotEmpty)
            ?: directory.listFiles()?.filter { it.isDirectory && File(it, "$voiceName.jet").isFile }
                ?.singleOrNull()?.name
            ?: error("MscTTS package does not identify its application resource directory")
        fun resource(name: String): File {
            val file = File(directory, "$appId/$name")
            require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile && file.length() > 0) {
                "Missing or escaped MscTTS resource: $appId/$name"
            }
            require(file.absolutePath.none { it == ',' || it == ';' || it == '|' }) { "Invalid MscTTS resource path" }
            return file
        }
        resourcePaths = listOf(resource("common.jet"), resource("$voiceName.jet"))
            .joinToString(";") { "fo|${it.absolutePath}|0|${it.length()}" }
        val workDirectory = File(context.filesDir, "offline-engine-cache/msc").apply { mkdirs() }
        check(workDirectory.isDirectory) { "Cannot create MscTTS working directory" }
        val login = linkedMapOf("appid" to appId, "work_dir" to workDirectory.absolutePath, "auth" to "1")
        parameters.getOrNull(1)?.takeIf(String::isNotEmpty)?.let { login["downfrom"] = it }
        // Authentication remains with the SDK and package identity; no embedded application key.
        checkResult("initialize", MSC.QMSPLogin(null, null, configuration(login)))
        initialized = true
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(initialized) { "MscTTS engine is closed" }
        cancelled = false
        val status = MSCSessionInfo()
        val settings = linkedMapOf("tts_res_path" to resourcePaths,
            "speed" to control(speed * voice.speed), "pitch" to control(voice.pitch), "volume" to control(voice.volume))
        if (legacy) {
            // The xzhd resource format uses MSC's local engine and its older session protocol.
            settings.putAll(mapOf("engine_type" to "local", "vcn" to voiceName,
                "text_encoding" to "GB2312", "aue" to if (sampleRate == 16000) "speex-wb" else "speex",
                "auf" to "audio/L16;rate=$sampleRate", "net_type" to "wifi", "net_subtype" to "wifi",
                "speed_increase" to "1", "stream_type" to "3", "tts_data_notify" to "1",
                "request_audio_focus" to "false", "ssm" to "1"))
        } else {
            val region = try { JSONObject(voice.engineParameters.ifBlank { "{}" }).optString("region", "sh").ifBlank { "sh" } }
                catch (_: JSONException) { "sh" }
            settings.putAll(mapOf("engine_type" to "purextts", "voice_name" to voiceName,
                "server_url" to "https://$region.voicecloud.cn/msp.do",
                "sample_rate" to sampleRate.toString(), "text_encoding" to "UTF8",
                "aue" to "raw", "ttp" to "text", "rdn" to "0"))
        }
        val session = MSC.QTTSSessionBegin(configuration(settings), status)
        try {
            checkResult("begin session", status.errorcode)
            check(session != null) { "MscTTS returned no synthesis session" }
            checkResult("submit text", MSC.QTTSTextPut(session, text.toByteArray(textCharset)))
            status.sesstatus = 0
            while (!cancelled && status.sesstatus != 2) {
                val audio = MSC.QTTSAudioGet(session, status)
                checkResult("synthesize", status.errorcode)
                if (!cancelled && audio != null && audio.isNotEmpty()) emit(audio, audio.size)
            }
            if (cancelled) throw CancellationException("MscTTS synthesis cancelled")
        } finally {
            if (session != null) MSC.QTTSSessionEnd(session, null)
        }
    }

    override fun cancel() { cancelled = true }

    @Synchronized override fun close() {
        if (initialized) {
            initialized = false
            MSC.QMSPLogOut()
        }
    }

    private fun control(value: Float) = (value * 50f).toInt().coerceIn(0, 100).toString()
    private fun configuration(values: Map<String, String>): ByteArray {
        require(values.values.none { it.contains(',') || it.contains('\u0000') }) { "Invalid MscTTS parameter" }
        return (values.entries.joinToString(",") { "${it.key}=${it.value}" } + "\u0000").toByteArray(Charsets.UTF_8)
    }
    private fun checkResult(operation: String, result: Int) {
        check(result == 0) { "MscTTS $operation failed: $result" }
    }
}
