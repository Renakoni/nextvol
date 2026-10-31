package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import android.util.Base64
import es.codefactory.vocalizertts.VocalizerApplication
import es.codefactory.vocalizertts.voices.VocalizerEngine as NativeVocalizer
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory

internal fun vocalizerContext(context: Context): Context {
    val signatures = offlineRuntimeLicenses(context).optJSONObject("vocalizer")?.optJSONArray("signatures")
    check(signatures != null && signatures.length() > 0) { "Vocalizer SDK certificate is missing" }
    val factory = CertificateFactory.getInstance("X.509")
    val certificates = Array(signatures.length()) { index ->
        val bytes = try { Base64.decode(signatures.getString(index), Base64.DEFAULT) }
            catch (failure: IllegalArgumentException) { throw IllegalStateException("Vocalizer SDK certificate is not valid Base64", failure) }
        try { factory.generateCertificate(bytes.inputStream()) }
            catch (failure: CertificateException) { throw IllegalStateException("Vocalizer SDK certificate is not valid X.509", failure) }
        bytes
    }
    return VocalizerContext(context.applicationContext, certificates)
}

internal class VocalizerEngine(context: Context, directory: File, private val voice: OfflineVoiceConfig) : OfflineEngine {
    private val lifecycle = Any()
    private var engine: NativeVocalizer? = null
    private var output: ((ByteArray, Int) -> Unit)? = null
    private var outputFailure: Throwable? = null
    @Volatile private var cancelled = false
    override val sampleRate: Int

    init {
        val resources = File(directory, voice.code)
        require(resources.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && resources.isDirectory) {
            "Missing or escaped Vocalizer resource directory"
        }
        val headers = resources.listFiles().orEmpty().filter { it.isFile && it.extension.equals("hdr", ignoreCase = true) }
        require(headers.isNotEmpty()) { "Vocalizer package has no voice header" }
        val definitions = headers.sortedBy { it.name }.joinToString("\r\n") { header ->
            require(header.canonicalFile.toPath().startsWith(resources.canonicalFile.toPath())) { "Escaped Vocalizer header" }
            val bytes = header.readBytes()
            if (!bytes.toString(Charsets.UTF_8).contains("<nuance>", ignoreCase = true)) {
                bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor headerMask[it % headerMask.size].toInt()).toByte() }
            }
            bytes.toString(Charsets.UTF_8)
        }
        VocalizerApplication.mContext = vocalizerContext(context)
        try {
            val current = NativeVocalizer { samples -> onAudio(samples) }
            engine = current
            current.initialize(definitions, resources.absolutePath + "/")
            // MultiTTS renders at Speaker.sampleRate; the SDK metadata uses its frequency enum.
            sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 16000
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    @Synchronized override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) {
        val current = checkNotNull(engine) { "Vocalizer engine is closed" }
        cancelled = false
        output = emit
        outputFailure = null
        try {
            current.configure((speed * voice.speed * 100f).toInt(), (voice.pitch * 100f).toInt(), (voice.volume * 100f).toInt())
            var start = 0
            while (start < text.length && !cancelled) {
                var end = (start + 200).coerceAtMost(text.length)
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                current.speak(text.substring(start, end))
                start = end
            }
            outputFailure?.let { throw it }
            if (cancelled) throw CancellationException("Vocalizer synthesis cancelled")
        } finally { output = null }
    }

    private fun onAudio(samples: ShortArray): Boolean {
        val destination = output ?: return false
        if (cancelled) return false
        return try {
            val pcm = ByteArray(samples.size * 2)
            samples.forEachIndexed { index, value ->
                pcm[index * 2] = value.toByte()
                pcm[index * 2 + 1] = (value.toInt() shr 8).toByte()
            }
            // Deliver every sample in the callback; the PCM writer owns chunking and bounds.
            destination(pcm, pcm.size)
            !cancelled
        } catch (failure: Throwable) {
            outputFailure = failure
            cancelled = true
            false
        }
    }

    override fun cancel() {
        cancelled = true
        synchronized(lifecycle) { engine?.stop() }
    }

    @Synchronized override fun close() {
        synchronized(lifecycle) {
            try { engine?.close() } finally { engine = null; VocalizerApplication.mContext = null }
        }
    }

    companion object {
        private val headerMask = "JxGsBpJ8iP5CZUFKlRMMxhSyIRiRHGm0sUOMt04ehuTYGHR4eMkLUDMkKh6Cvp8932Z0c1Fiw3UTrtNZh8J4gFCfaz4FEbGHaCnaF9QVHDA7kARm7tPcG2IKD4JnYxlny5u5sMfZlMzoNS1sUjN09vq2fEqtHn07EJSjY5URFyw8LigVhhQ5HjXKpV6Lqgwp7E8UeIF7sYa9JhWKyuU28hEJHJc7ihnl7fqesHxzSBmA6HSUV3EHO3eX0Wc67RfmiEozhpfXI4E2XGzv1m3LpHh8VCNMDnYsVZollIGqI7l89O1DaIgmyv5grunCWQTUoY1sl5EgpMghlYMX2SnTUi915pFsfRFeAlY9Mab03cZqsXP4xmFNatAB1LxAmzJgck4kQJXswEjQP0kAnfa0ziMiVLET9BWKfBjQGncmTFDeLWQp6FzBFzLNCfsOeW66D7C56c6RWneSfpvqLkPsCVTfahbX3rZF2MNCGhLEBnjK51Q2PG95zvsp1FlUumDAn0Bk17v6Srysjlg4jYhLMrjkpDbxQbxw8hkVuVTVOLRDmICnHnsNX5vLtQ6Viag0qt0WpnztZ0QYvCYagki10U1vgBPEkqrEWzGXMB1Q3oTiVCatbLSxiJ24blQcX2UW6ZPcDoeRNJc23BtajxxfM477WpMlkhnxY1C6Hrgxo1j5TVjUVsEvfK5jQe084hIes9GJauOkeXu5qxlelIUfhqvzor4Zp6uWGeBsrCXMBTmvWB5kUhZNIXeAAiCb3US1QE1DcH3y2cQpPMS5ayzxDz3T6ltI2yksSh191ZMtHZSOuSujGSvvyK2sT4Uf9lsTQkBLDyw5vnxgm7CMA2B6iOVZ1eWcKRTT6ZSob1PomNnVxwbeYzeCOIzj45h8Loqi0Tz2P7DJKOmoK72OLRIJ0p0H7YkXbpCXzJ59CHSxT2wQMKEPNl8VzIBfqED8Y0EWUHZbq0zIINnwfBa8gYfNUWSMHOCBOaNQBD0U7zf6SqfenVx3hWz6gGvIof3Etcl\u0000".toByteArray(Charsets.UTF_8)
    }
}
