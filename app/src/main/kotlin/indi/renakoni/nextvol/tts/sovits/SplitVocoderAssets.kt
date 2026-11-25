package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** Publish the selected acoustic graphs beside their external weights once per voice. */
internal object SplitVocoderAssets {
    const val ENCODER = "split-encoder.onnx"
    const val HIFIGAN = "split-hifigan.onnx"
    const val BOUNDARY = "/vq_model/Mul_2_output_0"

    fun prepare(voice: GptSovitsVoice, useHtp: Boolean) {
        val directory = voice.model
        val assets = File(SpeechRuntimePack.languageData.directory, "assets/gpt-sovits/graphs/${voice.version.directory}")
        val bundled = File(assets, "split-vits.json").bufferedReader().use { JSONObject(it.readText()) }
        require(sha256(File(directory, "vits_fp32.onnx")) == bundled.getString("source_sha256"))
        val hashes = bundled.getJSONObject("graphs")
        val required = if (useHtp) listOf(ENCODER, HIFIGAN) + V2HtpVocoder.GRAPHS else listOf(ENCODER, HIFIGAN)
        for (name in required) {
            val file = File(directory, name)
            val expected = hashes.getString(name)
            if (file.isFile && sha256(file) == expected) continue
            val part = File.createTempFile(".sovits-", ".part", directory)
            try {
                val digest = part.outputStream().use { sha256(File(assets, name), it) }
                require(digest == expected) { "Invalid acoustic graph: $name" }
                java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { part.delete() }
        }
    }

    private fun sha256(file: File, copyTo: OutputStream? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            var count = stream.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                copyTo?.write(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
