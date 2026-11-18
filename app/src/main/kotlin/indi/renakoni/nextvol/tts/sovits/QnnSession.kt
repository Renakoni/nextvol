package indi.renakoni.nextvol.tts.sovits

import android.os.Build
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Optional Qualcomm runtime; no ORT objects are shared with the WebGPU library. */
@androidx.annotation.Keep
internal class QnnSession(model: Model, performance: String, frames: Int,
                          allowCompilation: Boolean = false, onCompile: () -> Unit = {}) : AutoCloseable {
    private val handle: Long
    private val names: Array<String>

    init {
        System.loadLibrary("nextvol-matcha")
        val cache = model.cache(frames)
        // Generation loads only a prepared context. Even if it disappears, this
        // path cannot compile the source graph while rendering an acoustic chunk.
        fun load() = if (allowCompilation) {
            if (!cache.isFile) onCompile()
            create(model.runtime.absolutePath, model.graph.absolutePath, cache.absolutePath, performance, frames)
        } else {
            create(model.runtime.absolutePath, cache.absolutePath, "", performance, frames)
        }
        handle = try { load() }
        catch (error: IllegalStateException) {
            if (!allowCompilation || !cache.delete()) throw error
            load()
        }
        names = try { inputNames(handle) }
        catch (error: Throwable) { destroy(handle); throw error }
    }

    fun step(inputs: Map<String, FloatBuffer>, output: FloatBuffer) {
        terminate(handle, false)
        val context = GenerationContext.current()
        val registration = context?.onCancel { terminate(handle, true) }
        try {
            run(handle, names.map { inputs.getValue(it) }.toTypedArray(), output)
            context?.checkCancelled()
        } catch (error: IllegalStateException) {
            context?.checkCancelled()
            throw error
        } finally { registration?.close() }
    }

    override fun close() = destroy(handle)

    /** Reused across shape buckets and eviction. Import/runtime replacement closes the owner. */
    class Model(val runtime: File, val graph: File, weights: File, namespace: String) {
        private val prefix: String

        init {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("htp-finalization-3".toByteArray())
            digest.update(Build.FINGERPRINT.toByteArray())
            digest.update(graph.readBytes())
            val dependencies = listOf(weights) +
                runtime.listFiles().orEmpty().filter { it.extension == "so" }.sortedBy { it.name }
            dependencies.forEach { file ->
                digest.update("\n${file.name}:${file.length()}:${file.lastModified()}".toByteArray())
            }
            File(runtime, "libonnxruntime-qnn.so").inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val key = digest.digest().take(12).joinToString("") { "%02x".format(it) }
            val family = ".$namespace-htp-"
            prefix = "$family$key-"
            graph.parentFile!!.listFiles()?.filter { it.name.startsWith(family) && !it.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }

        fun cache(frames: Int) = File(graph.parentFile, "$prefix$frames.onnx")
    }

    companion object {
        /** A real HTP run with CPU fallback disabled; a chip name alone is not proof of support. */
        internal fun probe(runtime: File, model: File) {
            System.loadLibrary("nextvol-matcha")
            val handle = create(runtime.absolutePath, model.absolutePath, "", "balanced", 512)
            try {
                fun buffer() = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val input = buffer().apply { put(floatArrayOf(1f, 2f, 3f, 4f)); rewind() }
                val output = buffer()
                check(inputNames(handle).contentEquals(arrayOf("x")))
                run(handle, arrayOf(input), output)
                for (i in 0 until 4) check(kotlin.math.abs(output.get(i) - ((i + 1) * .5f + .25f)) < .01f)
            } finally { destroy(handle) }
        }

        @JvmStatic private external fun create(runtime: String, model: String, cache: String, performance: String, frames: Int): Long
        @JvmStatic private external fun inputNames(handle: Long): Array<String>
        @JvmStatic private external fun run(handle: Long, inputs: Array<FloatBuffer>, output: FloatBuffer)
        @JvmStatic private external fun terminate(handle: Long, cancel: Boolean)
        @JvmStatic private external fun destroy(handle: Long)
    }
}
