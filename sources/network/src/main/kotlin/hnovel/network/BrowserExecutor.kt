package hnovel.network

import kotlinx.serialization.Serializable

/** The host installs this capability. URL rules cannot select a session or request foreground UI. */
fun interface BrowserExecutor {
    suspend fun defaultUserAgent(): String? = null

    /** Optional UI feedback; headless hosts may omit display without changing script results. */
    suspend fun showMessage(message: String, long: Boolean, guard: RequestCommitGuard) { guard.commit {} }

    /** Admit via session.awaitBrowserAdmission after acquiring the execution slot, before dispatch.
     * Forward all child HTTP requests with the supplied route; never reread the preference. */
    suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
        guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult
    fun clearAccount(scope: SourceScope, localStorage: LocalStorageRetention = LocalStorageRetention()) {}
}

@Serializable data class BrowserOptions(val script: String = "", val delayMillis: Long = 0,
    val sourceRegex: String = "", val html: String? = null, val interactive: Boolean = false, val title: String = "",
    val overrideUrl: Boolean = false, val verificationCode: Boolean = false,
    val nativeWebsite: Boolean = false, val webCookie: String? = null,
    val waitForConfirmation: Boolean = false)
