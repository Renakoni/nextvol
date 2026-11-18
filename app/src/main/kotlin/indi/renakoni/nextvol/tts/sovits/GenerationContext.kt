package indi.renakoni.nextvol.tts.sovits

import java.util.concurrent.CancellationException

/** A request owns its cancellation hooks; removal and termination share one lock. */
internal class GenerationContext {
    @Volatile
    private var cancelled = false

    private val hooks = linkedSetOf<() -> Unit>()

    fun checkCancelled() {
        if (cancelled) throw CancellationException("GPT-SoVITS cancelled")
    }

    @Synchronized
    fun cancel() {
        if (cancelled) return
        cancelled = true
        hooks.forEach { it() }
    }

    @Synchronized
    fun onCancel(hook: () -> Unit): AutoCloseable {
        checkCancelled()
        hooks += hook
        return AutoCloseable { synchronized(this) { hooks -= hook } }
    }

    /** Used only on the inference owner thread; no context is shared across requests. */
    fun <T> run(block: () -> T): T {
        checkCancelled()
        val previous = current.get()
        current.set(this)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<GenerationContext?>()
        fun current(): GenerationContext? = current.get()
        fun checkCurrent() { current.get()?.checkCancelled() }
    }
}
