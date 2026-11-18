package indi.renakoni.nextvol.tts.sovits

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Bind optional derived graphs to the unchanged source graph before GPU use. */
object SplitVocoderAssets {
    const val ENCODER = "split-encoder.onnx"
    const val HIFIGAN = "split-hifigan.onnx"
    const val BOUNDARY = "/vq_model/Mul_2_output_0"

    /** Templates contain graph topology and external weight references. Bind them to the exact VITS export. */
    internal fun prepare(context: android.content.Context, voice: GptSovitsVoice, useHtp: Boolean) {
        val directory = voice.model
        val manifest = File(directory, "split-vits.json")
        val htpFiles = if (useHtp) V2HtpVocoder.GRAPHS else emptyList()
        if (manifest.isFile && htpFiles.all { File(directory, it).isFile }) return
        val assets = "gpt-sovits/graphs/${voice.version.directory}"
        val bundled = context.assets.open("$assets/split-vits.json").bufferedReader().use { JSONObject(it.readText()) }
        if (sha256(File(directory, "vits_fp32.onnx")) != bundled.getString("source_sha256")) return
        // Publish the manifest last so a partial first preparation can be retried.
        val files = (if (manifest.isFile) emptyList() else listOf(ENCODER, HIFIGAN)) +
            htpFiles.filterNot { File(directory, it).isFile } +
            (if (manifest.isFile) emptyList() else listOf("split-vits.json"))
        for (name in files) {
            val part = File.createTempFile(".sovits-", ".part", directory)
            try {
                context.assets.open("$assets/$name").use { input -> part.outputStream().use(input::copyTo) }
                java.nio.file.Files.move(part.toPath(), File(directory, name).toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { part.delete() }
        }
    }

    fun validate(directory: File): List<String> {
        val manifest = File(directory, "split-vits.json")
        require(manifest.isFile) { "This voice needs the V2ProPlus CPU + GPU package (split-vits.json). CPU remains available." }
        val data = JSONObject(manifest.readText())
        require(data.getInt("schema_version") == 1 && data.getString("format") == "genie_v2proplus_split_v1" &&
            data.getString("boundary") == BOUNDARY) { "Unsupported split vocoder format." }
        verify(File(directory, "vits_fp32.onnx"), data.getString("source_sha256"))
        val graphs = data.getJSONObject("graphs")
        return listOf(ENCODER, HIFIGAN).onEach { verify(File(directory, it), graphs.getString(it)) }
    }

    internal fun verify(file: File, expected: String) {
        require(sha256(file) == expected) { "Model graph hash mismatch: ${file.name}. Reimport the matching voice package." }
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
