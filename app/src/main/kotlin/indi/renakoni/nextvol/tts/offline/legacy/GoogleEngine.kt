package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.google.android.apps.speech.tts.googletts.local.greco3.AndroidComposerImpl
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

internal class GoogleEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private val composer = AndroidComposerImpl(context)
    private val pipeline: ParcelFileDescriptor
    private val speaker = voice.parameters.ifBlank { voice.code }.substringAfterLast('-')
    private val session = session(speaker, if (voice.gender == 1) "male" else "female")
    @Volatile private var cancelled = false
    override val sampleRate: Int get() = composer.sampleRate.takeIf { it > 0 }
        ?: voice.sampleRate.takeIf { it > 0 } ?: 24000

    init {
        val locale = voice.locale.ifBlank { voice.code.substringBefore("-x-") }
            .lowercase(Locale.US).replace("zh-", "cmn-")
        val localeDirectory = File(directory, locale)
        require(localeDirectory.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Escaped Google locale directory" }
        val speakerDeclaration = Regex("speaker:\\s*\"${Regex.escape(speaker)}\"")
        val candidates = localeDirectory.listFiles().orEmpty().filter { candidate ->
            candidate.isDirectory && File(candidate, "backend_pipeline.textproto").let { definition ->
                require(definition.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Escaped Google pipeline definition" }
                definition.isFile && speakerDeclaration.containsMatchIn(definition.readText())
            }
        }
        val selected = candidates.sortedBy { it.name }.firstOrNull()
            ?: error("Google package has no pipeline for $locale/$speaker")
        val model = File(selected, "pipeline.pb")
        require(model.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && model.isFile) { "Missing or escaped Google pipeline" }
        pipeline = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            composer.initialize(pipeline.fd, selected.absolutePath, normalizationOverrides)
        } catch (failure: Throwable) {
            pipeline.close()
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        cancelled = false
        try {
            composer.start(markup(text, speed * voice.speed, voice.volume), session)
            while (!cancelled) {
                when (val status = composer.read()) {
                    2 -> break
                    3 -> {
                        val audio = composer.consumeAudio()
                        if (!cancelled && audio.isNotEmpty()) emit(audio, audio.size)
                    }
                    else -> error("Google synthesis failed: $status")
                }
            }
            if (cancelled) throw CancellationException("Google synthesis cancelled")
        } finally { composer.discardAudio() }
    }

    override fun cancel() { cancelled = true }
    @Synchronized override fun close() {
        try { composer.close() } finally { pipeline.close() }
    }

    companion object {
        // Public locale normalization rules: en-GB road abbreviations and en-US temperature ranges.
        private val normalizationOverrides = Base64.decode(
            "CmsKBWVuLUdCEmIKMwoGbGJsc2VxEhcKB2VuLUdCLjASDEUiIGwgLiBiIGk6IhoOCgRlbi4wEgZsYmxzZXEiABIrIikSJwoPXnwgfFtbOnB1bmN0Ol1dEgJMQhoIIFtyUl1vYWQiBmxibHNlcQp5CgVlbi1VUxJwEm4ibBJqCi1bWzpkaWdpdDpdXSsoPzpcXC5bWzpkaWdpdDpdXSk/ID8oPzrCsEZ8wrBDKT8SBSA/LSA/GixbWzpkaWdpdDpdXSsoPzpcXC5bWzpkaWdpdDpdXSk/ID8oPzrCsEZ8wrBDKSIEIHRvIA==",
            Base64.DEFAULT,
        )

        private fun markup(text: String, rate: Float, volume: Float) = message {
            nested(2) { // MarkupMessage -> InnerMessage
                nested(1) { nested(2) { nested(1) { string(2, text) } } }
                nested(2) {
                    nested(3) { float(1, rate) }
                    nested(4) { float(2, volume) }
                }
            }
        }

        private fun session(speaker: String, gender: String) = message {
            integer(2, 1)
            integer(3, 512)
            nested(14) { string(1, speaker); string(2, gender) }
            nested(17) {
                listOf("VocaineSilenceMuter__enable_muting", "Darwinn__enable_darwinn", "Darwinn__compile_on_device",
                    "Lemonbalm__enable_compile_on_device", "Lemonbalm__enable_lemonbalm_darwinn_rio",
                    "Locomel__enable_compile_on_device", "Locomel__enable_havoc_on_32bit").forEach { flag ->
                    nested(1) { string(1, flag); integer(2, 0) }
                }
                nested(1) { string(1, "Locomel__enable_havoc_on_64bit"); integer(2, 1) }
                listOf("Locomel__duration_delegate", "Locomel__mel_delegate", "Locomel__nvoc_delegate").forEach { flag ->
                    nested(2) { string(1, flag); integer(2, 1) }
                }
            }
        }

        private fun message(block: Wire.() -> Unit): ByteArray = Wire().apply(block).bytes()
    }

    /** The small proto3 subset passed to JNI, with field numbers from the SDK's wire schema. */
    private class Wire {
        private val output = ByteArrayOutputStream()
        fun bytes(): ByteArray = output.toByteArray()
        fun string(field: Int, value: String) = data(field, value.toByteArray(Charsets.UTF_8))
        fun nested(field: Int, block: Wire.() -> Unit) = data(field, message(block))
        fun integer(field: Int, value: Int) { varint(field shl 3); varint(value) }
        fun float(field: Int, value: Float) {
            varint((field shl 3) or 5)
            val bits = value.toRawBits()
            repeat(4) { output.write((bits ushr (it * 8)) and 255) }
        }
        private fun data(field: Int, value: ByteArray) {
            varint((field shl 3) or 2); varint(value.size); output.write(value)
        }
        private fun varint(value: Int) {
            var remaining = value
            while (remaining and -128 != 0) { output.write((remaining and 127) or 128); remaining = remaining ushr 7 }
            output.write(remaining)
        }
    }
}
