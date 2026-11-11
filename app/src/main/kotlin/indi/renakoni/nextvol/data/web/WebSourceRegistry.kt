package indi.renakoni.nextvol.data.web

import android.util.Log
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** Owns registration generations; enumerating sources never constructs or loads one. */
@Singleton
class WebSourceRegistry internal constructor(private val dispatcher: CoroutineDispatcher,
    private val executionAuthority: hnovel.execution.ExecutionAuthority) {
    constructor() : this(Dispatchers.IO, hnovel.execution.ExecutionAuthority())
    @Inject constructor(executionAuthority: hnovel.execution.ExecutionAuthority) : this(Dispatchers.IO, executionAuthority)

    private val lock = Any()
    private val entries = mutableMapOf<Identifier, Entry>()
    private val registrationSequence = java.util.concurrent.atomic.AtomicLong()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableSources = MutableStateFlow<List<SourceListing>>(emptyList())
    val sources = mutableSources.asStateFlow()
    private val mutableRestoration = MutableStateFlow(SourceRestoration.Ready)
    val restoration = mutableRestoration.asStateFlow()
    val restoredSources = flow { awaitRestoration(); emitAll(sources) }
    private var restorationJob: kotlinx.coroutines.Deferred<Unit>? = null

    internal fun restoreIn(scope: CoroutineScope, restore: suspend () -> Boolean) {
        val job = scope.async<Unit>(start = CoroutineStart.LAZY) {
            var failed = true
            try { failed = !restore() }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                Log.e("WebSourceRegistry", "Could not restore source metadata", failure)
            } finally { mutableRestoration.value = if (failed) SourceRestoration.Failed else SourceRestoration.Ready }
        }
        restorationJob = job
        mutableRestoration.value = SourceRestoration.Restoring
        job.start()
    }

    suspend fun awaitRestoration() { restorationJob?.await() }

    fun register(metadata: SourceMetadata, create: suspend () -> WebBookDataSource): SourceRegistration =
        add(metadata, create, preconstructed = null)

    private fun add(metadata: SourceMetadata, create: suspend () -> WebBookDataSource, preconstructed: WebBookDataSource?,
        publishImmediately: Boolean = true): SourceRegistration {
        val snapshot = metadata.copy(capabilities = Collections.unmodifiableSet(metadata.capabilities.toSet()))
        val entry = synchronized(lock) {
            require(snapshot.id !in entries) { "Duplicate source identity: ${snapshot.id}" }
            Entry(snapshot, create, preconstructed).also {
                entries[snapshot.id] = it
                if (publishImmediately) publish()
            }
        }
        return SourceRegistration(snapshot) { remove(entry) }.also { it.owner = entry }
    }

    /** Durable commit precedes publication under the same lock as resolve/remove. No missing-source gap. */
    internal fun replace(expected: SourceRegistration, source: WebBookDataSource, metadata: SourceMetadata,
        ticket: hnovel.execution.ExecutionIdentity? = null, persist: () -> Unit): SourceRegistration {
        require(source.id == metadata.id && metadata.id == expected.metadata.id)
        require(ticket == null || ticket.sourceId == metadata.id.id && ticket.namespace == metadata.id.namespace &&
            ticket.revision == metadata.revision && ticket.accountGeneration == metadata.accountGeneration)
        val snapshot = metadata.copy(capabilities = Collections.unmodifiableSet(metadata.capabilities.toSet()))
        val previous: Entry
        val next: Entry
        synchronized(lock) {
            previous = checkNotNull(entries[metadata.id])
            check(previous === expected.owner) { "Source registration changed" }
            next = Entry(snapshot, { source }, source)
            if (ticket != null) executionAuthority.replaceSource(ticket) { persist() }
            else {
                // Native HTTP adapters have no script ticket; their runtime still owns all work.
                persist()
                executionAuthority.revokeSource(metadata.id.id, metadata.id.namespace)
            }
            entries[metadata.id] = next
            // Observers may resume inline in publish(); the old handle must already be
            // unavailable. Retirement schedules resource cleanup without waiting for it.
            previous.retire()
            publish()
        }
        return SourceRegistration(snapshot) { remove(next) }.also {
            it.owner = next
            indi.renakoni.nextvol.data.image.SourceImageRetryEvents.request(snapshot.id)
        }
    }

    fun register(source: WebBookDataSource, metadata: SourceMetadata): SourceRegistration {
        require(source.id == metadata.id) { "Source and metadata identities must match" }
        return add(metadata, { source }, preconstructed = source)
    }

    /** Startup registrations share one listing build; other mutations still publish immediately. */
    internal fun registerBatch(block: ((SourceMetadata, suspend () -> WebBookDataSource) -> SourceRegistration) -> Unit) {
        var changed = false
        try {
            block { metadata, create ->
                add(metadata, create, null, false).also { changed = true }
            }
        } finally {
            if (changed) synchronized(lock) { publish() }
        }
    }

    fun unregister(id: Identifier) {
        synchronized(lock) {
            executionAuthority.revokeSource(id.id, id.namespace)
            entries.remove(id)?.retire()
            publish()
        }
    }

    private fun remove(entry: Entry) {
        synchronized(lock) {
            if (entries[entry.metadata.id] !== entry) return
            executionAuthority.revokeSource(entry.metadata.id.id, entry.metadata.id.namespace)
            entries.remove(entry.metadata.id)
            // Inline observers must already see retired handles when a disabled source disappears.
            entry.retire()
            publish()
        }
    }

    suspend fun resolve(id: Identifier): SourceResolution {
        awaitRestoration()
        val entry = synchronized(lock) { entries[id] } ?: return if (restoration.value == SourceRestoration.Failed)
            SourceResolution.Unavailable(id, IllegalStateException("Source metadata restoration failed"))
            else SourceResolution.Missing(id)
        return try {
            val runtime = entry.initialization.await()
            synchronized(lock) {
                if (entries[id] === entry && runtime.isAvailable) SourceResolution.Ready(runtime)
                else SourceResolution.Missing(id)
            }
        } catch (failure: Exception) {
            // A cancelled caller remains cancelled. Retiring a source instead invalidates
            // its generation; it must not silently resolve a replacement with the same ID.
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (entries[id] !== entry) SourceResolution.Missing(id)
                else SourceResolution.Unavailable(id, failure)
            }
        }
    }

    /** Cache reads belong to the registration even when no runtime needs initializing. */
    internal fun cancelOnRetirement(id: Identifier, generation: Long, request: kotlinx.coroutines.Job): kotlinx.coroutines.DisposableHandle =
        synchronized(lock) {
            val entry = entries[id]?.takeIf { it.generation == generation } ?: throw SourceUnavailableException(id)
            entry.cancelOnRetirement(request)
        }

    internal fun refreshDiscovery(id: Identifier) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        entry.discoveryGeneration++
        entry.resolvedFeed = null
        publish()
    }

    private fun publish() {
        mutableSources.value = Collections.unmodifiableList(entries.values
            .map { SourceListing(it.metadata, it.status, it.generation, it.resolvedFeed, it.discoveryGeneration) }
            .sortedWith(compareBy({ !it.metadata.builtIn }, { it.metadata.id.namespace }, { it.metadata.id.id })))
    }

    private fun update(entry: Entry, status: SourceStatus) = synchronized(lock) {
        if (entries[entry.metadata.id] === entry) {
            entry.status = status
            publish()
        }
    }

    private inner class Entry(val metadata: SourceMetadata, create: suspend () -> WebBookDataSource,
        private val preconstructed: WebBookDataSource?) {
        val generation = registrationSequence.incrementAndGet()
        private val lifetime = CoroutineScope(SupervisorJob() + dispatcher)
        private val ownership = Any()
        private var retired = false
        private var started = false
        private var runtime: SourceRuntime? = null
        var status = SourceStatus.Registered
        var resolvedFeed: Boolean? = null
        var discoveryGeneration: Long = 0

        fun cancelOnRetirement(request: kotlinx.coroutines.Job) =
            requireNotNull(lifetime.coroutineContext[kotlinx.coroutines.Job]).invokeOnCompletion { request.cancel() }

        val initialization = lifetime.async(start = CoroutineStart.LAZY) {
            val start = if (indi.renakoni.nextvol.BuildConfig.BENCHMARK) android.os.SystemClock.elapsedRealtimeNanos() else 0L
            update(this@Entry, SourceStatus.Initializing)
            var source: WebBookDataSource? = null
            try {
                synchronized(ownership) {
                    if (retired) throw SourceUnavailableException(metadata.id)
                    started = true
                }
                source = create()
                require(source.id == metadata.id) { "Source and metadata identities must match" }
                source.onLoad()
                currentCoroutineContext().ensureActive()
                synchronized(ownership) {
                    check(!retired) { "Source was removed during initialization" }
                    SourceRuntime(metadata, source, lifetime, cleanupScope) { hasFeed ->
                        synchronized(lock) {
                            if (entries[metadata.id] === this@Entry && resolvedFeed != hasFeed) {
                                resolvedFeed = hasFeed
                                publish()
                            }
                        }
                    }.also { runtime = it }
                }.also { update(this@Entry, SourceStatus.Ready) }
            } catch (failure: Throwable) {
                // A constructed source which never reaches Ready is still our resource.
                if (runtime == null) runCatching { (source as? AutoCloseable)?.close() }
                    .exceptionOrNull()?.let(failure::addSuppressed)
                if (failure !is CancellationException) update(this@Entry, SourceStatus.Failed)
                throw failure
            } finally {
                if (indi.renakoni.nextvol.BuildConfig.BENCHMARK) {
                    val end = android.os.SystemClock.elapsedRealtimeNanos()
                    Log.i("NextVolStartup", "end phase=source-runtime source=${metadata.id} status=$status timeNs=$end durationNs=${end - start}")
                }
            }
        }

        fun retire() = synchronized(ownership) {
            retired = true
            lifetime.cancel()
            runtime?.retire()
            if (!started && preconstructed is AutoCloseable) {
                cleanupScope.launch {
                    runCatching { preconstructed.close() }.onFailure {
                        Log.w("WebSourceRegistry", "Could not close unloaded source ${metadata.id}", it)
                    }
                }
            }
        }
    }
}
