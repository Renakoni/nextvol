package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.cereproc.cerevoice_eng.TtsEngineCallback
import com.cereproc.cerevoice_eng.cerevoice_engJNI as Native
import hnovel.speech.OfflineVoiceConfig
import kotlinx.coroutines.CancellationException
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

internal class CereProcEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private var engine = 0L
    private var channel = 0
    @Volatile private var cancelled = false
    override val sampleRate: Int

    init {
        val root = directory.canonicalFile
        fun resource(path: String): File = File(root, path).canonicalFile.also {
            require(it.toPath().startsWith(root.toPath())) { "CereProc resource escapes its installation" }
        }
        val folder = resource(voice.code)
        val models = folder.listFiles().orEmpty().filter { it.isFile && it.extension == "voice" }
            .sortedWith(compareByDescending<File> { voice.sampleRate > 0 && it.name.contains("${voice.sampleRate / 1000}k") }.thenBy { it.name })
        require(models.isNotEmpty()) { "CereProc voice data is missing" }
        val options = if (voice.parameters.isBlank()) JSONObject() else JSONObject(voice.parameters)
        val explicitLicense = options.optString("licenseFile").takeIf(String::isNotBlank)?.let(::resource)
        val license = explicitLicense ?: folder.listFiles().orEmpty().singleOrNull { it.isFile && it.extension == "lic" }
        var header = options.optString("licenseHeader")
        var signature = options.optString("licenseSignature")
        if (license == null && header.isEmpty() && signature.isEmpty()) {
            val records = File(root, "Product_Records.json").takeIf(File::isFile)?.let { JSONObject(it.readText()).optJSONArray("voices") }
            val record = (0 until (records?.length() ?: 0)).asSequence().map { records!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == voice.code }
            val productCode = record?.optJSONObject("data")?.optString("code").orEmpty()
            val defaults = offlineRuntimeLicenses(context).optJSONArray("cereproc")
            val credential = (0 until (defaults?.length() ?: 0)).asSequence().map { defaults!!.getJSONObject(it) }
                .firstOrNull {
                    productCode.isNotEmpty() && it.getString("header").replace("\n", "").replace('=', ' ')
                        .split(' ').getOrNull(3)?.contains(productCode) == true
                }
            header = credential?.optString("header").orEmpty()
            signature = credential?.optString("signature").orEmpty()
        }
        require(license?.isFile == true || header.isNotEmpty() && signature.isNotEmpty()) {
            "CereProc requires the voice's license file or licenseHeader and licenseSignature; Product_Records.json contains only voice metadata"
        }
        engine = Native.CPRCEN_engine_new()
        check(engine != 0L) { "CereProc initialization failed" }
        try {
            var loaded = false
            for (model in models) {
                for (loadType in 1..2) {
                    val result = if (license != null) Native.CPRCEN_engine_load_voice(engine, model.canonicalPath, license.path,
                        loadType, null, null, null, null)
                    else Native.CPRCEN_engine_load_voice_licensestr(engine, header, signature, null, model.canonicalPath, loadType)
                    if (result == 1) { loaded = true; break }
                }
                if (loaded) break
            }
            check(loaded && Native.CPRCEN_engine_get_voice_count(engine) > 0) { "CereProc rejected the voice or its license" }
            sampleRate = Native.CPRCEN_engine_get_voice_info(engine, 0, "sample_rate")?.toIntOrNull()?.takeIf { it > 0 }
                ?: voice.sampleRate.takeIf { it > 0 } ?: error("CereProc voice sample rate is missing")
        } catch (failure: Throwable) {
            Native.CPRCEN_engine_delete(engine)
            engine = 0L
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        check(engine != 0L) { "CereProc engine is closed" }
        cancelled = false
        channel = Native.CPRCEN_engine_open_channel_with_voice(engine, 0)
        if (channel == 0) channel = Native.CPRCEN_engine_open_default_channel(engine)
        check(channel != 0) { "CereProc could not open a synthesis channel" }
        val outputFailure = AtomicReference<Throwable?>()
        val callback = object : TtsEngineCallback() {
            override fun onAudio(buffer: Long) {
                try {
                    if (cancelled) { Native.CPRCEN_engine_channel_reset(engine, channel); return }
                    check(Native.CPRC_abuf_wav_srate(buffer) == sampleRate) { "CereProc changed the sample rate" }
                    var offset = Native.CPRC_abuf_wav_mk(buffer)
                    val end = Native.CPRC_abuf_wav_done(buffer)
                    while (offset < end && !cancelled) {
                        val count = minOf(8192, end - offset)
                        val bytes = ByteArray(count * 2)
                        repeat(count) { index ->
                            val sample = Native.CPRC_abuf_wav(buffer, offset + index).toInt()
                            bytes[index * 2] = sample.toByte()
                            bytes[index * 2 + 1] = (sample shr 8).toByte()
                        }
                        emit(bytes, bytes.size)
                        offset += count
                    }
                } catch (failure: Throwable) {
                    outputFailure.compareAndSet(null, failure)
                    cancelled = true
                    Native.CPRCEN_engine_channel_reset(engine, channel)
                }
            }
        }
        try {
            callback.setCallback(engine, channel)
            Native.CPRCEN_channel_set_phone_min_max(engine, channel, 10, 20)
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;")
            val markup = String.format(Locale.ROOT, "<sig amplitude='%.3f' f0='%.3f' rate='%.3f'>%s</sig>",
                voice.volume, voice.pitch, speed * voice.speed, escaped)
            Native.CPRCEN_engine_channel_speak(engine, channel, markup, markup.toByteArray(Charsets.UTF_8).size, 1)
            outputFailure.get()?.let { throw it }
            if (cancelled) throw CancellationException("CereProc synthesis cancelled")
        } finally {
            callback.clearCallback(engine, channel)
            Native.CPRCEN_engine_channel_close(engine, channel)
            channel = 0
        }
    }

    override fun cancel() { cancelled = true }

    @Synchronized override fun close() {
        if (engine != 0L) {
            Native.CPRCEN_engine_delete(engine)
            engine = 0L
        }
    }
}
