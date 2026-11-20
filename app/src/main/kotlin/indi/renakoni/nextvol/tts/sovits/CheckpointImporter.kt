package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.platform.Fp16Conversions
import indi.renakoni.nextvol.R
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

internal fun checkpointGraphAsset(template: String, name: String) =
    if (name == "vits_fp32.onnx") "$template/$name" else "gpt-sovits/graphs/$name"

/** Writes the two supported model layouts in the offline repository's unpublished staging directory. */
internal class CheckpointImporter(private val stage: File, private val openAsset: (String) -> InputStream) {
    data class Result(val architecture: String, val character: File)

    fun convert(openGpt: () -> InputStream, openSovits: () -> InputStream,
        progress: (SovitsImportMessage) -> Unit, checkCancelled: () -> Unit): Result {
        val gptFile = File(stage, "source.ckpt")
        val sovitsFile = File(stage, "source.pth")
        progress(SovitsImportMessage(R.string.sovits_reading_gpt))
        copyCheckpoint(openGpt, gptFile, checkCancelled)
        progress(SovitsImportMessage(R.string.sovits_reading_sovits))
        copyCheckpoint(openSovits, sovitsFile, checkCancelled)
        val result = TorchCheckpoint(gptFile).use { gpt ->
            TorchCheckpoint(sovitsFile).use { sovits ->
                val architecture = validateArchitecture(gpt, sovits)
                progress(SovitsImportMessage(R.string.sovits_reading_architecture, architecture))
                val assets = "gpt-sovits/checkpoint-${architecture.lowercase()}"
                val manifest = openAsset("$assets/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
                val groups = manifest.getJSONArray("groups")
                val outputBytes = (0 until groups.length()).sumOf { g ->
                    val tensors = groups.getJSONObject(g).getJSONArray("tensors")
                    (0 until tensors.length()).sumOf { tensors.getJSONObject(it).getLong("elements") * Float.SIZE_BYTES }
                }
                requireSovits(stage.usableSpace >= outputBytes) { SovitsImportMessage(R.string.sovits_checkpoint_storage_full) }
                val character = File(stage, "CharacterModels/$architecture/imported-${UUID.randomUUID()}")
                val models = File(character, "tts_models")
                check(models.mkdirs())
                val checkpoints = mapOf("gpt" to gpt, "sovits" to sovits)
                val inputBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                val outputBuffer = ByteBuffer.allocate(inputBuffer.capacity() * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (g in 0 until groups.length()) {
                    val group = groups.getJSONObject(g)
                    val half = group.getString("precision") == "fp16"
                    val filename = group.getString("file").replace("_fp16.bin", "_fp32.bin")
                    progress(SovitsImportMessage(R.string.sovits_converting_weights, g + 1, groups.length()))
                    File(models, filename).outputStream().buffered().use { output ->
                        val tensors = group.getJSONArray("tensors")
                        for (i in 0 until tensors.length()) {
                            checkCancelled()
                            val entry = tensors.getJSONObject(i)
                            val key = entry.getString("key")
                            val checkpoint = checkpoints.getValue(entry.getString("checkpoint"))
                            val tensor = checkpoint.tensor(key)
                            val shape = entry.getJSONArray("shape")
                            requireSovits(shape.length() == tensor.shape.size && tensor.shape.indices.all { tensor.shape[it] == shape.getLong(it) }) {
                                SovitsImportMessage(R.string.sovits_checkpoint_structure_mismatch, architecture, key)
                            }
                            checkpoint.open(tensor).use { input ->
                                writeTensor(DataInputStream(input), output, tensor, half, inputBuffer, outputBuffer, checkCancelled)
                            }
                        }
                    }
                }
                progress(SovitsImportMessage(R.string.sovits_installing_optimized_model))
                for (filename in manifest.getJSONObject("files").keys()) {
                    checkCancelled()
                    openAsset(checkpointGraphAsset(assets, filename)).use { input -> File(models, filename).outputStream().use { input.copyTo(it) } }
                }
                Result(architecture, character)
            }
        }
        // On failure the repository discards the whole stage. On success only model files enter the catalog.
        check(gptFile.delete() && sovitsFile.delete())
        return result
    }

    private fun copyCheckpoint(open: () -> InputStream, target: File, checkCancelled: () -> Unit) {
        open().use { input -> target.outputStream().use { output ->
            val first = input.read()
            val second = input.read()
            val third = input.read()
            val fourth = input.read()
            requireSovits(fourth >= 0) { SovitsImportMessage(R.string.sovits_checkpoint_empty) }
            requireSovits(third == 3 && fourth == 4) { SovitsImportMessage(R.string.sovits_checkpoint_zip_required) }
            // GPT-SoVITS may replace PK with its two-digit model-version marker.
            requireSovits((first == 80 && second == 75) || (first in 48..57 && second in 48..57)) {
                SovitsImportMessage(R.string.sovits_checkpoint_format_unsupported)
            }
            output.write(byteArrayOf(80, 75, 3, 4))
            var total = 4L
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer); if (count < 0) break
                total += count
                requireSovits(total <= 2L * 1024 * 1024 * 1024) { SovitsImportMessage(R.string.sovits_checkpoint_size_limit) }
                output.write(buffer, 0, count)
            }
        } }
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

    private fun writeTensor(input: DataInputStream, output: OutputStream, tensor: TorchTensor, half: Boolean,
        source: ByteBuffer, result: ByteBuffer, checkCancelled: () -> Unit) {
        val type = tensor.storage.type
        val width = tensor.storage.bytesPerElement
        var remaining = tensor.elements
        while (remaining > 0) {
            checkCancelled()
            val count = minOf(remaining, (source.capacity() / width).toLong()).toInt()
            input.readFully(source.array(), 0, count * width)
            source.rewind()
            result.clear()
            repeat(count) {
                val value = when (type) {
                    "HalfStorage" -> Fp16Conversions.fp16ToFloat(source.short)
                    "BFloat16Storage" -> Fp16Conversions.bf16ToFloat(source.short)
                    else -> source.float
                }
                requireSovits(value.isFinite()) { SovitsImportMessage(R.string.sovits_checkpoint_nonfinite) }
                // The graphs read FP32. Match the template's FP16 rounding without storing a second copy.
                val stored = if (half && type != "HalfStorage") {
                    Fp16Conversions.fp16ToFloat(Fp16Conversions.floatToFp16(value)).also {
                        requireSovits(it.isFinite()) { SovitsImportMessage(R.string.sovits_checkpoint_fp16_range) }
                    }
                } else value
                result.putFloat(stored)
            }
            output.write(result.array(), 0, count * Float.SIZE_BYTES)
            remaining -= count
        }
    }
}
