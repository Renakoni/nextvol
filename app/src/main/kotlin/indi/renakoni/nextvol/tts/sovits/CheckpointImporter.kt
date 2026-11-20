package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.R

import ai.onnxruntime.platform.Fp16Conversions
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/** Installs supported GPT-SoVITS checkpoints with the selected mobile graph template. */
internal class CheckpointImporter(
    private val filesRoot: File,
    private val openAsset: (String) -> InputStream,
) {
    data class Result(val characterId: String, val displayName: String, val architecture: String = "v2ProPlus")
    data class Reference(val audioFile: File, val text: String, val language: String = "auto")

    fun import(
        name: String,
        openGpt: () -> InputStream,
        openSovits: () -> InputStream,
        progress: (SovitsImportMessage) -> Unit = {},
        checkCancelled: () -> Unit = {},
        reference: Reference,
        gptFilename: String = "",
        sovitsFilename: String = "",
    ): Result {
        requireSovits(name.isNotBlank() && name.length <= 80) { SovitsImportMessage(R.string.sovits_voice_name_length) }
        reference.let {
            requireSovits(it.text.isNotBlank()) { SovitsImportMessage(R.string.sovits_transcript_required) }
            requireSovits(it.audioFile.isFile && it.audioFile.length() in 1..32L * 1024 * 1024) { SovitsImportMessage(R.string.sovits_reference_file_invalid) }
            requireSovits(it.audioFile.extension.matches(Regex("[A-Za-z0-9]{1,8}"))) { SovitsImportMessage(R.string.sovits_reference_extension_missing) }
        }
        requireSovits(filesRoot.isDirectory || filesRoot.mkdirs()) { SovitsImportMessage(R.string.sovits_model_directory_failed) }
        val staging = File(filesRoot, ".genie-checkpoint-${UUID.randomUUID()}")
        require(staging.mkdir())
        try {
            lateinit var architecture: String
            lateinit var manifestBytes: ByteArray
            lateinit var manifest: JSONObject
            progress(SovitsImportMessage(R.string.sovits_reading_gpt))
            val gptFile = File(staging, "source.ckpt")
            val gptHash = copyCheckpoint(openGpt, gptFile, checkCancelled)
            progress(SovitsImportMessage(R.string.sovits_reading_sovits))
            val sovitsFile = File(staging, "source.pth")
            val sovitsHash = copyCheckpoint(openSovits, sovitsFile, checkCancelled)
            val characterId = "imported-" + UUID.randomUUID().toString()
            val character = File(staging, characterId).apply { mkdirs() }
            val models = File(character, "tts_models").apply { mkdirs() }
            TorchCheckpoint(gptFile).use { gpt ->
                TorchCheckpoint(sovitsFile).use { sovits ->
                    architecture = validateArchitecture(gpt, sovits)
                    progress(SovitsImportMessage(R.string.sovits_reading_architecture, architecture))
                    manifestBytes = openAsset("${assetDirectory(architecture)}/manifest.json").use { it.readBytes() }
                    manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
                    val checkpoints = mapOf("gpt" to gpt, "sovits" to sovits)
                    val groups = manifest.getJSONArray("groups")
                    var storedBytes = 0L
                    var expandedBytes = 0L
                    // Validate the complete pair before producing any installed weight file.
                    for (g in 0 until groups.length()) {
                        val group = groups.getJSONObject(g)
                        val half = group.getString("precision") == "fp16"
                        val entries = group.getJSONArray("tensors")
                        for (i in 0 until entries.length()) {
                            checkCancelled()
                            val entry = entries.getJSONObject(i)
                            val tensor = checkpoints.getValue(entry.getString("checkpoint")).tensor(entry.getString("key"))
                            val expected = entry.getJSONArray("shape").let { array -> List(array.length()) { array.getLong(it) } }
                            requireSovits(tensor.shape == expected && tensor.elements == entry.getLong("elements")) {
                                SovitsImportMessage(R.string.sovits_checkpoint_structure_mismatch, architecture, entry.getString("key"))
                            }
                            storedBytes += tensor.elements * if (half) 2 else 4
                            if (half) expandedBytes += tensor.elements * 4
                        }
                    }
                    // Check the actual final layout once; source copies are removed before expansion.
                    val remainingBytes = maxOf(storedBytes, storedBytes + expandedBytes - gptFile.length() - sovitsFile.length())
                    requireSovits(filesRoot.usableSpace >= remainingBytes) { SovitsImportMessage(R.string.sovits_checkpoint_storage_full) }
                    for (g in 0 until groups.length()) {
                        val group = groups.getJSONObject(g)
                        val half = group.getString("precision") == "fp16"
                        progress(SovitsImportMessage(R.string.sovits_converting_weights, g + 1, groups.length()))
                        File(models, group.getString("file")).outputStream().buffered(1024 * 1024).use { output ->
                            val entries = group.getJSONArray("tensors")
                            for (i in 0 until entries.length()) {
                                checkCancelled()
                                val entry = entries.getJSONObject(i)
                                val checkpoint = checkpoints.getValue(entry.getString("checkpoint"))
                                val tensor = checkpoint.tensor(entry.getString("key"))
                                checkpoint.open(tensor).use { input -> writeTensor(input, output, tensor, half, checkCancelled) }
                            }
                        }
                    }
                }
            }
            progress(SovitsImportMessage(R.string.sovits_installing_optimized_model))
            val templates = manifest.getJSONObject("files")
            for (filename in templates.keys()) {
                checkCancelled()
                val digest = MessageDigest.getInstance("SHA-256")
                openAsset("${assetDirectory(architecture)}/$filename").use { input -> File(models, filename).outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                requireSovits(hex(digest.digest()) == templates.getString(filename)) { SovitsImportMessage(R.string.sovits_model_template_corrupt) }
            }
            File(character, "checkpoint-import.json").writeText(JSONObject().put("schema_version", 1)
                .put("architecture", architecture).put("display_name", name.trim())
                .put("gpt_sha256", gptHash).put("sovits_sha256", sovitsHash)
                .put("gpt_filename", gptFilename).put("sovits_filename", sovitsFilename)
                .put("template_sha256", hex(MessageDigest.getInstance("SHA-256").digest(
                    manifestBytes))).toString(2))
            reference.let {
                progress(SovitsImportMessage(R.string.sovits_saving_reference))
                val audioDirectory = File(character, "prompt_wav").apply { mkdirs() }
                val filename = "reference.${it.audioFile.extension.lowercase()}"
                it.audioFile.copyTo(File(audioDirectory, filename))
                File(character, "prompt_wav.json").writeText(JSONObject().put("Normal",
                    JSONObject().put("wav", filename).put("text", it.text.trim()).put("language", it.language)).toString(2))
            }
            checkCancelled()
            val parent = File(filesRoot, "CharacterModels/$architecture").apply { mkdirs() }
            val target = File(parent, characterId)
            check(!target.exists() && character.renameTo(target))
            return Result(characterId, name.trim(), architecture)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun copyCheckpoint(open: () -> InputStream, target: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer); if (count < 0) break
                total += count
                requireSovits(total <= 2L * 1024 * 1024 * 1024) { SovitsImportMessage(R.string.sovits_checkpoint_size_limit) }
                digest.update(buffer, 0, count); output.write(buffer, 0, count)
            }
        } }
        RandomAccessFile(target, "rw").use { file ->
            requireSovits(file.length() >= 4) { SovitsImportMessage(R.string.sovits_checkpoint_empty) }
            val first = file.readUnsignedByte(); val second = file.readUnsignedByte()
            requireSovits(file.readUnsignedByte() == 3 && file.readUnsignedByte() == 4) { SovitsImportMessage(R.string.sovits_checkpoint_zip_required) }
            if (first != 80 || second != 75) {
                // GPT-SoVITS may replace PK with a two-digit model-version marker.
                requireSovits(first in 48..57 && second in 48..57) { SovitsImportMessage(R.string.sovits_checkpoint_format_unsupported) }
                file.seek(0); file.write(byteArrayOf(80, 75))
            }
        }
        return hex(digest.digest())
    }

    private fun validateArchitecture(gpt: TorchCheckpoint, sovits: TorchCheckpoint): String {
        fun config(checkpoint: TorchCheckpoint, key: String) =
            ((checkpoint.root["config"] as? Map<*, *>)?.get(key) as? Map<*, *>)
                ?: errorSovits(SovitsImportMessage(R.string.sovits_checkpoint_config_missing))
        val g = config(gpt, "model")
        val s = config(sovits, "model")
        val d = config(sovits, "data")
        val pro = s["upsample_initial_channel"] == 512L
        val expectedG = mapOf("vocab_size" to 1025L, "phoneme_vocab_size" to 732L, "embedding_dim" to 512L,
            "hidden_dim" to 512L, "head" to 16L, "linear_units" to 2048L, "n_layer" to 24L, "EOS" to 1024L)
        requireSovits(!sovits.weights.containsKey("cfm.estimator.proj_out.weight") && !sovits.root.containsKey("lora_rank")) {
            SovitsImportMessage(R.string.sovits_v2_variant_unsupported)
        }
        val expectedS = mapOf("inter_channels" to 192L, "hidden_channels" to 192L, "filter_channels" to 768L,
            "n_heads" to 2L, "n_layers" to 6L, "kernel_size" to 3L, "gin_channels" to 1024L,
            "upsample_initial_channel" to if (pro) 512L else 768L)
        requireSovits(expectedG.all { (k, v) -> g[k] == v } && expectedS.all { (k, v) -> s[k] == v } &&
            s["semantic_frame_rate"] == "25hz" && s["resblock"] == "1" &&
            s["upsample_rates"] == listOf(10L, 8L, 2L, 2L, 2L) &&
            s["upsample_kernel_sizes"] == listOf(if (pro) 16L else 20L, 16L, 8L, 2L, 2L) &&
            s["resblock_kernel_sizes"] == listOf(3L, 7L, 11L) &&
            s["resblock_dilation_sizes"] == List(3) { listOf(1L, 3L, 5L) } &&
            d["sampling_rate"] == 32000L && d["filter_length"] == 2048L && d["hop_length"] == 640L) {
            SovitsImportMessage(R.string.sovits_v2_variant_unsupported)
        }
        return if (pro) "v2Pro" else "v2ProPlus"
    }

    internal fun writeTensor(input: InputStream, output: OutputStream, tensor: TorchTensor, half: Boolean, checkCancelled: () -> Unit) {
        val width = tensor.storage.bytesPerElement
        val buffer = ByteArray(64 * 1024)
        val converted = ByteBuffer.allocate(128 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = Math.multiplyExact(tensor.elements, width.toLong())
        while (remaining > 0) {
            checkCancelled()
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            var read = 0
            while (read < count) { val n = input.read(buffer, read, count - read); requireSovits(n > 0) { SovitsImportMessage(R.string.sovits_checkpoint_truncated) }; read += n }
            if (half && tensor.storage.type == "HalfStorage") {
                for (index in 1 until count step 2) {
                    requireSovits(buffer[index].toInt() and 0x7c != 0x7c) { SovitsImportMessage(R.string.sovits_checkpoint_nonfinite) }
                }
                output.write(buffer, 0, count)
                remaining -= count
                continue
            }
            val bytes = ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.LITTLE_ENDIAN)
            converted.clear()
            repeat(count / width) {
                val value = when (tensor.storage.type) {
                    "HalfStorage" -> Fp16Conversions.fp16ToFloat(bytes.short)
                    "BFloat16Storage" -> Float.fromBits((bytes.short.toInt() and 65535) shl 16)
                    else -> bytes.float
                }
                requireSovits(value.isFinite()) { SovitsImportMessage(R.string.sovits_checkpoint_nonfinite) }
                if (half) {
                    val bits = floatToHalf(value)
                    requireSovits(bits and 0x7c00 != 0x7c00) { SovitsImportMessage(R.string.sovits_checkpoint_fp16_range) }
                    converted.putShort(bits.toShort())
                } else converted.putFloat(value)
            }
            output.write(converted.array(), 0, converted.position())
            remaining -= count
        }
    }

    companion object {
        private fun assetDirectory(architecture: String) = "gpt-sovits/checkpoint-${architecture.lowercase()}"
        internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

        /** IEEE binary16 round-to-nearest, ties-to-even, matching torch.float16. */
        internal fun floatToHalf(value: Float): Int {
            val bits = value.toRawBits()
            val sign = (bits ushr 16) and 0x8000
            val exponent = ((bits ushr 23) and 255) - 127
            val fraction = bits and 0x7fffff
            if (exponent > 15) return sign or 0x7c00
            if (exponent < -25) return sign
            val shift = if (exponent < -14) -exponent - 1 else 13
            val mantissa = if (exponent < -14) fraction or 0x800000 else fraction
            val rounded = (mantissa + (1 shl (shift - 1)) - 1 + ((mantissa ushr shift) and 1)) ushr shift
            return sign or (if (exponent < -14) rounded else ((exponent + 15) shl 10) + rounded)
        }
    }
}
