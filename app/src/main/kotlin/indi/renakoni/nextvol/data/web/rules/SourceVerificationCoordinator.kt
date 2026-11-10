package indi.renakoni.nextvol.data.web.rules

import hnovel.content.SourceContentException
import hnovel.content.SourceScriptInteraction
import hnovel.content.SourceScriptInteractionRequired
import hnovel.content.SourceVerification
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.data.web.BackgroundSourceRequest
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.data.web.SourceStatus
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class VerificationOwner(val source: Identifier, val revision: String, val generation: Long)
data class VerificationPrompt(val id: String, val owner: VerificationOwner, val name: String,
    val kind: hnovel.network.BrowserChallengeKind?, val foreground: Boolean, val opening: Boolean = false,
    val certificate: hnovel.network.CertificateProblem? = null, val confirmingCertificate: Boolean = false)

/** Foreground operations open verification directly; background work only leaves a notice. */
@Singleton
class SourceVerificationCoordinator @Inject constructor(private val registry: WebSourceRegistry) {
    private data class Session(val owner: VerificationOwner, val kind: hnovel.network.BrowserChallengeKind, val origin: String)
    private data class Outcome(val sequence: Long, val failure: Throwable? = null)
    private data class Pending(val prompt: VerificationPrompt, val verification: SourceVerification?,
        val approval: CompletableDeferred<Boolean> = CompletableDeferred(),
        val background: BackgroundSourceRequest? = null,
        val scriptRetry: (suspend () -> Unit)? = null,
        val started: Long = Long.MAX_VALUE,
        val claimed: AtomicBoolean = AtomicBoolean(),
        val scriptClaimed: AtomicBoolean = AtomicBoolean(),
        val scriptForeground: ForegroundSourceRequest = ForegroundSourceRequest(),
        val scriptFinished: CompletableDeferred<Unit> = CompletableDeferred()) {
        val session = if (prompt.certificate != null) null else prompt.kind?.let { kind ->
            verification?.origin?.let { Session(prompt.owner, kind, it) }
        }
    }
    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val outcomes = mutableMapOf<Session, Outcome>()
    private var sequence = 0L
    private val mutable = MutableStateFlow<List<VerificationPrompt>>(emptyList())
    val prompts = mutable.asStateFlow()
    private val browser = Mutex()

    private fun current(owner: VerificationOwner) = registry.sources.value.any {
        it.metadata.id == owner.source && it.metadata.revision == owner.revision &&
            it.metadata.accountGeneration == owner.generation && it.status == SourceStatus.Ready
    }
    private fun publish() { mutable.value = pending.values.map { it.prompt } }
    private fun remove(id: String) = synchronized(lock) {
        pending.remove(id)?.let {
            it.approval.complete(false)
            it.scriptFinished.cancel()
            it.scriptForeground.setActive(false)
        }
        publish()
    }

    private fun newerOutcome(entry: Pending) = outcomes[entry.session]?.takeIf { it.sequence > entry.started }

    private fun enqueue(entry: Pending): Outcome? = synchronized(lock) {
        val outcome = newerOutcome(entry)
        if (!entry.prompt.foreground && outcome != null && outcome.failure == null) return@synchronized outcome
        // Keep each download's own continuation, including waiters arriving while a window is open.
        if (!entry.prompt.foreground) pending.entries.removeAll {
            (!it.value.prompt.foreground && !it.value.prompt.opening && !it.value.prompt.confirmingCertificate &&
                it.value.prompt.owner == entry.prompt.owner && it.value.session == entry.session &&
                it.value.background === entry.background).also { removed -> if (removed) it.value.approval.complete(false) }
        }
        pending[entry.prompt.id] = entry
        publish()
        outcome
    }

    fun dismiss(id: String) = remove(id)

    /** Expiry must not cancel a browser or certificate confirmation opened at the deadline. */
    fun expireBackgroundNotice(id: String) = synchronized(lock) {
        val entry = pending[id] ?: return@synchronized
        if (entry.background != null) return@synchronized
        val prompt = entry.prompt
        if (!prompt.foreground && !prompt.opening && !prompt.confirmingCertificate) remove(id)
    }

    /** Only the confirmation dialog calls this. Merely opening a prompt grants nothing. */
    fun approveCertificate(id: String) = synchronized(lock) {
        val entry = pending[id] ?: return@synchronized
        if (entry.prompt.confirmingCertificate && !entry.prompt.opening && current(entry.prompt.owner)) entry.approval.complete(true)
    }

    /** The visible host collects this; retirement also removes stale background notices. */
    suspend fun observeRetirement(): Unit = registry.sources.collect {
        synchronized(lock) {
            val retired = pending.values.filter { !current(it.prompt.owner) }
            retired.forEach { entry -> remove(entry.prompt.id) }
            outcomes.keys.removeAll { !current(it.owner) }
            publish()
        }
    }

    private suspend fun open(entry: Pending) {
        val waiters = browser.withLock {
            val outcome = synchronized(lock) {
                if (pending[entry.prompt.id]?.approval !== entry.approval) throw CancellationException("Verification dismissed")
                newerOutcome(entry)
            }
            if (outcome != null) {
                if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "browser.verification")
                outcome.failure?.let { throw it }
                return@withLock emptyList<Pending>()
            }
            try { openWithCertificate(entry) }
            catch (failure: Throwable) {
                synchronized(lock) { entry.session?.let { outcomes[it] = Outcome(++sequence, failure) } }
                throw failure
            }
            synchronized(lock) {
                val session = entry.session ?: return@synchronized emptyList<Pending>()
                outcomes[session] = Outcome(++sequence)
                pending.values.filter { !it.prompt.foreground && it.session == session && !it.claimed.get() }
            }
        }
        for (waiter in waiters) {
            val claimed = synchronized(lock) { pending[waiter.prompt.id]?.approval === waiter.approval &&
                waiter.claimed.compareAndSet(false, true) }
            if (claimed) try {
                waiter.background?.prepareResume()?.invoke(null, null)
            } finally { remove(waiter.prompt.id) }
        }
    }

    private suspend fun openWithCertificate(entry: Pending) {
        try { completeVerification(entry) }
        catch (failure: SourceContentException) {
            val verification = failure.verification
            if (entry.prompt.certificate != null || failure.code != hnovel.content.ContentError.Certificate ||
                verification?.certificate == null) throw failure
            // A verification page can itself fail TLS. Keep the original browser action,
            // ask for this certificate separately, then resume that action only once.
            val certificate = Pending(entry.prompt.copy(kind = verification.kind, opening = false,
                certificate = verification.certificate), verification)
            fun replace(expected: Pending, next: Pending) = synchronized(lock) {
                if (pending[entry.prompt.id]?.approval !== expected.approval) throw CancellationException("Verification dismissed")
                pending[entry.prompt.id] = next
                publish()
            }
            replace(entry, certificate)
            completeVerification(certificate)
            replace(certificate, entry)
            completeVerification(entry)
        }
    }

    private suspend fun completeVerification(entry: Pending) {
        if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "browser.verification")
        if (entry.prompt.certificate != null) {
            synchronized(lock) {
                if (pending[entry.prompt.id]?.approval !== entry.approval) throw CancellationException("Verification dismissed")
                pending[entry.prompt.id] = entry.copy(prompt = entry.prompt.copy(confirmingCertificate = true))
                publish()
            }
            val approved = entry.approval.await()
            if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "certificate.verification")
            if (!approved) throw SourceContentException(hnovel.content.ContentError.Certificate, "certificate.verification")
        }
        synchronized(lock) {
            if (pending[entry.prompt.id]?.approval !== entry.approval) throw CancellationException("Verification dismissed")
            pending[entry.prompt.id] = entry.copy(prompt = entry.prompt.copy(opening = true))
            publish()
        }
        checkNotNull(entry.verification).complete()
        if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "browser.verification")
        synchronized(lock) {
            if (pending[entry.prompt.id]?.approval !== entry.approval) throw CancellationException("Verification dismissed")
        }
        indi.renakoni.nextvol.data.image.SourceImageRetryEvents.request(entry.prompt.owner.source)
    }

    /** A background failure exposes an action, but never waits or opens an Activity itself. */
    suspend fun verifyBackground(id: String) {
        val entry = synchronized(lock) { pending[id]?.takeIf { !it.prompt.foreground && !it.prompt.opening &&
            !it.prompt.confirmingCertificate && it.claimed.compareAndSet(false, true) }?.let {
            // A new user action can retry a failed round; a completed round still skips its old window.
            if (newerOutcome(it)?.failure != null) it.copy(started = sequence).also { retry -> pending[id] = retry } else it
        } } ?: return
        try { withTimeout(300000) {
            val resume = entry.background?.let { it.prepareResume() ?: return@withTimeout }
            if (entry.verification != null) open(entry)
            else synchronized(lock) {
                if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "browser.verification")
                if (pending[id]?.approval !== entry.approval) throw CancellationException("Verification dismissed")
                pending[id] = entry.copy(prompt = entry.prompt.copy(opening = true))
                publish()
            }
            if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "browser.verification")
            // The real worker consumes and saves the response. Never fetch a body only to discard it.
            resume?.invoke(if (entry.verification == null) id else null,
                entry.scriptFinished.takeIf { entry.verification == null })
            entry.scriptRetry?.invoke()
        } }
        finally { remove(id) }
    }

    suspend fun <T> execute(owner: VerificationOwner, name: String, block: suspend () -> T): T {
        val foreground = currentCoroutineContext()[ForegroundSourceRequest]?.takeIf { it.allowsInteraction }
        return if (foreground == null) executeRequest(owner, name, null, block)
        else foreground.ownVerification { executeRequest(owner, name, foreground, block) }
    }

    private suspend fun <T> executeRequest(owner: VerificationOwner, name: String,
        permission: ForegroundSourceRequest?, block: suspend () -> T): T {
        var foreground = permission
        val background = currentCoroutineContext()[BackgroundSourceRequest]?.takeIf { foreground == null }
        val started = synchronized(lock) { sequence }
        var scriptResume: Pending? = null
        val manualAttempted = AtomicBoolean()
        suspend fun run(allowScriptInteraction: Boolean = true): T = withContext(SourceScriptInteraction { verification ->
            if (!allowScriptInteraction) throw SourceContentException(hnovel.content.ContentError.BrowserRequired, "loginCheckJs")
            if (foreground == null) {
                val resume = synchronized(lock) { pending[background?.verificationId]?.takeIf {
                    it.prompt.owner == owner && current(owner) && it.verification == null &&
                        it.claimed.get() && it.scriptClaimed.compareAndSet(false, true)
                } } ?: throw SourceScriptInteractionRequired()
                scriptResume = resume
                foreground = resume.scriptForeground
            }
            manualAttempted.set(true)
            val interaction = Pending(VerificationPrompt(UUID.randomUUID().toString(), owner, name,
                verification.kind, true, certificate = verification.certificate), verification)
            enqueue(interaction)
            verifyForeground(interaction, checkNotNull(foreground), SourceContentException(hnovel.content.ContentError.BrowserRequired,
                "loginCheckJs")) { Unit }
        }) { block() }
        suspend fun request(): T {
            val failure = try { return run() } catch (error: SourceContentException) { error }
            if (manualAttempted.get() && failure.code == hnovel.content.ContentError.BrowserRequired) throw failure
            val scriptInteraction = failure is SourceScriptInteractionRequired
            val verification = failure.verification
            if (verification == null && !scriptInteraction || !current(owner)) throw failure
            val entry = Pending(VerificationPrompt(UUID.randomUUID().toString(), owner, name,
                verification?.kind, foreground != null, certificate = verification?.certificate), verification,
                background = background, started = started,
                scriptRetry = if (scriptInteraction && background == null) {
                    { withContext(ForegroundSourceRequest()) { execute(owner, name, block) }; Unit }
                } else null)
            val outcome = enqueue(entry)
            if (foreground == null) {
                if (outcome == null || outcome.failure != null) throw failure
                // The response was challenged before another request updated this session.
                return run(allowScriptInteraction = false)
            }
            return verifyForeground(entry, checkNotNull(foreground), failure) {
                run(allowScriptInteraction = verification?.certificate != null)
            }
        }
        return try { request().also { scriptResume?.scriptFinished?.complete(Unit) } }
        catch (failure: Throwable) {
            val waiting = scriptResume ?: synchronized(lock) { pending[background?.verificationId]?.takeIf {
                it.prompt.owner == owner && it.verification == null
            } }
            waiting?.scriptFinished?.completeExceptionally(failure)
            throw failure
        }
    }

    private suspend fun <T> verifyForeground(entry: Pending, foreground: ForegroundSourceRequest,
        failure: SourceContentException, block: suspend () -> T): T {
        foreground.beginVerification()
        try {
            val completed = withTimeoutOrNull(300000) {
                foreground.awaitActive()
                open(entry)
                foreground.awaitActive()
                true
            } ?: false
            if (!completed) throw failure
            // Resume a waiting script directly, or retry the failed request only once.
            return block()
        } finally {
            foreground.endVerification()
            remove(entry.prompt.id)
        }
    }
}

internal class RuleRequestRecovery(private val coordinator: SourceVerificationCoordinator,
    private val owner: VerificationOwner, private val name: String) {
    suspend fun <T> execute(block: suspend () -> T): T = coordinator.execute(owner, name, block)
}
