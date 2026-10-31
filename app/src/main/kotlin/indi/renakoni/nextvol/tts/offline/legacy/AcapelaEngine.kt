package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.TextToSpeech
import android.util.Base64
import com.acapelagroup.android.tts.AcattsandroidService
import com.acapelagroup.android.tts.acattsandroid
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal class AcapelaEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private var classic: acattsandroid? = null
    private var extended: AcattsandroidService? = null
    private val lifecycle = Any()
    @Volatile private var cancelled = false
    override val sampleRate: Int

    init {
        val root = directory.canonicalFile
        fun resource(path: String): File = File(root, path).canonicalFile.also {
            require(it.toPath().startsWith(root.toPath())) { "Acapela resource escapes its installation" }
        }
        if (voice.parameters.isBlank() || voice.parameters.trimStart().startsWith('{')) {
            val options = if (voice.parameters.isBlank()) JSONObject() else JSONObject(voice.parameters)
            val folder = resource("voicedream/${voice.code}")
            require(folder.isDirectory) { "Acapela voice directory is missing" }
            val licenseConfig = options.optString("licenseConfig").takeIf(String::isNotBlank)?.let(::resource)
                ?: listOf(File(folder, "license.json"), File(root, "license.json")).firstOrNull(File::isFile)
            val credentials = offlineRuntimeLicenses(context).optJSONObject("acapela") ?: JSONObject()
            val supplied = licenseConfig?.let { JSONObject(it.readText()) } ?: options
            supplied.keys().forEach { credentials.put(it, supplied.get(it)) }
            val license = credentials.optString("licenseFile").takeIf(String::isNotBlank)?.let { resource(it).readText() }
                ?: credentials.optString("license")
            require(credentials.has("userId") && credentials.has("password") && license.isNotBlank()) {
                "Acapela runtime requires userId, password and license or licenseFile"
            }
            val engine = acattsandroid()
            classic = engine
            try {
                engine.nInitCallbacks()
                engine.nSetLog(false)
                engine.nSetLicense(credentials.getLong("userId"), credentials.getLong("password"), license)
                engine.nGetVoiceList(arrayOf(folder.path))
                val ini = folder.walkTopDown().filter { it.isFile && it.extension == "ini" }.sortedBy { it.path }.firstOrNull()
                    ?: error("Acapela voice configuration is missing")
                val name = engine.nGetVoiceInfo(ini.path, "speaker")
                sampleRate = engine.nLoadVoice(name)
                check(sampleRate > 0) { "Acapela rejected the voice or its license: $sampleRate" }
            } catch (failure: Throwable) {
                engine.nShutdown()
                classic = null
                throw failure
            }
        } else {
            val decoded = if (voice.parameters.contains(',')) voice.parameters else
                String(codec(Cipher.DECRYPT_MODE).doFinal(Base64.decode(voice.parameters, Base64.NO_WRAP)), Charsets.UTF_8)
            val fields = decoded.split(',').map(String::trim)
            require(fields.size >= 3) { "Acapela parameters require voiceId, account and serial" }
            val parts = fields[0].split('-')
            require(parts.size >= 3) { "Acapela voiceId requires language, country and speaker" }
            val account = fields[1]
            val serial = fields[2]
            val encoded = Base64.encodeToString(codec(Cipher.ENCRYPT_MODE).doFinal(account.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
                .replace("/", "")
            val folder = sequenceOf(resource(encoded), resource(account)).firstOrNull(File::isDirectory)
                ?: error("Acapela account resource directory is missing")
            val locale = Locale(parts[0], parts[1])
            val engine = AcattsandroidService(account + serial)
            extended = engine
            try {
                engine.nInit(folder.path)
                engine.nSetVoiceMode("MODE=prep_hd")
                engine.nSetProperty("punctuation_pause", "0")
                engine.nSetLanguage(locale.isO3Language, locale.isO3Country, "")
                check(engine.nSetCurrentVoice(parts.drop(2).joinToString("-")) >= 0) { "Acapela rejected the requested voice" }
                sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 22050
            } catch (failure: Throwable) {
                engine.nShutdown()
                extended = null
                throw failure
            }
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(classic != null || extended != null) { "Acapela engine is closed" }
        cancelled = false
        val outputFailure = AtomicReference<Throwable?>()
        fun output(data: ByteArray, offset: Int, length: Int): Int {
            if (cancelled) return -1
            return try {
                require(offset >= 0 && length >= 0 && offset <= data.size - length) { "Acapela returned invalid PCM bounds" }
                emit(if (offset == 0) data else data.copyOfRange(offset, offset + length), length)
                0
            } catch (failure: Throwable) {
                outputFailure.compareAndSet(null, failure)
                cancelled = true
                -1
            }
        }
        try {
            classic?.let { engine ->
                engine.nSetSpeechRate(100 * speed * voice.speed)
                engine.nSetShape(100 * voice.pitch)
                engine.setListener { samples, length ->
                    try {
                        require(length >= 0 && length <= samples.size) { "Acapela returned invalid PCM length" }
                        val bytes = ByteArray(length.toInt() * 2)
                        repeat(length.toInt()) { index ->
                            val sample = (samples[index] * voice.volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            bytes[index * 2] = sample.toByte()
                            bytes[index * 2 + 1] = (sample shr 8).toByte()
                        }
                        output(bytes, 0, bytes.size)
                    } catch (failure: Throwable) {
                        outputFailure.compareAndSet(null, failure)
                        cancelled = true
                    }
                }
                engine.nSpeak(text)
            }
            extended?.let { engine ->
                val callback = object : SynthesisCallback {
                    private var finished = false
                    override fun getMaxBufferSize() = 8192
                    override fun start(rate: Int, format: Int, channels: Int): Int {
                        if (rate == sampleRate && format == AudioFormat.ENCODING_PCM_16BIT && channels == 1) return 0
                        error(TextToSpeech.ERROR_OUTPUT)
                        return -1
                    }
                    override fun audioAvailable(data: ByteArray, offset: Int, length: Int) = output(data, offset, length)
                    override fun done(): Int { finished = true; return 0 }
                    override fun error() = error(TextToSpeech.ERROR_SYNTHESIS)
                    override fun error(code: Int) { outputFailure.compareAndSet(null, IllegalStateException("Acapela synthesis failed: $code")) }
                    override fun hasStarted() = true
                    override fun hasFinished() = finished
                    override fun rangeStart(marker: Int, start: Int, end: Int) = Unit
                }
                engine.setCallback(callback)
                engine.nSetProperty("pitch", (100 * voice.pitch).toInt().toString())
                engine.nSetProperty("volume", voice.volume.toString())
                engine.nSetProperty("rate", (100 * speed * voice.speed).toInt().toString())
                engine.nSpeak(text.replace("/", ", "), callback)
            }
            outputFailure.get()?.let { throw it }
            if (cancelled) throw CancellationException("Acapela synthesis cancelled")
        } finally {
            classic?.setListener(null)
            extended?.setCallback(null)
        }
    }

    override fun cancel() = synchronized(lifecycle) {
        cancelled = true
        classic?.nStop()
        extended?.nStop()
        Unit
    }

    @Synchronized override fun close() = synchronized(lifecycle) {
        classic?.let { it.nStop(); it.nShutdown() }
        extended?.let { it.nStop(); it.nShutdown() }
        classic = null
        extended = null
    }

    companion object {
        // MultiTTS uses this format key to encode account directory names and param data.
        private fun codec(mode: Int): Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(mode, SecretKeySpec("MultiTTS.Acapela".toByteArray(Charsets.UTF_8), "AES"))
        }
    }
}
