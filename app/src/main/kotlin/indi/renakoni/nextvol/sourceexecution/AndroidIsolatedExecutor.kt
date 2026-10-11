package indi.renakoni.nextvol.sourceexecution

import android.app.ActivityManager
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionIdentity
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.ExecutionWire
import hnovel.execution.ExecutionPayload
import hnovel.execution.FailureCode
import hnovel.execution.SourceExecutionBroker
import hnovel.execution.BridgeWire
import hnovel.execution.SourceLibraryDefinition
import hnovel.execution.LibraryTooLarge
import hnovel.execution.libraryCode
import hnovel.execution.SourceWorkQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import indi.renakoni.nextvol.BuildConfig

/** Stateful rules keep source-affine lanes; independent work uses separate, safely retireable processes. */
class AndroidIsolatedExecutor @Inject constructor(@ApplicationContext context: Context, private val authority: ExecutionAuthority) {
    private val context = context.applicationContext
    private val pool = synchronized(poolLock) {
        sharedPool ?: WorkerPool(independentWorkerParallelism(Runtime.getRuntime().availableProcessors(),
            this.context.getSystemService(ActivityManager::class.java).isLowRamDevice)).also { sharedPool = it }
    }
    internal val parallelism get() = pool.parallelism

    /** Bind only this group's potential workers. Readiness overlaps transport; busy slots are left alone. */
    suspend fun prepareIndependent(identity: ExecutionIdentity, count: Int) = withContext(Dispatchers.IO) {
        if (!authority.accepts(identity)) return@withContext
        val slots = synchronized(pool.idleWorkers) {
            pool.idleWorkers.descendingIterator().asSequence().take(count.coerceIn(0, parallelism)).toList()
        }
        for (slot in slots) {
            currentCoroutineContext().ensureActive()
            if (!slot.lock.tryLock()) continue
            try {
                if (!authority.accepts(identity)) return@withContext
                if (slot.worker == null && slot.retiringBinder?.isBinderAlive != true) {
                    // Preparation is optional; actual execution still reports binding failures.
                    slot.worker = try { IsolatedWorkerConnection(this@AndroidIsolatedExecutor.context, authority, slot.service) }
                        catch (_: Exception) { null }
                    if (BuildConfig.DEBUG && slot.worker != null) android.util.Log.d("RuleExecutionTrace",
                        "slot=${slot.id} phase=Prepare parallelism=$parallelism")
                }
                scheduleRetirement(slot)
            } finally { slot.lock.unlock() }
        }
    }

    suspend fun execute(identity: ExecutionIdentity, task: ExecutionTask,
        limits: ExecutionLimits = ExecutionLimits(), broker: SourceExecutionBroker? = null): ExecutionResult = withContext(Dispatchers.IO) {
        if (broker != null && (broker.identity != identity || broker.limits != limits)) return@withContext failure(FailureCode.InvalidIdentity)
        if (broker != null && !broker.matchesTaskContext(task)) return@withContext failure(FailureCode.InvalidTask)
        if (!authority.accepts(identity)) return@withContext failure(FailureCode.InvalidIdentity)
        if (task is ExecutionTask.Rule && task.readOnly && !task.libraryCode.isNullOrBlank())
            return@withContext failure(FailureCode.InvalidTask)
        val request = ExecutionWire.encode(identity, task, limits)
        if (request.size > ExecutionWire.MAX_INPUT_BYTES) return@withContext failure(FailureCode.InputLimit)
        val queuedAt = SystemClock.elapsedRealtime()
        val call = nextCall.incrementAndGet()
        try {
            withWorker(identity, task) { slot ->
                val admittedAt = SystemClock.elapsedRealtime()
                if (!authority.accepts(identity)) return@withWorker failure(FailureCode.Revoked)
                // Each component has its own process. Never rebind it before its previous worker dies.
                if (slot.retiringBinder?.isBinderAlive == true) return@withWorker failure(FailureCode.ProcessExited)
                val previous = slot.worker
                if (previous != null && (previous.authority !== authority || previous.died.isCompleted || previous.hasRevokedOwner())) retire(slot, previous)
                if (slot.retiringBinder?.isBinderAlive == true) return@withWorker failure(FailureCode.ProcessExited)
                val worker = slot.worker ?: IsolatedWorkerConnection(context, authority, slot.service).also { slot.worker = it }
                val started = SystemClock.elapsedRealtime()
                val active = activeCalls.incrementAndGet()
                trace(call, slot, task, "Start", "queueMs=${admittedAt - queuedAt} active=$active")
                var outcome = "Cancelled"
                try {
                    invoke(slot, worker, identity, task, limits, broker).also {
                        outcome = (it as? ExecutionResult.Failure)?.code?.name ?: "Success"
                    }
                } finally {
                    trace(call, slot, task, "End", "elapsedMs=${SystemClock.elapsedRealtime() - started} result=$outcome active=${activeCalls.decrementAndGet()}")
                }
            }
        } finally {
            broker?.close()
        }
    }

    /** Release retained library state when the owning runtime is shut down. */
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        pool.workers.forEach { slot ->
            slot.lock.withLock { slot.worker?.takeIf { it.authority === authority }?.let { retire(slot, it) } }
        }
    }

    private suspend fun retire(slot: WorkerSlot, worker: IsolatedWorkerConnection) {
        slot.idleRetirement?.cancel()
        slot.idleRetirement = null
        slot.retiringBinder = worker.remote?.asBinder()
        try { worker.close() } finally { if (slot.worker === worker) slot.worker = null }
    }

    /** Only independent slots may decay: source-affine workers can own mutable library state. */
    private fun scheduleRetirement(slot: WorkerSlot) {
        slot.idleRetirement?.cancel()
        slot.idleRetirement = null
        val worker = slot.worker ?: return
        slot.idleRetirement = pool.maintenance.launch {
            delay(INDEPENDENT_IDLE_MILLIS)
            slot.lock.withLock {
                if (slot.worker !== worker || slot.idleRetirement !== currentCoroutineContext()[Job]) return@withLock
                slot.idleRetirement = null
                withContext(NonCancellable) { retire(slot, worker) }
                if (BuildConfig.DEBUG) android.util.Log.d("RuleExecutionTrace", "slot=${slot.id} phase=IdleRetire")
            }
        }
    }

    private suspend fun invoke(slot: WorkerSlot, worker: IsolatedWorkerConnection, identity: ExecutionIdentity, task: ExecutionTask,
        limits: ExecutionLimits, broker: SourceExecutionBroker?): ExecutionResult = coroutineScope {
        val result = CompletableDeferred<ExecutionResult>()
        val finished = AtomicBoolean()
        val callingBroker = AtomicBoolean()
        var keepWorker = false
        val brokerCalls = SupervisorJob(coroutineContext[Job])
        val revocations = launch {
            while (!result.isCompleted) {
                if (!authority.accepts(identity)) {
                    result.complete(failure(FailureCode.Revoked))
                    break
                }
                delay(10)
            }
        }
        try {
            // Android class loading is not script execution. Bound cold startup separately;
            // cancellation/revocation still interrupts it and no task is submitted before readiness.
            val waitingAt = SystemClock.elapsedRealtime()
            val service = withTimeoutOrNull(15000) {
                select<IIsolatedExecutionService?> {
                    worker.connected.onAwait { it }
                    worker.died.onAwait { null }
                    result.onAwait { null }
                }
            } ?: return@coroutineScope when {
                result.isCompleted -> result.await()
                worker.died.isCompleted -> failure(FailureCode.ProcessExited)
                else -> failure(FailureCode.Timeout)
            }
            if (BuildConfig.DEBUG) android.util.Log.d("RuleExecutionTrace",
                "slot=${slot.id} phase=Ready waitMs=${SystemClock.elapsedRealtime() - waitingAt}")
            val completed = withTimeoutOrNull(limits.timeoutMillis) {
                val definition = task.libraryCode()
                val scripts = if (SourceLibraryDefinition.isUrlMap(definition)) {
                    val host = broker ?: return@withTimeoutOrNull failure(FailureCode.BridgeDenied)
                    // Keep a denied download from cancelling the invocation's parent scope before
                    // its structured failure can be returned and the worker retired.
                    val loading = async { runCatching { host.loadLibrary(checkNotNull(definition)) } }
                    try {
                        select<List<String>?> {
                            loading.onAwait { it.getOrThrow() }
                            result.onAwait { null }
                            worker.died.onAwait { null }
                        } ?: return@withTimeoutOrNull if (result.isCompleted) result.await() else failure(FailureCode.ProcessExited)
                    } finally { loading.cancel() }
                } else null
                val request = ExecutionWire.encode(identity, task, limits, scripts)
                if (request.size > ExecutionWire.MAX_INPUT_BYTES) return@withTimeoutOrNull failure(FailureCode.InputLimit)
                val packet = ExecutionPayload.pack(request, ExecutionWire.MAX_INPUT_PACKET_BYTES)
                    ?: return@withTimeoutOrNull failure(FailureCode.InputLimit)
                val workerUid = service.workerUid()
                if (workerUid == Process.myUid()) return@withTimeoutOrNull failure(FailureCode.InvalidIdentity)
                if (!authority.accepts(identity)) return@withTimeoutOrNull failure(FailureCode.Revoked)
                val brokerBinder = object : IExecutionBroker.Stub() {
                    override fun call(operation: String, arguments: ByteArray): ParcelFileDescriptor {
                        if (Binder.getCallingUid() != workerUid || finished.get() || !authority.accepts(identity))
                            throw SecurityException("Invalid worker invocation")
                        check(operation.length <= 256 && arguments.size <= IsolatedExecutionService.MAX_IPC_BYTES)
                        check(callingBroker.compareAndSet(false, true)) { "Concurrent bridge call" }
                        try {
                            val host = checkNotNull(broker) { "No broker capability" }
                            val args = BridgeWire.arguments(arguments)
                            val value = runBlocking { withContext(Dispatchers.IO + brokerCalls) { host.call(operation, args) } }
                            val reply = value.toString().toByteArray(Charsets.UTF_8)
                            check(reply.size <= BridgeWire.MAX_REPLY_BYTES && !finished.get() && authority.accepts(identity))
                            val ends = ParcelFileDescriptor.createPipe()
                            launch(Dispatchers.IO + brokerCalls) {
                                runCatching { ParcelFileDescriptor.AutoCloseOutputStream(ends[1]).use { it.write(reply) } }
                            }.invokeOnCompletion { runCatching { ends[1].close() } }
                            return ends[0]
                        } finally { callingBroker.set(false) }
                    }
                }
                service.execute(packet, object : IExecutionCallback.Stub() {
                    private val received = AtomicBoolean()

                    private fun accept(): Boolean = Binder.getCallingUid() == workerUid &&
                        !finished.get() && received.compareAndSet(false, true)

                    private fun decode(bytes: ByteArray): ExecutionResult {
                        if (!authority.accepts(identity)) return failure(FailureCode.Revoked)
                        val decoded = ExecutionWire.decodeResult(ExecutionPayload.unpack(bytes, ExecutionWire.MAX_RESULT_BYTES))
                        return if (decoded is ExecutionResult.Success && decoded.output.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes)
                            failure(FailureCode.OutputLimit) else decoded
                    }

                    override fun onResult(bytes: ByteArray) {
                        if (!accept()) return
                        val reply = if (bytes.size > IsolatedExecutionService.MAX_IPC_BYTES) failure(FailureCode.OutputLimit)
                            else try { decode(bytes) } catch (_: Exception) { failure(FailureCode.InvalidTask) }
                        result.complete(reply)
                    }

                    override fun onResultFile(pipe: ParcelFileDescriptor) {
                        if (!accept()) { pipe.close(); return }
                        launch(Dispatchers.IO + brokerCalls) {
                            try {
                                result.complete(decode(readExecutionResultPacket(pipe)))
                            } catch (cancelled: CancellationException) { throw cancelled }
                              catch (_: Exception) { result.complete(failure(FailureCode.InvalidTask)) }
                        }.invokeOnCompletion { runCatching { pipe.close() } }
                    }
                }, brokerBinder)
                select {
                    result.onAwait { it }
                    worker.died.onAwait { failure(FailureCode.ProcessExited) }
                }
            } ?: failure(FailureCode.Timeout)
            val accepted = if (authority.accepts(identity)) completed else failure(FailureCode.Revoked)
            keepWorker = accepted is ExecutionResult.Success && !worker.died.isCompleted
            if (keepWorker) {
                worker.lastIdentity = identity
                if (!task.libraryCode().isNullOrBlank()) worker.retain(identity)
            }
            accepted
        } catch (_: RemoteException) {
            failure(FailureCode.ProcessExited)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: LibraryTooLarge) { failure(FailureCode.InputLimit) }
          catch (_: Exception) { if (authority.accepts(identity)) failure(FailureCode.BridgeDenied) else failure(FailureCode.Revoked) }
        finally {
            finished.set(true)
            brokerCalls.cancel()
            broker?.close()
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                revocations.cancelAndJoin()
                if (!keepWorker) retire(slot, worker)
            }
        }
    }

    private fun failure(code: FailureCode) = ExecutionResult.Failure(code)

    private class WorkerSlot(val id: Int, val service: Class<out IsolatedExecutionService>) {
        val lock = SourceWorkQueue()
        var retiringBinder: IBinder? = null
        var worker: IsolatedWorkerConnection? = null
        var idleRetirement: Job? = null
    }

    private suspend fun <T> withWorker(identity: ExecutionIdentity, task: ExecutionTask, block: suspend (WorkerSlot) -> T): T {
        val independent = when (task) {
            is ExecutionTask.ContentMarkup, is ExecutionTask.BookOverviews, is ExecutionTask.SearchBooks, is ExecutionTask.DiscoveryReadPlan -> true
            is ExecutionTask.Rule -> task.readOnly
            else -> false
        }
        if (!independent) return pool.statefulWorkers[statefulWorkerIndex(identity, pool.statefulWorkers.size)]
            .let { slot -> slot.lock.withLock { block(slot) } }
        return pool.admission.withLock {
            // Prefer the last used process so sequential work does not cold-start the whole pool.
            val slot = synchronized(pool.idleWorkers) { pool.idleWorkers.removeLast() }
            try { slot.lock.withLock {
                slot.idleRetirement?.cancel()
                slot.idleRetirement = null
                try { block(slot) } finally { scheduleRetirement(slot) }
            } }
            finally { synchronized(pool.idleWorkers) { pool.idleWorkers.addLast(slot) } }
        }
    }

    private fun trace(call: Long, slot: WorkerSlot, task: ExecutionTask, phase: String, detail: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("RuleExecutionTrace",
            "call=$call slot=${slot.id} task=${task.javaClass.simpleName} phase=$phase $detail thread=${Thread.currentThread().name}")
    }

    private class WorkerPool(independentBudget: Int) {
        val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val workers = listOf(IsolatedExecutionService::class.java,
            IndependentExecutionService1::class.java, IndependentExecutionService2::class.java,
            IndependentExecutionService3::class.java, IndependentExecutionService4::class.java)
            .take(independentBudget + 1).mapIndexed { index, service -> WorkerSlot(index, service) }
        // Repartition the existing budget, never add processes. Small/low-RAM pools stay unchanged.
        val statefulWorkers = workers.take(if (independentBudget >= 3) 2 else 1)
        private val independentWorkers = workers.drop(statefulWorkers.size)
        val parallelism = independentWorkers.size
        val idleWorkers = java.util.ArrayDeque(independentWorkers.reversed())
        val admission = SourceWorkQueue(parallelism, maxPriorityBypasses = 8)
    }

    companion object {
        internal const val INDEPENDENT_IDLE_MILLIS = 60_000L
        private val poolLock = Any()
        private var sharedPool: WorkerPool? = null
        private val nextCall = AtomicLong()
        private val activeCalls = AtomicInteger()
    }
}

internal fun independentWorkerParallelism(processors: Int, lowRamDevice: Boolean): Int =
    if (lowRamDevice) 1 else processors.coerceIn(1, 4)

internal fun statefulWorkerIndex(identity: ExecutionIdentity, count: Int): Int =
    Math.floorMod(listOf(identity.namespace, identity.sourceId, identity.profile).hashCode(), count)

/** Polling keeps a stalled pipe cancellable without blocking a Binder thread. */
internal suspend fun readExecutionResultPacket(pipe: ParcelFileDescriptor): ByteArray =
    ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val poll = StructPollfd().apply { fd = pipe.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (Os.poll(arrayOf(poll), 100) == 0) continue
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= ExecutionWire.MAX_RESULT_BYTES)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
