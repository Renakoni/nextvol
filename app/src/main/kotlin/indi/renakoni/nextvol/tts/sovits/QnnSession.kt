package indi.renakoni.nextvol.tts.sovits

import android.os.Build
import indi.renakoni.nextvol.tts.runtime.SpeechLibraries
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Optional Qualcomm runtime; no ORT objects are shared with the WebGPU library. */
@androidx.annotation.Keep
internal class QnnSession(runtime: File, graph: File, appUpdatedAt: Long) : AutoCloseable {
    private val handle: Long

    init {
        val weights = File(graph.parentFile, "vits_fp32.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${Build.FINGERPRINT}:$appUpdatedAt:${weights.length()}:${weights.lastModified()}".toByteArray())
        val key = digest.digest(graph.readBytes()).take(12).joinToString("") { "%02x".format(it) }
        val cache = File(graph.parentFile, ".v2-htp-$key.onnx")
        graph.parentFile!!.listFiles()?.filter { it != cache && CACHE_NAME.matches(it.name) }?.forEach { it.delete() }
        fun load() = create(runtime.absolutePath, graph.absolutePath, cache.absolutePath)
        val existingCache = cache.isFile
        handle = try { load() } catch (failure: IllegalStateException) {
            if (!existingCache || !cache.delete()) throw failure
            load()
        }
    }

    fun step(input: FloatBuffer, output: FloatBuffer) {
        val context = GenerationContext.current()
        val registration = context?.onCancel { terminate(handle) }
        try {
            run(handle, input, output)
        } catch (error: IllegalStateException) {
            context?.checkCancelled()
            throw error
        } finally { registration?.close() }
    }

    override fun close() = destroy(handle)

    companion object {
        private val CACHE_NAME = Regex("""\.v2-htp-[0-9a-f]{24}\.onnx""")

        /** A real HTP run with CPU fallback disabled; a chip name alone is not proof of support. */
        internal fun probe(runtime: File, model: File) {
            SpeechLibraries.loadSovitsBridge()
            val handle = create(runtime.absolutePath, model.absolutePath, "")
            try {
                fun buffer() = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                val input = buffer().apply { put(floatArrayOf(1f, 2f, 3f, 4f)); rewind() }
                val output = buffer()
                run(handle, input, output)
                for (i in 0 until 4) check(kotlin.math.abs(output.get(i) - ((i + 1) * .5f + .25f)) < .01f)
            } finally { destroy(handle) }
        }

        @JvmStatic private external fun create(runtime: String, model: String, cache: String): Long
        @JvmStatic private external fun run(handle: Long, input: FloatBuffer, output: FloatBuffer)
        @JvmStatic private external fun terminate(handle: Long)
        @JvmStatic private external fun destroy(handle: Long)
    }
}
