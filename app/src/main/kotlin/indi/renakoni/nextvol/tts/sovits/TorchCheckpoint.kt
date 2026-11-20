package indi.renakoni.nextvol.tts.sovits

import indi.renakoni.nextvol.R

import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader

private val storageKey = Regex("[a-zA-Z0-9_]+")

internal data class TorchStorage(val type: String, val key: String, val count: Long) {
    val bytesPerElement: Int get() = when (type) {
        "HalfStorage", "BFloat16Storage" -> 2
        "FloatStorage" -> 4
        else -> errorSovits(SovitsImportMessage(R.string.sovits_checkpoint_precision_unsupported, type))
    }
}

internal data class TorchTensor(
    val storage: TorchStorage, val offset: Long, val shape: List<Long>, val stride: List<Long>,
) {
    val elements: Long get() = shape.fold(1L, Math::multiplyExact)

    fun validate() {
        requireSovits(shape.size == stride.size && shape.size <= 8 && shape.all { it > 0 }) { SovitsImportMessage(R.string.sovits_tensor_shape_invalid) }
        var contiguous = 1L
        for (index in shape.indices.reversed()) {
            requireSovits(shape[index] == 1L || stride[index] == contiguous) { SovitsImportMessage(R.string.sovits_tensor_noncontiguous) }
            contiguous = Math.multiplyExact(contiguous, shape[index])
        }
        requireSovits(offset >= 0 && Math.addExact(offset, contiguous) <= storage.count) { SovitsImportMessage(R.string.sovits_tensor_out_of_bounds) }
    }
}

/** Reads tensor metadata only. Pickle globals are symbols, never executable Python objects. */
internal class TorchPickle(private val bytes: ByteArray) {
    private data class Symbol(val module: String, val name: String)
    private class Params : LinkedHashMap<Any?, Any?>()
    private fun isParams(symbol: Symbol) = symbol.name == "HParams" && symbol.module in setOf("utils", "GPT_SoVITS.utils")
    private val marker = Any()
    private var position = 0
    private val stack = arrayListOf<Any?>()
    private val memo = hashMapOf<Int, Any?>()
    private fun byte(): Int { requireSovits(position < bytes.size) { SovitsImportMessage(R.string.sovits_metadata_truncated) }; return bytes[position++].toInt() and 255 }
    private fun unsigned(count: Int): Long {
        var value = 0L
        repeat(count) { value = value or (byte().toLong() shl (it * 8)) }
        return value
    }
    private fun text(length: Int): String {
        requireSovits(length in 0..1_048_576 && length <= bytes.size - position) { SovitsImportMessage(R.string.sovits_metadata_length_invalid) }
        return bytes.copyOfRange(position, position + length).toString(Charsets.UTF_8).also { position += length }
    }
    private fun line(): String {
        val start = position
        while (position < bytes.size && bytes[position] != 10.toByte()) position++
        requireSovits(position < bytes.size && position - start <= 4096) { SovitsImportMessage(R.string.sovits_metadata_format_invalid) }
        return bytes.copyOfRange(start, position++).toString(Charsets.UTF_8)
    }
    private fun pop(): Any? { requireSovits(stack.isNotEmpty()) { SovitsImportMessage(R.string.sovits_metadata_stack_empty) }; return stack.removeAt(stack.lastIndex) }
    private fun marked(): List<Any?> {
        val index = stack.indexOfLast { it === marker }
        requireSovits(index >= 0) { SovitsImportMessage(R.string.sovits_metadata_marker_missing) }
        val values = stack.subList(index + 1, stack.size).toList()
        stack.subList(index, stack.size).clear()
        return values
    }
    private fun tuple(count: Int) {
        require(stack.size >= count)
        val values = stack.subList(stack.size - count, stack.size).toList()
        repeat(count) { pop() }
        stack.add(values)
    }
    private fun symbol(module: String, name: String): Symbol {
        requireSovits((module == "collections" && name == "OrderedDict") ||
            (module == "torch._utils" && name in setOf("_rebuild_tensor", "_rebuild_tensor_v2")) ||
            isParams(Symbol(module, name)) ||
            (module == "torch" && name in setOf("HalfStorage", "FloatStorage", "BFloat16Storage"))) {
            SovitsImportMessage(R.string.sovits_checkpoint_object_unsupported)
        }
        return Symbol(module, name)
    }
    @Suppress("UNCHECKED_CAST")
    fun read(): Map<*, *> {
        var operations = 0
        while (position < bytes.size) {
            requireSovits(++operations < 2_000_000 && stack.size < 100_000 && memo.size < 1_000_000) { SovitsImportMessage(R.string.sovits_metadata_too_large) }
            when (val opcode = byte()) {
                0x80 -> requireSovits(byte() in 2..5) { SovitsImportMessage(R.string.sovits_pytorch_format_unsupported) }
                0x95 -> require(unsigned(8) in 0..(bytes.size - position).toLong())
                '.'.code -> { require(stack.size == 1); return pop() as? Map<*, *> ?: errorSovits(SovitsImportMessage(R.string.sovits_checkpoint_root_invalid)) }
                '('.code -> stack.add(marker)
                ')'.code -> stack.add(emptyList<Any?>())
                ']'.code -> stack.add(arrayListOf<Any?>())
                '}'.code -> stack.add(linkedMapOf<Any?, Any?>())
                'N'.code -> stack.add(null)
                0x88 -> stack.add(true)
                0x89 -> stack.add(false)
                'K'.code -> stack.add(byte().toLong())
                'M'.code -> stack.add(unsigned(2))
                'J'.code -> stack.add(unsigned(4).toInt().toLong())
                0x8a -> {
                    val length = byte(); require(length in 0..8)
                    val value = unsigned(length)
                    stack.add(if (length in 1..7 && value and (1L shl (length * 8 - 1)) != 0L)
                        value or (-1L shl (length * 8)) else value)
                }
                'G'.code -> { val raw = ByteArray(8) { byte().toByte() }; stack.add(ByteBuffer.wrap(raw).double) }
                'X'.code -> stack.add(text(unsigned(4).toInt()))
                0x8c -> stack.add(text(byte()))
                0x8d -> { val length = unsigned(8); require(length in 0..1_048_576); stack.add(text(length.toInt())) }
                'q'.code, 'r'.code -> { val key = if (opcode == 'q'.code) byte() else unsigned(4).toInt(); require(key in 0..1_000_000); memo[key] = stack.last() }
                0x94 -> memo[memo.size] = stack.last()
                'h'.code, 'j'.code -> { val key = if (opcode == 'h'.code) byte() else unsigned(4).toInt(); require(memo.containsKey(key)); stack.add(memo[key]) }
                't'.code -> stack.add(marked())
                0x85 -> tuple(1)
                0x86 -> tuple(2)
                0x87 -> tuple(3)
                0x81 -> {
                    val args = pop() as List<*>
                    val type = pop() as Symbol
                    requireSovits(isParams(type) && args.isEmpty()) { SovitsImportMessage(R.string.sovits_config_object_unsupported) }
                    stack.add(Params()) // A plain map, never a Python constructor.
                }
                'c'.code -> stack.add(symbol(line(), line()))
                0x93 -> { val name = pop() as String; val module = pop() as String; stack.add(symbol(module, name)) }
                'Q'.code -> {
                    val id = pop() as List<*>
                    require(id.size == 5 && id[0] == "storage")
                    val type = id[1] as Symbol
                    require(type.module == "torch" && type.name.endsWith("Storage"))
                    val key = id[2] as String; val count = id[4] as Long
                    require(storageKey.matches(key) && count in 1..1_073_741_824L)
                    stack.add(TorchStorage(type.name, key, count))
                }
                'R'.code -> {
                    val args = pop() as List<*>
                    val call = pop() as Symbol
                    stack.add(when (call) {
                        Symbol("collections", "OrderedDict") -> {
                            require(args.isEmpty()); linkedMapOf<Any?, Any?>()
                        }
                        Symbol("torch._utils", "_rebuild_tensor"), Symbol("torch._utils", "_rebuild_tensor_v2") -> {
                            require(args.size in 4..7)
                            TorchTensor(args[0] as TorchStorage, args[1] as Long,
                                (args[2] as List<*>).map { it as Long }, (args[3] as List<*>).map { it as Long })
                        }
                        else -> errorSovits(SovitsImportMessage(R.string.sovits_weight_object_unsupported))
                    })
                }
                's'.code -> { val value = pop(); val key = pop(); require(key is String || key is Long); (stack.last() as MutableMap<Any?, Any?>)[key] = value }
                'u'.code -> {
                    val items = marked(); require(items.size % 2 == 0)
                    val map = stack.last() as MutableMap<Any?, Any?>
                    for (i in items.indices step 2) {
                        val key = items[i]
                        require(key is String || key is Long)
                        map[key] = items[i + 1]
                    }
                }
                'a'.code -> { val item = pop(); (stack.last() as MutableList<Any?>).add(item) }
                'e'.code -> { val items = marked(); (stack.last() as MutableList<Any?>).addAll(items) }
                'b'.code -> {
                    val state = pop() as? Map<*, *> ?: errorSovits(SovitsImportMessage(R.string.sovits_metadata_unsupported))
                    val target = stack.last()
                    if (target is Params) {
                        require(state.keys.all { it is String })
                        target.putAll(state)
                    } else {
                        // PyTorch attaches OrderedDict._metadata; it is not a model tensor or callable.
                        require(target is MutableMap<*, *> && state.keys.all { it == "_metadata" })
                    }
                }
                else -> errorSovits(SovitsImportMessage(R.string.sovits_checkpoint_opcode_unsupported, opcode))
            }
        }
        errorSovits(SovitsImportMessage(R.string.sovits_metadata_unterminated))
    }
}

internal class TorchCheckpoint(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val headers: Map<String, FileHeader>
    private val prefix: String
    val root: Map<*, *>
    val weights: Map<*, *>
    init {
        try {
            val entries = zip.fileHeaders
            requireSovits(entries.size <= 20_000) { SovitsImportMessage(R.string.sovits_checkpoint_zip_invalid) }
            headers = entries.associateBy { it.fileName }
            requireSovits(headers.size == entries.size) { SovitsImportMessage(R.string.sovits_checkpoint_zip_invalid) }
            val metadata = entries.singleOrNull { it.fileName.endsWith("/data.pkl") } ?: errorSovits(SovitsImportMessage(R.string.sovits_pytorch_zip_required))
            requireSovits(metadata.uncompressedSize in 1..16 * 1024 * 1024) { SovitsImportMessage(R.string.sovits_metadata_too_large) }
            prefix = metadata.fileName.removeSuffix("data.pkl")
            headers[prefix + "byteorder"]?.let { entry ->
                requireSovits(entry.uncompressedSize in 1..16) { SovitsImportMessage(R.string.sovits_checkpoint_big_endian) }
                val order = zip.getInputStream(entry).use { input ->
                    ByteArray(entry.uncompressedSize.toInt()).also { DataInputStream(input).readFully(it); require(input.read() == -1) }
                }
                requireSovits(order.toString(Charsets.UTF_8) == "little") { SovitsImportMessage(R.string.sovits_checkpoint_big_endian) }
            }
            root = TorchPickle(zip.getInputStream(metadata).use { input ->
                ByteArray(metadata.uncompressedSize.toInt()).also { DataInputStream(input).readFully(it); require(input.read() == -1) }
            }).read()
            weights = root["weight"] as? Map<*, *>
                ?: errorSovits(SovitsImportMessage(R.string.sovits_checkpoint_weight_missing))
        } catch (error: Throwable) { zip.close(); throw error }
    }

    fun tensor(key: String): TorchTensor = (weights[key] as? TorchTensor
        ?: errorSovits(SovitsImportMessage(R.string.sovits_checkpoint_key_missing, key))).also { tensor ->
        tensor.validate()
        val entry = headers[prefix + "data/" + tensor.storage.key] ?: errorSovits(SovitsImportMessage(R.string.sovits_weight_data_missing, key))
        requireSovits(entry.uncompressedSize == Math.multiplyExact(tensor.storage.count, tensor.storage.bytesPerElement.toLong())) { SovitsImportMessage(R.string.sovits_weight_length_mismatch, key) }
    }

    fun open(tensor: TorchTensor): InputStream {
        val stream = zip.getInputStream(headers.getValue(prefix + "data/" + tensor.storage.key))
        try {
            var remaining = Math.multiplyExact(tensor.offset, tensor.storage.bytesPerElement.toLong())
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) remaining -= skipped else { require(stream.read() != -1); remaining-- }
            }
            return stream
        } catch (error: Throwable) { stream.close(); throw error }
    }
    override fun close() = zip.close()
}
