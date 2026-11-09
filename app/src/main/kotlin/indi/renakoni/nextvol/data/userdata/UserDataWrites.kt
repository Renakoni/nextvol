package indi.renakoni.nextvol.data.userdata

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** One owner per store; only explicitly submitted settings writes enter this queue. */
internal class UserDataWrites(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val lock = Any()
    private val tails = mutableMapOf<String, Deferred<Unit>>()
    private var closed = false

    fun submit(path: String, write: suspend () -> Unit): Deferred<Unit> = synchronized(lock) {
        check(!closed) { "Settings writer is closed" }
        val previous = tails[path]
        val work = scope.async(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                write()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("UserData", "Failed to save $path", error)
                throw error
            }
        }
        tails[path] = work
        work.invokeOnCompletion {
            synchronized(lock) {
                if (tails[path] === work) tails.remove(path)
            }
        }
        work.start()
        work
    }

    /** Stop accepting work, then drain all accepted writes before the database closes. */
    suspend fun close() {
        synchronized(lock) {
            closed = true
            lifetime.complete()
        }
        lifetime.join()
    }
}
