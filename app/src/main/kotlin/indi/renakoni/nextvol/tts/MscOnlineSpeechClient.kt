package indi.renakoni.nextvol.tts

import android.os.Build
import com.iflytek.msc.MSC
import com.iflytek.msc.MSCSessionInfo
import hnovel.speech.HttpSpeechError
import hnovel.speech.HttpSpeechException
import hnovel.speech.OnlineSpeechBackend
import hnovel.speech.multiTtsControl
import hnovel.speech.resolveOfflineParameters
import hnovel.speech.speechPcmWave
import indi.renakoni.nextvol.tts.offline.legacySpeechPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** MultiTTS d4.c/d4.d: the same MSC runtime owns cloud and ptts sessions. */
internal class MscOnlineSpeechClient(metadata: JsonObject) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject
    private val parameters = resolveOfflineParameters(voice.text("param"), voice.text("extendUI")).split(',')
        .map(String::trim).takeWhile(String::isNotEmpty)
    private val appId = parameters.getOrNull(0) ?: "5ef02a7c"
    private val downFrom = parameters.getOrNull(1) ?: "77010010"
    private val name = parameters.getOrNull(2) ?: voice.text("code")
    private val type = voice["type"]?.jsonPrimitive?.intOrNull ?: 1
    private val sampleRate = voice["sampleRate"]?.jsonPrimitive?.intOrNull ?: 16000
    private val region = metadata.getValue("engine").jsonObject.text("param").takeIf(String::isNotBlank)
        ?.let { Json.parseToJsonElement(it).jsonObject.text("region") }?.ifEmpty { null } ?: "sh"
    private val cancelled = AtomicBoolean()

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray = legacySpeechPermit.withPermit {
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            fun checkActive() {
                coroutine.ensureActive()
                if (cancelled.get()) throw CancellationException("MSC synthesis cancelled")
            }
            checkActive()
            val login = linkedMapOf("ver" to "2.7.3195", "downfrom" to downFrom,
                "net_type" to "WIFI", "net_subtype" to "wifi", "vad_enable" to "false", "auth" to "1",
                "dvc" to "null|null", "imei" to "null", "sid" to "null", "tel_number" to "null", "appid" to appId,
                "osmanufacturer" to Build.MANUFACTURER, "osmodel" to Build.MODEL, "osproduct" to Build.PRODUCT,
                "osversion" to Build.VERSION.RELEASE, "ossystem" to "Android",
                "server_url" to "https://dz-x2.xf-yun.com:1028/msp.do", "timeout" to "10000")
            checkResult(MSC.QMSPLogin(null, null, configuration(login)))
            try {
                checkActive()
                val prefix = when (type) { 1 -> "x"; 2 -> "x2"; else -> "" }
                val cloudName = if (prefix.isNotEmpty() && name.getOrNull(1) != '_' && name.getOrNull(2) != '_')
                    "${prefix}_$name" else name
                val settings = linkedMapOf(
                    (if (type == 5) "ent" else "engine_type") to (if (type == 5) "ptts" else "cloud"),
                    "voice_name" to cloudName, "speed" to multiTtsControl(rate).toString(),
                    "volume" to multiTtsControl(volume).toString(), "sample_rate" to sampleRate.toString(),
                    "rdn" to "0", "ttp" to "text", "server_url" to "https://$region.voicecloud.cn/msp.do",
                    "aue" to "raw", "text_encoding" to "UTF8")
                val status = MSCSessionInfo()
                val session = MSC.QTTSSessionBegin(configuration(settings), status)
                try {
                    checkResult(status.errorcode)
                    if (session == null) throw HttpSpeechException(HttpSpeechError.Network)
                    checkResult(MSC.QTTSTextPut(session, text.toByteArray(Charsets.UTF_8)))
                    val audio = ByteArrayOutputStream()
                    while (status.sesstatus != 2) {
                        checkActive()
                        val bytes = MSC.QTTSAudioGet(session, status)
                        checkResult(status.errorcode)
                        checkActive()
                        if (bytes != null) {
                            if (audio.size() + bytes.size > 8 * 1024 * 1024 - 44)
                                throw HttpSpeechException(HttpSpeechError.TooLarge)
                            audio.write(bytes)
                        }
                    }
                    val pcm = audio.toByteArray()
                    if (pcm.isEmpty()) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
                    speechPcmWave(pcm, sampleRate)
                } finally { if (session != null) MSC.QTTSSessionEnd(session, null) }
            } finally { MSC.QMSPLogOut() }
        }
    }

    override fun cancel() { cancelled.set(true) }
    override fun close() = cancel()

    private fun configuration(values: Map<String, String>) =
        (values.entries.joinToString(",") { "${it.key}=${it.value.replace(" ", "")}" } + '\u0000').toByteArray(Charsets.UTF_8)

    private fun checkResult(result: Int) {
        if (result != 0) throw HttpSpeechException(HttpSpeechError.Network, networkCode = null).apply {
            initCause(IllegalStateException("MSC status $result"))
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
