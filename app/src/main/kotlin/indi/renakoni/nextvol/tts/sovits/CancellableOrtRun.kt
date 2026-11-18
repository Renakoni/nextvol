package indi.renakoni.nextvol.tts.sovits

import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession

/** The owner closes RunOptions only after its cross-thread terminate hook is detached. */
internal fun OrtSession.runCancellable(
    inputs: Map<String, OnnxTensorLike>,
    pinnedOutputs: Map<String, OnnxTensor> = emptyMap(),
): OrtSession.Result {
    val context = GenerationContext.current()
    context?.checkCancelled()
    val options = OrtSession.RunOptions()
    var registration: AutoCloseable? = null
    try {
        options.setLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
        registration = context?.onCancel { options.setTerminate(true) }
        val result = if (pinnedOutputs.isEmpty()) run(inputs, options)
            else run(inputs, emptySet(), pinnedOutputs, options)
        try {
            context?.checkCancelled()
            return result
        } catch (error: Throwable) {
            result.close()
            throw error
        }
    } catch (error: Exception) {
        context?.checkCancelled()
        throw error
    } finally {
        registration?.close()
        options.close()
    }
}
