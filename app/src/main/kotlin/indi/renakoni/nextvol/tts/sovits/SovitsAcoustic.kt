package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

internal const val MAX_SOVITS_PCM_SAMPLES = (8 * 1024 * 1024 - 44) / 2

/** V2's autoregressive decoder stays on CPU. The acoustic stage owns its separate WebGPU runtime. */
internal class SovitsAcoustic(
    private val voice: GptSovitsVoice, private val ge: FloatArray, private val advanced: FloatArray,
) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessions = mutableMapOf<String, OrtSession>()
    private var htp: V2HtpVocoder? = null

    fun prepare(context: Context, runtime: File?) {
        val directory = voice.model
        SplitVocoderAssets.prepare(voice, runtime != null)
        if (runtime != null) {
            val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val channels = if (voice.version == GptSovitsVersion.V2Pro) 512 else 768
            htp = V2HtpVocoder(environment, ::session, directory, runtime, updated, channels)
        }
    }

    private fun session(name: String): OrtSession = sessions.getOrPut(name) {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 6))
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setMemoryPatternOptimization(false)
            if (name == SplitVocoderAssets.HIFIGAN) options.addWebGPU(emptyMap())
            environment.createSession(File(voice.model, name).absolutePath, options)
        }
    }

    fun generate(phones: LongArray, tokens: LongArray): ByteArray {
        val inputs = mutableMapOf<String, OnnxTensor>()
        val start = SystemClock.elapsedRealtime()
        try {
            val encoder = session(SplitVocoderAssets.ENCODER)
            inputs["text_seq"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(phones), longArrayOf(1, phones.size.toLong()))
            inputs["pred_semantic"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), longArrayOf(1, 1, tokens.size.toLong()))
            fun condition(values: FloatArray) = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
                longArrayOf(1, values.size.toLong(), 1))
            inputs["ge"] = condition(ge)
            inputs["ge_advanced"] = condition(advanced)
            inputs["speed"] = OnnxTensor.createTensor(environment, DoubleBuffer.wrap(doubleArrayOf(voice.options.speed.toDouble())), longArrayOf(1))
            return encoder.runCancellable(inputs).use { result ->
                val value = result[0] as OnnxTensor
                val speaker = inputs.getValue("ge")
                htp?.generate(value, speaker)?.let { return@use SovitsNative.pcm(it) }
                val vocoderInputs = mapOf(SplitVocoderAssets.BOUNDARY to value, "ge" to speaker)
                val encoded = SystemClock.elapsedRealtime()
                session(SplitVocoderAssets.HIFIGAN).runCancellable(vocoderInputs).use {
                    val audio = pcm(it[0] as OnnxTensor)
                    if (Log.isLoggable("NextVolSoVITS", Log.DEBUG)) {
                        Log.d("NextVolSoVITS", "WebGPU encoder_ms=${encoded - start} hifigan_ms=${SystemClock.elapsedRealtime() - encoded}")
                    }
                    audio
                }
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    private fun pcm(value: OnnxTensor): ByteArray {
        val count = value.info.numElements
        require(count in 1..MAX_SOVITS_PCM_SAMPLES.toLong())
        val audio = FloatArray(count.toInt())
        value.floatBuffer.get(audio)
        return SovitsNative.pcm(audio)
    }

    override fun close() {
        htp?.close()
        htp = null
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
