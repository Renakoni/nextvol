package indi.renakoni.nextvol.sourceexecution

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** A bound process, independent of any one invocation's result and broker endpoints. */
internal class IsolatedWorkerConnection(private val context: Context, val authority: ExecutionAuthority,
    service: Class<out IsolatedExecutionService>) {
    val connected = CompletableDeferred<IIsolatedExecutionService>()
    val died = CompletableDeferred<Unit>()
    var lastIdentity: ExecutionIdentity? = null
    // Inspected only under this worker slot's lock. Keep the most recent ticket per
    // source lifetime so invalidated sessions cannot leave reusable library state in the worker.
    private val libraryTickets = LinkedHashMap<List<String>, ExecutionIdentity>(16, 0.75f, true)
    fun retain(identity: ExecutionIdentity) {
        libraryTickets[listOf(identity.namespace, identity.sourceId, identity.profile, identity.revision,
            identity.accountGeneration.toString())] = identity
        if (libraryTickets.size > 16) libraryTickets.entries.iterator().apply { next(); remove() }
    }
    fun hasRevokedOwner() = lastIdentity?.let { !authority.accepts(it) } == true ||
        libraryTickets.values.any { !authority.accepts(it) }
    private val closed = AtomicBoolean()
    @Volatile var remote: IIsolatedExecutionService? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IIsolatedExecutionService.Stub.asInterface(binder)
            if (closed.get() || died.isCompleted) {
                try { service.terminate() } catch (_: RemoteException) { }
                return
            }
            try {
                binder.linkToDeath({ died.complete(Unit) }, 0)
                remote = service
                connected.complete(service)
            } catch (_: RemoteException) {
                died.complete(Unit)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { died.complete(Unit) }
        override fun onNullBinding(name: ComponentName) { died.complete(Unit) }
        override fun onBindingDied(name: ComponentName) { died.complete(Unit) }
    }

    private val bound = context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)

    init { if (!bound) died.complete(Unit) }

    suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (closed.compareAndSet(false, true)) {
            try {
                try { remote?.terminate() } catch (_: RemoteException) { }
                if (remote != null) withTimeoutOrNull(2000) { died.await() }
            } finally { if (bound) context.unbindService(connection) }
        }
    }
}
