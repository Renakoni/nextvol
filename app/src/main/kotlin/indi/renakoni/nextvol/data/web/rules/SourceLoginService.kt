package indi.renakoni.nextvol.data.web.rules

import hnovel.content.LoginForm
import hnovel.content.LoginActionResult
import hnovel.content.LoginReadingContext
import hnovel.content.RuleSource
import hnovel.content.SourceContentException
import hnovel.content.ContentError
import hnovel.network.*
import indi.renakoni.nextvol.data.web.SourceSessionManager
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

data class LoginAttempt internal constructor(val source: Identifier, val generation: Long, val revision: String,
    internal val rules: RuleSource, val reading: LoginReadingContext? = null) {
    internal val lifetime = Job()
    internal val submission = kotlinx.coroutines.sync.Mutex()
}
enum class LoginIntent { Panel, Relogin }
/** Saved facts only. Submitting a login script does not confirm server-side authentication. */
enum class LoginStatus { LoggedOut, LoginSubmitted, SessionSaved, Required }

/** No Activity is launched from a rule/worker. Foreground UI explicitly owns a cancellable login attempt. */
@Singleton
class SourceLoginService @Inject constructor(private val sources: ImportedRuleSources, private val accounts: SourceSessionManager,
    private val verification: SourceVerificationCoordinator? = null) {
    // loginForm() may suspend across logout, revision change or removal. Validate the same attempt
    // before loading and before returning, rejecting any form produced by a retired attempt.
    suspend fun form(attempt: LoginAttempt): LoginForm = recover(attempt) { target(attempt).rules.loginForm().also { target(attempt) } }
    suspend fun status(source: Identifier): LoginStatus = withContext(Dispatchers.IO) {
        savedStatus(sources.storedSettings(source).loginStatus)
    }
    suspend fun begin(source: Identifier, intent: LoginIntent = LoginIntent.Panel, reading: LoginReadingContext? = null): LoginAttempt {
        val target = if (intent == LoginIntent.Relogin) sources.rotateAccount(source) else sources.loginTarget(source)
        return LoginAttempt(source, target.generation, target.revision, target.rules.openLoginSession(reading), reading)
    }
    suspend fun submit(attempt: LoginAttempt, values: Map<String, String>, action: String? = null, formId: String? = null,
        longClick: Boolean = false): LoginActionResult {
        val target = target(attempt)
        if (!attempt.submission.tryLock()) throw SourceContentException(ContentError.Unavailable, "login.busy")
        try {
            // A source button can have remote side effects before a later request fails.
            // Consent/recovery must never replay the entire user action.
            return recover(attempt, retry = false) {
                target(attempt).rules.login(values.toMap(), action, formId, longClick).also { result ->
                    target(attempt)
                    if (result.refreshDiscovery) sources.refreshDiscovery(attempt.source)
                    indi.renakoni.nextvol.data.image.SourceImageRetryEvents.request(attempt.source)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancel(attempt) }
            throw cancelled
        } catch (failure: Exception) {
            // A transport/grant failure does not establish that the credentials were rejected.
            if (failure is SourceContentException && failure.code == ContentError.LoginRequired &&
                accounts.current(attempt.source).generation == attempt.generation) {
                target.session.write(StorageRequest(StorageArea.Account, "login/status", "required"))
            }
            throw failure
        } finally { attempt.submission.unlock() }
    }
    suspend fun cancel(attempt: LoginAttempt) {
        attempt.rules.close()
        attempt.lifetime.cancel()
    }
    suspend fun logout(source: Identifier) { sources.rotateAccount(source) }

    internal suspend fun <T> withAttempt(attempt: LoginAttempt, block: suspend () -> T): T =
        recover(attempt, retry = false) { block().also { target(attempt) } }

    private suspend fun <T> recover(attempt: LoginAttempt, retry: Boolean = true, block: suspend () -> T): T = coroutineScope {
        val request = currentCoroutineContext().job
        val cancellation = attempt.lifetime.invokeOnCompletion { request.cancel() }
        try {
            target(attempt)
            val coordinator = verification
            if (coordinator == null || !retry) block() else {
                val name = sources.installedSources().firstOrNull { ImportedRuleSources.id(it.definition) == attempt.source }
                    ?.definition?.displayName ?: attempt.source.id
                coordinator.execute(VerificationOwner(attempt.source, attempt.revision, attempt.generation), name, block)
            }
        } finally { cancellation.dispose() }
    }

    private suspend fun target(attempt: LoginAttempt): RuleLoginTarget {
        val current = sources.loginTarget(attempt.source)
        if (!attempt.lifetime.isActive || current.generation != attempt.generation || current.revision != attempt.revision ||
            accounts.current(attempt.source).generation != attempt.generation)
            throw SourceContentException(ContentError.Unavailable, "login")
        return current.copy(rules = attempt.rules)
    }
    internal companion object {
        private val pixivCredential = Regex("[1-9][0-9]*_[^;\\s]+")

        fun storedStatus(session: SourceSession, pixiv: Boolean = false): String? {
            val stored = session.read(StorageRequest(StorageArea.Account, "login/status")) as? StorageResult.Value
                ?: error("Stored source settings are unavailable")
            if (pixiv) {
                if (stored.value == "required") return stored.value
                // A browser visit can save a guest session. Match the source's current-cookie rule.
                return if (session.hasMatchingCookie("https://www.pixiv.net/", "PHPSESSID", pixivCredential))
                    stored.value ?: "session" else null
            }
            return stored.value ?: "session".takeIf { session.hasSavedCookies() }
        }

        // Conservative display convention, not a new login schema. Ambiguous forms have no label.
        private val accountNames = setOf("user", "username", "account", "email", "账号", "帐号", "账户", "用户名", "邮箱")
        // Use only the protocol's unique login() button, never translated labels or the first action.
        // Credential inputs still need an editable form, including multiple account-name fields.
        fun directLoginAction(form: LoginForm): String? {
            if (form.fields.any { it.type == "password" ||
                it.type == "text" && it.name.trim().lowercase(java.util.Locale.ROOT) in accountNames }) return null
            return form.fields.singleOrNull {
                it.enabled && it.type == "button" && it.action?.trim()?.removeSuffix(";")?.trim() == "login()"
            }?.id
        }

        fun accountNameField(form: LoginForm?): String? = form?.fields?.filter {
            it.type == "text" && it.name.trim().lowercase(java.util.Locale.ROOT) in accountNames
        }?.singleOrNull()?.name

        fun savedAccountName(field: String, info: String?): String? {
            if (field.trim().lowercase(java.util.Locale.ROOT) !in accountNames || info == null) return null
            val value = runCatching { (Json.parseToJsonElement(info) as? JsonObject)?.get(field) as? JsonPrimitive }.getOrNull()
            return value?.takeIf { it.isString }?.content?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl)
            }
        }
        fun savedStatus(value: String?) = when (value) {
            "authenticated" -> LoginStatus.LoginSubmitted // Keep the existing persisted value compatible.
            "session" -> LoginStatus.SessionSaved
            "required" -> LoginStatus.Required
            else -> LoginStatus.LoggedOut
        }
    }
}
