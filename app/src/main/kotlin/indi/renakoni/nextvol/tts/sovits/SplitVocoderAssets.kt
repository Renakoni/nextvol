package indi.renakoni.nextvol.tts.sovits

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Bind optional derived graphs to the unchanged source graph before GPU use. */
internal object SplitVocoderAssets {
    const val ENCODER = "split-encoder.onnx"
    const val HIFIGAN = "split-hifigan.onnx"
    const val BOUNDARY = "/vq_model/Mul_2_output_0"

    enum class Layout { Full, Split, Htp }

    fun prepare(context: android.content.Context, voice: GptSovitsVoice, useHtp: Boolean): Layout {
        val directory = voice.model
        val assets = "gpt-sovits/graphs/${voice.version.directory}"
        val bundled = context.assets.open("$assets/split-vits.json").bufferedReader().use { JSONObject(it.readText()) }
        val source = sha256(File(directory, "vits_fp32.onnx"))
        val canonical = source == bundled.getString("source_sha256")
        val data = if (canonical) bundled else {
            val manifest = File(directory, "split-vits.json")
            if (!manifest.isFile) return Layout.Full
            JSONObject(manifest.readText()).also {
                require(it.getInt("schema_version") == 1 && it.getString("format") == "genie_v2proplus_split_v1" &&
                    it.getString("boundary") == BOUNDARY && it.getString("source_sha256") == source)
            }
        }
        val hashes = data.getJSONObject("graphs")
        fun prepareGraph(name: String, digests: JSONObject): Boolean {
            val file = File(directory, name)
            if (!digests.has(name)) return false
            if (file.isFile && sha256(file) == digests.getString(name)) return true
            if (!canonical) return false
            val part = File.createTempFile(".sovits-", ".part", directory)
            try {
                context.assets.open("$assets/$name").use { input -> part.outputStream().use(input::copyTo) }
                java.nio.file.Files.move(part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { part.delete() }
            return true
        }
        if (!prepareGraph(ENCODER, hashes) || !prepareGraph(HIFIGAN, hashes)) return Layout.Full
        if (!useHtp) return Layout.Split
        return try {
            prepareHtpGraphs(directory, source, hashes, ::prepareGraph)
        } catch (failure: Exception) {
            android.util.Log.w("NextVolSoVITS", "Ignoring unavailable HTP graphs", failure)
            Layout.Split
        }
    }

    private fun prepareHtpGraphs(directory: File, source: String, hashes: JSONObject,
                                 prepareGraph: (String, JSONObject) -> Boolean): Layout {
        // Voicebox exports HTP checksums in the checkpoint manifest.
        val htpHashes = if (hashes.has(V2HtpVocoder.BODY)) hashes else {
            val checkpoint = File(directory, "manifest.json")
            if (!checkpoint.isFile) return Layout.Split
            val files = JSONObject(checkpoint.readText()).getJSONObject("files")
            if (files.optString("vits_fp32.onnx") != source) return Layout.Split
            files
        }
        return if (V2HtpVocoder.GRAPHS.all { prepareGraph(it, htpHashes) }) Layout.Htp else Layout.Split
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            var count = stream.read(buffer)
            while (count >= 0) {
                digest.update(buffer, 0, count)
                count = stream.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
