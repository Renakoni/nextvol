package hnovel.network

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

/** Host-owned authority. A script receives a bound session protocol, never open() or a raw client. */
class SourceBroker(private val storageRoot: Path, private val dns: Dns = VpnDns.Default,
    private val limits: BrokerLimits = BrokerLimits(), private val cipher: StorageCipher = StorageCipher.Plain,
    private val browser: BrowserExecutor? = null, route: SourceRouteProvider? = null) : AutoCloseable {
    private val routes = route ?: SourceNetworkRoute(SourceNetworkMode.SystemDefault, dns).let { fallback ->
        SourceRouteProvider { fallback }
    }
    private val sessions = mutableMapOf<List<String>, SourceSession>()
    @Synchronized fun open(scope: SourceScope, grants: List<NetworkGrant>): SourceSession {
        val key = scope.components(account = false)
        val old = sessions[key]
        if (old != null && old.scope == scope && !old.closed) {
            require(old.grants == grants) { "Close the session before changing its grants" }
            return old
        }
        old?.close()
        return SourceSession(scope, grants, storageRoot, dns, limits, cipher, browser, routes).also {
            if (old != null) it.inheritCaches(old)
            sessions[key] = it
        }
    }
    @Synchronized override fun close() { sessions.values.forEach { it.close() }; sessions.clear() }
}

class SourceSession internal constructor(val scope: SourceScope, grants: List<NetworkGrant>, root: Path,
    dns: Dns, private val limits: BrokerLimits, cipher: StorageCipher = StorageCipher.Plain,
    private val browser: BrowserExecutor? = null, private val routes: SourceRouteProvider,
    private val retryPolicy: HttpRetryPolicy = HttpRetryPolicy()) : AutoCloseable, SourceStateStorage {
    internal val grants = grants.map { it.copy(headers = it.headers.toMap()) }
    private val policy = NetworkPolicy(this.grants, dns)
    private val imagePolicy = NetworkPolicy(this.grants, dns, publicImages = true)
    private val denied = linkedSetOf<OriginDenial>()
    /** Ephemeral, bounded and source/account owned; revision replacement does not inherit these requests. */
    val deniedOrigins: List<OriginDenial> get() = synchronized(this) { denied.toList() }
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(limits.concurrency)
    private val rate = Mutex()
    private var lastStart = 0L
    @Volatile private var sourcePacing = SourceRequestPacer(null)
    private var cache = ResponseCache(limits)
    private val valuesCache = run {
        val cacheLimits = limits.copy(maxStorageBytes = limits.maxCacheBytes.toLong())
        // Old script values have no account owner. Never import them into the current account;
        // deletion is best effort because the account-owned partition never reads them.
        SourceStorage(root, scope.components(false) + "cache", cacheLimits, cipher).clear()
        ValueCache(limits, SourceStorage(root, scope.components(true) + "cache", cacheLimits, cipher))
    }
    private val config = SourceStorage(root, scope.components(false) + "config", limits, cipher)
    private val bookState by lazy { SourceStorage(root, scope.components(false) + "books",
        limits.copy(maxStorageBytes = limits.maxBookStorageBytes, maxStorageEntries = limits.maxBookStorageEntries), cipher) }
    private val account = SourceStorage(root, scope.components(true) + "account", limits, cipher)
    private val cookieStorage = SourceStorage(root, scope.components(true) + "cookies", limits, cipher)
    private val cookies = SourceCookies(cookieStorage)
    private val certificates = SourceCertificates(SourceStorage(root, scope.components(true) + "certificate-exceptions", limits, cipher))
    @Volatile var enabledCookieJar = true
    var sourceUrl: String = ""
        private set
    var browserRead: Boolean = false
        private set
    var localStorageRetention = LocalStorageRetention()
        private set
    private var defaultUserAgent: String? = null
    @Volatile private var preferBrowserUserAgent = false
    @Volatile private var providerUserAgent: String? = null
    private val browserIdentity = lifetime.async(start = CoroutineStart.LAZY) {
        browser?.defaultUserAgent()?.takeIf { it.isNotBlank() }.also { providerUserAgent = it }
    }
    @Volatile private var requestTrace: RequestTrace = RequestTrace.None
    /** Host-only observer; source scripts cannot install diagnostic callbacks. */
    fun traceRequests(trace: RequestTrace) { requestTrace = trace }
    @Synchronized fun configureSource(url: String, cookiesEnabled: Boolean, browserRead: Boolean = false,
        concurrentRate: String? = null, localStorageRetention: LocalStorageRetention = LocalStorageRetention(),
        defaultUserAgent: String? = null, preferBrowserUserAgent: Boolean = false) {
        require(sourceUrl.isEmpty() || sourceUrl == url)
        sourceUrl = url
        enabledCookieJar = cookiesEnabled
        this.browserRead = browserRead
        this.localStorageRetention = localStorageRetention.approved(grants)
        this.defaultUserAgent = defaultUserAgent
        this.preferBrowserUserAgent = preferBrowserUserAgent
        val parsedRate = SourceRequestRate.parse(concurrentRate)
        if (sourcePacing.rate != parsedRate) sourcePacing = SourceRequestPacer(parsedRate)
    }

    /** Host-only: call inside browser serialization, immediately before starting a navigation.
     * The enclosing execute owns cancellation and timeout; browser subresources never call this. */
    suspend fun awaitBrowserAdmission() {
        checkOpen()
        sourcePacing.awaitAdmission()
        currentCoroutineContext().ensureActive()
        checkOpen()
    }
    // An HTTP proxy would move DNS/peer validation to an unchecked destination.
    // Each route below supplies its own DNS, sockets and connection pool.
    // enqueue() also has a per-host queue; it must not silently lower the broker's concurrency.
    // Registering a source does not need an HTTP client or its TLS/connection resources.
    private val client = lazy { OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = limits.concurrency
            maxRequestsPerHost = limits.concurrency
        })
        .retryOnConnectionFailure(false).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .addNetworkInterceptor { chain ->
            val peer = chain.connection()?.socket()?.remoteSocketAddress as? InetSocketAddress
                ?: throw BrokerFailure(RequestStage.Permission, FailureCode.AddressDenied)
            (chain.request().tag(NetworkPolicy::class.java) ?: policy).checkPeer(chain.request().url, peer.address)
            chain.request().tag(HttpRequestObservation::class.java)?.let { trace ->
                trace.observation.record(RequestEvidence.TransportHeaders, RequestPath.Http,
                    userAgent = UserAgentSummary.from(chain.request().header("User-Agent")),
                    attempt = trace.attempt, hop = trace.hop)
            }
            chain.proceed(chain.request())
        }.build() }
    private val routeClients = mutableMapOf<Pair<SourceNetworkRoute, String>, OkHttpClient>()

    @Synchronized private fun clientFor(route: SourceNetworkRoute, url: HttpUrl): OkHttpClient {
        checkOpen()
        route.checkAvailable()
        routeClients.entries.removeAll { (network, transport) ->
            (!network.first.available).also { if (it) network.first.detach(transport.connectionPool) }
        }
        val origin = NetworkPolicy.origin(url)
        if ((route to origin) !in routeClients && routeClients.size >= 64) {
            // Public cover URLs can introduce more origins than the source's explicit grants.
            val idle = routeClients.entries.firstOrNull {
                it.value.connectionPool.connectionCount() == it.value.connectionPool.idleConnectionCount()
            }
            if (idle != null) { idle.key.first.detach(idle.value.connectionPool); routeClients.remove(idle.key) }
        }
        return routeClients.getOrPut(route to origin) {
            val pool = ConnectionPool()
            val builder = client.value.newBuilder().socketFactory(route.socketFactory).connectionPool(pool)
            val transport = (if (url.isHttps) certificates.configure(builder, origin) else builder).build()
            route.attach(pool)
            transport
        }
    }
    val closed get() = !lifetime.isActive

    /** Host-only: native background work must stop even after its last document completed. */
    fun onClosed(action: () -> Unit): AutoCloseable {
        val subscription = lifetime.coroutineContext.job.invokeOnCompletion { action() }
        return AutoCloseable { subscription.dispose() }
    }

    @Synchronized fun certificateExceptions(): List<CertificateExceptionSite> { checkOpen(); return certificates.exceptions() }

    /** Host UI only; accepts only a certificate observed by this still-current session. */
    @Synchronized fun approveCertificate(problem: CertificateProblem) {
        checkOpen()
        certificates.approve(problem)
        routeClients.entries.removeAll { (key, transport) ->
            (key.second == problem.origin).also { if (it) key.first.detach(transport.connectionPool) }
        }
    }

    /** Retires HTTP pools and in-flight commits; onClosed also stops the native browser. */
    @Synchronized fun revokeCertificate(origin: String) {
        checkOpen()
        close()
        cache.clear()
        certificates.revoke(origin)
    }

    /** Trusted native browser callback. Unknown origins still need their ordinary network grant. */
    @Synchronized fun browserCertificateFailure(problem: CertificateProblem): BrokerResult.Failure {
        checkOpen()
        permissionFailureDetail(problem.origin)?.let { return it }
        certificates.remember(problem)
        return BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, certificate = problem)
    }

    // TTS is an API transport, not a browser source; preserve its existing default identity.
    private val transportUserAgent get() = if (scope.profile == "http-tts") "okhttp/${OkHttp.VERSION}" else DESKTOP_USER_AGENT

    /** Resolve the same header precedence as an HTTP request without sending one. */
    suspend fun requestUserAgent(url: String, explicit: Map<String, String> = emptyMap()): String {
        checkOpen()
        val address = requireNotNull(url.toHttpUrlOrNull())
        prepareUserAgent(address, explicit, policy)
        return synchronized(this) {
            checkOpen()
            resolvedUserAgent(headers(address, explicit, policy, includeCookies = false)["User-Agent"], transportUserAgent)
        }
    }

    suspend fun webViewUserAgent(): String {
        checkOpen()
        val value = browserIdentity.await()
        checkOpen()
        check(!value.isNullOrBlank()) { "WebView user agent unavailable" }
        return value
    }

    private suspend fun prepareUserAgent(url: HttpUrl, explicit: Map<String, String>, policy: NetworkPolicy) {
        // A source-selected request identity must not depend on initializing the default provider.
        if (preferBrowserUserAgent && synchronized(this) {
                headers(url, explicit, policy, includeCookies = false, includeDefaultUserAgent = false)["User-Agent"].isNullOrBlank()
            })
            browserIdentity.await()
    }

    suspend fun showMessage(message: String, long: Boolean, guard: RequestCommitGuard) {
        guard.commit { checkOpen() }
        browser?.showMessage(message, long, RequestCommitGuard { action -> guard.commit { checkOpen(); action() } })
    }

    /** Host-only revision/re-enable handoff. A closed session may retain memory-only cookies;
     * exact scope equality still forbids transfer across sources, profiles or account generations. */
    fun inheritCookies(previous: SourceSession) {
        require(scope == previous.scope)
        checkOpen()
        cookies.inherit(previous.cookies)
    }

    /** Retain HTTP response bodies; script values have their own account-owned storage. */
    @Synchronized fun inheritCaches(previous: SourceSession) {
        require(scope.components(false) == previous.scope.components(false))
        checkOpen()
        cache = previous.cache
    }

    /** Host-only saved-session fact; never exposes cookie names, values or authentication claims. */
    @Synchronized fun hasSavedCookies(): Boolean {
        checkOpen()
        return cookies.snapshot().any { it.second.persistent && it.second.expiresAt > System.currentTimeMillis() }
    }

    /** Host-only local credential check; no network access or server-side authentication claim. */
    @Synchronized fun hasMatchingCookie(url: String, name: String, valuePattern: Regex): Boolean {
        checkOpen()
        val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        return cookies.snapshot().any { (_, cookie) ->
            cookie.expiresAt > System.currentTimeMillis() && cookie.matches(parsed) &&
                cookie.name == name && valuePattern.matches(cookie.value)
        }
    }

    @Synchronized fun cookie(url: String): String { checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed); return cookies.header(parsed, null) }
    @Synchronized fun setCookie(url: String, value: String, replace: Boolean = false) {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL"); policy.check(parsed)
        // Re-seeding an explicit login renews our synthetic expiry, not server authority.
        updateSessionCookies(retireAttributes = false) { cookies.setHeader(parsed, value, replace) }
    }
    @Synchronized fun removeCookie(url: String) { checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed); updateSessionCookies { cookies.setHeader(parsed, "", true) } }

    /** Host-only cookie handoff; never exposed as a website JavascriptInterface. */
    @Synchronized fun nativeBrowserCookieSeed(url: String): NativeBrowserCookieSeed {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed)
        return cookies.browserSeed(parsed)
    }

    @Synchronized fun nativeBrowserCookies(url: String): List<String> {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed)
        return cookies.browserSnapshot(parsed)
    }

    /** Response identity includes Chromium-owned cookies, which must never be seeded back into it. */
    @Synchronized fun responseCookies(url: String): List<String> {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed)
        return cookies.responseSnapshot(parsed)
    }

    @Synchronized fun updateNativeBrowserCookies(url: String, values: List<String>, completeMetadata: Boolean = true,
        expectedSeedVersion: Long? = null) {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL")
        policy.check(parsed)
        updateSessionCookies { cookies.replaceBrowserSnapshot(parsed, values, completeMetadata, expectedSeedVersion) }
    }

    @Synchronized fun browserCookie(url: String, value: String? = null): String {
        checkOpen(); val parsed = url.toHttpUrlOrNull() ?: error("Invalid cookie URL"); policy.check(parsed)
        if (!enabledCookieJar) return ""
        if (value != null) updateSessionCookies { cookies.documentCookie(parsed, value) }
        return cookies.documentHeader(parsed)
    }

    /** Login headers bootstrap cookies. A confirmed value/attribute update retires only that seed;
     * the jar owns the replacement's domain, path and expiry. Request headers remain explicit. */
    @Synchronized private fun updateSessionCookies(retireAttributes: Boolean = true, update: () -> Unit) {
        val loginUrl = sourceUrl.toHttpUrlOrNull()
        // A granted subdomain can update a parent-domain cookie. Compare accepted jar
        // entries, including attributes; header strings lose expiry and scope changes.
        val before = loginUrl?.let { address -> cookies.snapshot().map { it.second }.filter { it.matches(address) } }.orEmpty()
        update()
        if (loginUrl == null || before.isEmpty()) return
        // Cookie expiry is serialized at HTTP-date (second) precision in browser seeds.
        // Host-only Set-Cookie text omits its domain; another host/path cannot keep this seed alive.
        fun identity(cookie: Cookie) = listOf(cookie.domain, cookie.path,
            if (retireAttributes) cookie.toString() else "${cookie.name}=${cookie.value}")
        val after = cookies.snapshot().map { identity(it.second) }.toSet()
        val retired = before.filter { identity(it) !in after }.map { it.name to it.value }.toSet()
        if (retired.isEmpty()) return
        val saved = when (val result = account.read(StorageRequestKey.LOGIN_HEADERS)) {
            is StorageResult.Failure -> throw BrokerFailure(RequestStage.Storage, result.code)
            is StorageResult.Value -> result.value ?: return
        }
        val headers = (Json.parseToJsonElement(saved) as kotlinx.serialization.json.JsonObject).toMutableMap()
        var changed = false
        headers.toMap().forEach { (key, value) ->
            if (key.equals("Cookie", true)) {
                val original = (value as kotlinx.serialization.json.JsonPrimitive).content
                val kept = original.split(';').filter { part ->
                    val pair = part.trim().split('=', limit = 2)
                    pair.size != 2 || (pair[0] to pair[1]) !in retired
                }.joinToString(";").trim()
                if (kept != original.trim()) {
                    changed = true
                    if (kept.isEmpty()) headers.remove(key) else headers[key] = kotlinx.serialization.json.JsonPrimitive(kept)
                }
            }
        }
        if (changed) when (val result = account.write(StorageRequestKey.LOGIN_HEADERS, kotlinx.serialization.json.JsonObject(headers).toString())) {
            is StorageResult.Failure -> throw BrokerFailure(RequestStage.Storage, result.code)
            is StorageResult.Value -> Unit
        }
    }

    /** Called by the host after revocation; deletes only the retired account's sensitive state. */
    fun clearAccount() {
        val storageFailure = synchronized(this) {
            close()
            val accountCleared = account.clear() is StorageResult.Value
            val cookiesCleared = cookieStorage.clear() is StorageResult.Value
            val certificatesCleared = certificates.clear() is StorageResult.Value
            val valuesCleared = valuesCache.clear() is StorageResult.Value
            cookies.restoreMemory(emptyList())
            if (accountCleared && cookiesCleared && certificatesCleared && valuesCleared) null else IllegalStateException("Account cleanup failed")
        }
        // Browser cancellation may finish on another thread that checks this session.
        // Do not hold the session monitor while waiting for its process to stop.
        try { browser?.clearAccount(scope, localStorageRetention) }
        catch (failure: Exception) {
            if (storageFailure == null) throw failure
            storageFailure.addSuppressed(failure)
        }
        storageFailure?.let { throw it }
    }

    @Synchronized override fun read(request: StorageRequest): StorageResult {
        checkOpen()
        return when (request.area) {
            StorageArea.Config -> config.read(request.key)
            StorageArea.Account -> account.read(request.key)
            StorageArea.Cache -> valuesCache.read(request.key)
            StorageArea.BookState -> bookState.read(request.key)
        }
    }
    @Synchronized override fun write(request: StorageRequest): StorageResult {
        checkOpen()
        return when (request.area) {
            StorageArea.Config -> config.write(request.key, request.value)
            StorageArea.Account -> account.write(request.key, request.value)
            StorageArea.Cache -> valuesCache.write(request)
            StorageArea.BookState -> bookState.write(request.key, request.value)
        }
    }
    @Synchronized override fun writeBookStates(values: Map<String, String>): StorageResult {
        checkOpen()
        return bookState.writeAll(values)
    }
    fun newVariables(initial: Map<String, String> = emptyMap()) = RequestVariables(initial)

    /** Source/profile-scoped installation pseudonym. Never reads the global Android device ID. */
    @Synchronized fun installationIdentifier(): StorageResult {
        checkOpen()
        val key = "installation-identifier"
        val current = config.read(key)
        if (current !is StorageResult.Value) return current
        if (current.value != null) return if (current.value.matches(Regex("[0-9a-f]{16}"))) current
            else StorageResult.Failure(FailureCode.StorageUnavailable)
        val bytes = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        val identifier = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        return config.write(key, identifier)
    }

    /** Checks current grants for a cached resource without opening a connection. */
    fun permissionFailure(url: String): FailureCode? = permissionFailureDetail(url)?.code

    fun permissionFailureDetail(url: String, kind: ResourceKind = ResourceKind.Document): BrokerResult.Failure? {
        checkOpen()
        val parsed = url.toHttpUrlOrNull() ?: return BrokerResult.Failure(RequestStage.Parse, FailureCode.InvalidRequest)
        return try { policy.check(parsed); null }
        catch (failure: BrokerFailure) { permissionResult(failure, kind) }
    }

    @Synchronized private fun permissionResult(failure: BrokerFailure, kind: ResourceKind): BrokerResult.Failure {
        checkOpen()
        val detail = failure.deniedOrigin?.let { OriginDenial(it, kind) }
        if (detail != null && denied.size < 32) denied.add(detail)
        return BrokerResult.Failure(failure.stage, failure.code, denial = detail)
    }

    suspend fun execute(request: BrokerRequest, guard: RequestCommitGuard = RequestCommitGuard { it() }): BrokerResult =
        execute(request, guard, policy)

    /** Host-owned response handoffs must remain on the same live route as their original read. */
    fun responseRoute(): SourceRouteReference? = runCatching {
        checkOpen(); routes.snapshot().reference.takeIf { it.available }
    }.getOrNull()

    /** Host-only response identity, including account headers which are not in the rule request. */
    @Synchronized fun responseHeaders(request: BrokerRequest): Map<String, String> {
        checkOpen()
        return headers(requireNotNull(request.url.toHttpUrlOrNull()), request.headers, policy, includeCookies = false).toMap()
    }

    /** Use the route already captured for an owned response handoff, including native browser reads. */
    suspend fun executeOnRoute(request: BrokerRequest, guard: RequestCommitGuard, route: SourceRouteReference): BrokerResult =
        execute(request, guard, policy, route = route.route)

    /** Host-only HTTP transport for synthetic/image verification documents; avoids browser re-entry. */
    suspend fun executeHttp(request: BrokerRequest, guard: RequestCommitGuard, route: SourceNetworkRoute? = null): BrokerResult {
        require(request.browser == null)
        return execute(request, guard, policy, browserDefault = false, paceSource = false, route = route)
    }

    /** Host-only image loading for an URL already extracted from a book. No script bridge exposes this. */
    suspend fun loadImage(request: BrokerRequest, guard: RequestCommitGuard = RequestCommitGuard { it() }): BrokerResult {
        require(request.kind == ResourceKind.Image && request.method == "GET" && request.body == null && request.browser == null)
        // Coil owns image caching; keep downloaded images out of the script response cache.
        return execute(request.copy(cache = CacheMode.Disabled), guard, imagePolicy, paceSource = false)
    }

    private suspend fun execute(request: BrokerRequest, guard: RequestCommitGuard, policy: NetworkPolicy,
        browserDefault: Boolean = true, paceSource: Boolean = true, route: SourceNetworkRoute? = null): BrokerResult {
        checkOpen()
        val selectedRoute = runCatching { route ?: routes.snapshot() }
        // A browser source keeps document reads in the same native session. Binary/image
        // requests stay explicit HTTP operations; URL options can still supply a render script.
        val snapshot = request.copy(headers = request.headers.toMap(), browser = request.browser ?:
            if (browserDefault && browserRead && request.kind == ResourceKind.Document) BrowserOptions() else null)
        val retryContext = currentCoroutineContext()[RequestRetryContext]
        if (!retryPolicy.safe(snapshot)) retryContext?.disallowReplay()
        val observation = requestTrace.takeUnless { it === RequestTrace.None }?.let {
            RequestObservation(it, currentCoroutineContext()[RequestObservation])
        }
        observation?.record(RequestEvidence.Selected, when {
            snapshot.url.startsWith("data:") -> RequestPath.Inline
            snapshot.browser != null -> RequestPath.Browser
            else -> RequestPath.Http
        }, when {
            snapshot.url.startsWith("data:") -> null
            request.browser != null -> RequestReason.ExplicitBrowser
            snapshot.browser != null -> RequestReason.SourceBrowserRead
            !browserDefault -> RequestReason.ExplicitHttp
            else -> RequestReason.DefaultHttp
        })
        val work = lifetime.async((observation ?: EmptyCoroutineContext) + (retryContext ?: EmptyCoroutineContext)) {
            var stage = RequestStage.Queue
            try {
                val transport = selectedRoute.getOrThrow()
                validate(snapshot)
                withTimeout(if (snapshot.browser?.interactive == true) 300000 else snapshot.timeoutMillis) {
                    // Resolve once before either HTTP or browser dispatch. Header rules can still
                    // override this default, but verification must not switch an automatic identity.
                    if (!snapshot.url.startsWith("data:")) prepareUserAgent(
                        snapshot.url.toHttpUrlOrNull() ?: throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest),
                        snapshot.headers, policy)
                    if (snapshot.url.startsWith("data:")) {
                        val encoded = Regex("^data:.*?;base64,([A-Za-z0-9+/=\\s]*)$").matchEntire(snapshot.url)
                            ?.groupValues?.get(1) ?: throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
                        val bytes = java.util.Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace))
                        if (bytes.size > minOf(snapshot.maxResponseBytes ?: limits.maxResponseBytes, limits.maxResponseBytes))
                            throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                        guard.commit { checkOpen() }
                        // Legado StrResponse falls back to this URL for non-HTTP input; no socket is opened.
                        BrokerResult.Success(BrokerResponse(200, "http://localhost/", emptyMap(), bytes, snapshot.charset, 0,
                            message = "OK", protocol = "data"))
                    } else if (snapshot.browser != null) {
                        val url = snapshot.url.toHttpUrlOrNull() ?: throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
                        val browserHeaders = if (snapshot.browser.webCookie != null) {
                            policy.check(url)
                            observation?.record(RequestEvidence.HeadersResolved, RequestPath.Browser,
                                RequestReason.WebCookieDefaults, listOf(UserAgentSource.WebViewDefault))
                            emptyMap()
                        } else headers(url, snapshot.headers, policy, includeCookies = false, observation = observation).toMap()
                        val maxBytes = minOf(snapshot.maxResponseBytes ?: limits.maxResponseBytes, limits.maxResponseBytes)
                        val result = browser?.execute(this@SourceSession, snapshot.copy(browser = null, headers = browserHeaders,
                            maxResponseBytes = maxBytes), snapshot.browser, guard, transport)
                            ?: BrokerResult.Failure(RequestStage.Parse, FailureCode.BrowserRequired)
                        if (result is BrokerResult.Success) {
                            if (result.response.body.size > maxBytes)
                                throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                            // A completed foreground browser saved a session, not proof of authentication.
                            // Script-named login actions must report the same fact as browser-only forms.
                            if (snapshot.browser.interactive && (result.response.kind == ResponseKind.BrowserDocument ||
                                    result.response.status in 200..299)) guard.commit {
                                checkOpen()
                                check(account.write("login/status", "session") is StorageResult.Value)
                            }
                        }
                        result
                    } else {
                        perform(snapshot, guard, policy, paceSource, transport, browserDefault) { stage = RequestStage.Connect }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                BrokerResult.Failure(stage, FailureCode.Timeout,
                    retryable = stage == RequestStage.Connect && retryPolicy.safe(snapshot))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: BrokerFailure) {
                  var result: BrokerResult.Failure? = null
                  guard.commit { result = permissionResult(failure, snapshot.kind) }
                  checkNotNull(result).let { it.copy(retryable = it.code == FailureCode.RouteUnavailable && retryPolicy.safe(snapshot)) }
              }
              catch (_: IllegalArgumentException) { BrokerResult.Failure(RequestStage.Parse, FailureCode.InvalidRequest) }
              catch (_: java.net.UnknownHostException) { BrokerResult.Failure(RequestStage.Connect, FailureCode.Dns, retryable = retryPolicy.safe(snapshot)) }
              catch (failure: IOException) { BrokerResult.Failure(RequestStage.Connect, FailureCode.Network,
                  retryable = retryPolicy.safe(snapshot) && retryPolicy.recoverable(failure)) }
        }
        return try { work.await().also { checkOpen() }.let { result ->
            observation?.record(RequestEvidence.Completed, when {
                result is BrokerResult.Success && result.response.protocol == "data" -> RequestPath.Inline
                result is BrokerResult.Success && result.response.fromCache -> RequestPath.Cache
                else -> null
            }, status = (result as? BrokerResult.Success)?.response?.status, failure = (result as? BrokerResult.Failure)?.code)
            if (result is BrokerResult.Success && snapshot.responseAsHex) result.copy(response = result.response.copy(textAsHex = true)) else result
        } } catch (cancelled: CancellationException) {
            observation?.record(RequestEvidence.Cancelled)
            throw cancelled
        } finally { work.cancel() }
    }

    private fun validate(request: BrokerRequest) {
        if (request.id.length > 256 || request.url.length + request.headers.entries.sumOf { it.key.length + it.value.length } > 65536 ||
            request.method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD") || request.retry !in 0..limits.maxRetry ||
            request.timeoutMillis !in 1..limits.maxTimeoutMillis || (request.body?.length ?: 0) > limits.maxRequestBytes ||
            request.maxResponseBytes?.let { it <= 0 } == true ||
            request.method in setOf("GET", "HEAD") && request.body != null ||
            request.cache != CacheMode.Disabled && request.method != "GET") {
            throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
        }
        Charset.forName(request.charset)
        request.responseCharset?.let(Charset::forName)
        if (request.headers.keys.any { it.lowercase() in setOf("host", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection") }) {
            throw BrokerFailure(RequestStage.Permission, FailureCode.InvalidRequest)
        }
    }

    private suspend fun perform(request: BrokerRequest, guard: RequestCommitGuard, policy: NetworkPolicy,
        paceSource: Boolean, route: SourceNetworkRoute, detectChallenges: Boolean, onRequest: () -> Unit): BrokerResult {
        val initialUrl = request.url.toHttpUrlOrNull() ?: throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
        policy.check(initialUrl)
        val observation = currentCoroutineContext()[RequestObservation]
        val initialHeaders = headers(initialUrl, request.headers, policy, observation = observation)
        val cacheKey = hash(Json.encodeToString(listOf(initialUrl.toString(), request.responseCharset.orEmpty(), request.followRedirects.toString(),
            Json.encodeToString<Map<String, List<String>>>(initialHeaders.toMultimap().mapKeys { it.key.lowercase() }.toSortedMap()))))
        if (request.cache != CacheMode.Disabled) {
            cache.get(cacheKey)?.let {
                if (it.body.size > (request.maxResponseBytes ?: limits.maxResponseBytes))
                    return BrokerResult.Failure(RequestStage.Response, FailureCode.ResponseTooLarge)
                // A previously cached verification page is not a readable document.
                // Raw API callers still receive their original response unchanged.
                if (!detectChallenges || request.kind != ResourceKind.Document || websiteChallenge(it) == null)
                    return BrokerResult.Success(it)
            }
            if (request.cache == CacheMode.Only) return BrokerResult.Failure(RequestStage.Response, FailureCode.CacheMiss)
        }
        val safe = retryPolicy.safe(request)
        // Preserve one safe connection recovery even at retry=0 unless the host disables
        // retries. Share its budget with explicit retries, not a nested OkHttp retry loop.
        val taskOwned = currentCoroutineContext()[RequestRetryContext] != null
        val retryLimit = if (safe && !taskOwned) maxOf(request.retry, 1).coerceAtMost(limits.maxRetry) else 0
        var cookieRefreshed = false
        for (attempt in 0..retryLimit) {
            val response = try {
                guard.commit { checkOpen() }
                permits.withPermit {
                    var response = redirects(request, initialUrl, guard, policy, paceSource, route, attempt, onRequest)
                    // Cookie bootstrap has one separate credit for the whole logical read,
                    // never one credit per retry. Login/submission bodies are not replayed.
                    if (detectChallenges && request.kind == ResourceKind.Document && request.method == "GET" &&
                        !cookieRefreshed && isCookieRefreshChallenge(response)) {
                        cookieRefreshed = true
                        observation?.record(RequestEvidence.Selected, RequestPath.Http, RequestReason.CookieBootstrap, attempt = attempt)
                        response = redirects(request, initialUrl, guard, policy, paceSource, route, attempt, onRequest)
                    }
                    response
                }
            } catch (failure: BrokerFailure) { throw failure }
              catch (failure: IOException) {
                route.checkAvailable()
                val rejected = generateSequence<Throwable>(failure) { it.cause }.take(16).filterIsInstance<RejectedCertificate>().firstOrNull()
                if (rejected != null) {
                    guard.commit { synchronized(this) { checkOpen(); certificates.remember(rejected.problem) } }
                    return BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, attempt, certificate = rejected.problem)
                }
                if (failure is javax.net.ssl.SSLPeerUnverifiedException)
                    return BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, attempt)
                if (attempt == retryLimit || !retryPolicy.recoverable(failure)) return BrokerResult.Failure(RequestStage.Connect,
                    when (failure) {
                        is java.net.UnknownHostException -> FailureCode.Dns
                        is java.io.InterruptedIOException -> FailureCode.Timeout
                        else -> FailureCode.Network
                    }, attempt, retryable = safe && retryPolicy.recoverable(failure))
                guard.commit { checkOpen() }
                retryPolicy.await(checkNotNull(retryPolicy.delayMillis(attempt)))
                continue
            }
            // Raw APIs/browser subrequests keep the original response for source scripts.
            if (detectChallenges && request.kind == ResourceKind.Document) {
                websiteChallenge(response)?.let { challenge ->
                    return BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired, attempt,
                        challenge = challenge, verificationRequest = request)
                }
            }
            val wait = if (safe && attempt < minOf(request.retry, retryLimit)) retryPolicy.delayMillis(attempt, response) else null
            if (wait != null) {
                guard.commit { checkOpen() }
                retryPolicy.await(wait) // No network permit is retained during retry backoff.
                continue
            }
            if (request.cache == CacheMode.ReadThrough && response.status in 200..299) guard.commit {
                cache.put(cacheKey, response)
            }
            return BrokerResult.Success(response)
        }
        error("Unreachable retry state")
    }

    private suspend fun redirects(request: BrokerRequest, first: HttpUrl, guard: RequestCommitGuard, policy: NetworkPolicy,
        paceSource: Boolean, route: SourceNetworkRoute, attempt: Int, onRequest: () -> Unit): BrokerResponse {
        val observation = currentCoroutineContext()[RequestObservation]
        var url = first
        var method = request.method
        var body = request.body
        var callerHeaders = request.headers
        for (hop in 0..limits.maxRedirects) {
            policy.check(url)
            // An origin/account override can disappear after a cross-origin redirect.
            prepareUserAgent(url, callerHeaders, policy)
            rate.withLock {
                val elapsed = (System.nanoTime() - lastStart) / 1_000_000
                if (lastStart != 0L && elapsed < limits.minIntervalMillis) delay(limits.minIntervalMillis - elapsed)
                // Each attempt is a logical source request. Redirect hops retain the broker's
                // hard interval but do not consume another source admission.
                if (paceSource && hop == 0) sourcePacing.awaitAdmission()
                currentCoroutineContext().ensureActive()
                checkOpen()
                lastStart = System.nanoTime()
            }
            val headers = headers(url, callerHeaders, policy, observation = observation, attempt = attempt, hop = hop)
            val bytes = body?.toByteArray(Charset.forName(request.charset))
            if (bytes != null && bytes.size > limits.maxRequestBytes) throw BrokerFailure(RequestStage.Parse, FailureCode.InvalidRequest)
            val requestBody = if (method in setOf("POST", "PUT", "PATCH") || bytes != null)
                (bytes ?: ByteArray(0)).toRequestBody(headers["Content-Type"]?.toMediaTypeOrNull()) else null
            onRequest()
            val transport = clientFor(route, url)
            // Broker retries own the budget, including stale pooled-connection recovery.
            // OkHttp 5.4 follows 503/Retry-After:0 even with connection retries disabled.
            // Hide this header only from its follow-up interceptor; restore it before the
            // response/size checks and caller see it (including invalid or huge values).
            var retryAfter = emptyList<String>()
            val call = transport.newBuilder().retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (retryAfter.isEmpty()) response else response.newBuilder().apply {
                        retryAfter.forEach { addHeader("Retry-After", it) }
                    }.build()
                }
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code != 503) response else {
                        retryAfter = response.headers.values("Retry-After")
                        response.newBuilder().removeHeader("Retry-After").build()
                    }
                }
                .dns(policy.dns(url, route.dns)).callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS).build()
                .newCall(Request.Builder().url(url).tag(NetworkPolicy::class.java, policy)
                    .tag(HttpRequestObservation::class.java, observation?.let { HttpRequestObservation(it, attempt, hop) })
                    .headers(headers).method(method, requestBody).build())
            route.track(call)
            val response = try {
                awaitResponse(call, request.responseCharset, hop, guard, minOf(request.maxResponseBytes ?: limits.maxResponseBytes, limits.maxResponseBytes))
            } finally { route.finished(call, transport.connectionPool) }
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
            if (!request.followRedirects || response.status !in setOf(301, 302, 303, 307, 308) || location == null) return response
            if (hop == limits.maxRedirects) throw BrokerFailure(RequestStage.Response, FailureCode.RedirectLimit)
            val next = url.resolve(location) ?: throw BrokerFailure(RequestStage.Permission, FailureCode.InvalidRequest)
            policy.check(next)
            if (response.status == 303 && method != "HEAD" || response.status in setOf(301, 302) && method == "POST") {
                method = "GET"
                body = null
                callerHeaders = callerHeaders.filterKeys { !it.equals("Content-Type", true) }
            }
            if (NetworkPolicy.origin(next) != NetworkPolicy.origin(url)) {
                if (body != null) throw BrokerFailure(RequestStage.Permission, FailureCode.RedirectBodyDenied)
                // The website's client identity survives a redirect; credentials remain origin-bound.
                callerHeaders = callerHeaders.filterKeys { it.equals("User-Agent", true) }
            }
            url = next
        }
        error("Unreachable redirect state")
    }

    private fun headers(url: HttpUrl, explicit: Map<String, String>, policy: NetworkPolicy, includeCookies: Boolean = true,
        observation: RequestObservation? = null, attempt: Int? = null, hop: Int? = null,
        includeDefaultUserAgent: Boolean = true): Headers {
        val headers = Headers.Builder()
        val sources = observation?.let { mutableListOf<UserAgentSource>() }
        fun supplied(key: String, value: String, source: UserAgentSource) {
            if (key.equals("User-Agent", true) && value.isBlank()) return
            headers.set(key, value)
            if (key.equals("User-Agent", true) && sources?.contains(source) == false) sources.add(source)
        }
        // Automatic browser sources share the provider's identity across HTTP and verification.
        // Headless hosts keep their configured compatibility default. Explicit headers win.
        (if (!includeDefaultUserAgent) null else if (preferBrowserUserAgent) providerUserAgent ?: defaultUserAgent else defaultUserAgent)
            ?.let { supplied("User-Agent", it, UserAgentSource.SessionDefault) }
        policy.check(url).headers.forEach { (key, value) -> supplied(key, value, UserAgentSource.OriginGrant) }
        val sameOrigin = sourceUrl.toHttpUrlOrNull()?.let { NetworkPolicy.origin(it) == NetworkPolicy.origin(url) } == true
        val loginHeaders = if (sameOrigin) (account.read(StorageRequestKey.LOGIN_HEADERS) as? StorageResult.Value)?.value else null
        loginHeaders?.let { Json.parseToJsonElement(it).let { json ->
            (json as kotlinx.serialization.json.JsonObject).forEach { (key, value) ->
                // The login API already seeded the jar. Replaying its unscoped header
                // masks path-specific cookies and can resurrect expired credentials.
                // Explicit source/request cookies below remain deliberate overrides.
                if (key.equals("Cookie", true)) return@forEach
                supplied(key, (value as kotlinx.serialization.json.JsonPrimitive).content, UserAgentSource.AccountLogin)
            }
        } }
        // A CDN learned from page data must not receive a source's arbitrary credential headers.
        val knownOrigin = grants.any { sourceOrigin(it.origin) == NetworkPolicy.origin(url) }
        explicit.forEach { (key, value) ->
            if (policy !== imagePolicy || knownOrigin || key.lowercase() in setOf("user-agent", "referer", "accept", "accept-language"))
                supplied(key, value, UserAgentSource.RequestHeaders)
        }
        if (headers.build().names().any { it.lowercase() in setOf("host", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection") }) {
            throw BrokerFailure(RequestStage.Permission, FailureCode.InvalidRequest)
        }
        // Source-provided Accept-Encoding disables OkHttp's transparent gzip decoder.
        // Negotiate only transport-supported encodings; preserve explicit uncompressed requests.
        if (headers["Accept-Encoding"]?.trim()?.equals("identity", true) != true) headers.removeAll("Accept-Encoding")
        // Gate the callback holder too: a captured mutable local allocates even without an observer.
        val cookieDiagnostic = observation?.let { arrayOfNulls<CookieDiagnostic>(1) }
        // Chromium owns its persistent store; do not turn an HTTP jar snapshot into a native header.
        if (includeCookies) {
            val observeCookies: ((CookieDiagnostic) -> Unit)? = cookieDiagnostic?.let { result ->
                { value -> result[0] = value.copy(automaticCapture = enabledCookieJar) }
            }
            // enabledCookieJar controls capture, not sending explicit login/verification cookies.
            val cookie = if (policy !== imagePolicy || knownOrigin) cookies.header(url, headers["Cookie"], observeCookies)
                else {
                    if (cookieDiagnostic != null) cookieDiagnostic[0] = CookieDiagnostic(CookieStore.ExplicitHeaderOnly,
                        automaticCapture = enabledCookieJar)
                    headers["Cookie"].orEmpty()
                }
            headers.removeAll("Cookie")
            if (cookie.isNotEmpty()) headers.set("Cookie", cookie)
        }
        if (includeCookies && headers["User-Agent"].isNullOrBlank()) {
            headers.set("User-Agent", resolvedUserAgent(headers["User-Agent"], transportUserAgent))
            sources?.add(UserAgentSource.TransportDefault)
        }
        if (sources != null) {
            if (sources.isEmpty()) sources.add(if (includeCookies) UserAgentSource.TransportDefault else UserAgentSource.WebViewDefault)
            observation.record(RequestEvidence.HeadersResolved, userAgentSources = sources.toList(),
                userAgent = UserAgentSummary.from(headers["User-Agent"]),
                attempt = attempt, hop = hop, cookies = cookieDiagnostic?.get(0))
        }
        return headers.build()
    }

    /** The continuation remains cancellable through body consumption, so call.cancel interrupts reads too. */
    private suspend fun awaitResponse(call: Call, forcedCharset: String?, redirects: Int, guard: RequestCommitGuard, maxResponseBytes: Int): BrokerResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!continuation.isActive) return
                        if (response.headers.toString().length > 65536) throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                        val body = response.body
                        if (body.contentLength() > maxResponseBytes) throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                if (bytes.size() + read > maxResponseBytes) throw BrokerFailure(RequestStage.Response, FailureCode.ResponseTooLarge)
                                bytes.write(buffer, 0, read)
                            }
                        }
                        val responseBytes = bytes.toByteArray()
                        val declaredCharset = forcedCharset ?: body.contentType()?.charset()?.name()
                        val charset = declaredCharset ?: htmlResponseCharset(responseBytes, body.contentType()) ?: "UTF-8"
                        guard.commit {
                            synchronized(this@SourceSession) {
                                checkOpen()
                                if (continuation.isActive) {
                                    if (enabledCookieJar) updateSessionCookies { cookies.save(response.request.url, response.headers) }
                                    continuation.resume(BrokerResponse(response.code, response.request.url.toString(),
                                        response.headers.toMultimap().mapValues { it.value.toList() }, responseBytes, charset, redirects,
                                        message = response.message, protocol = response.protocol.toString(),
                                        sentAt = response.sentRequestAtMillis, receivedAt = response.receivedResponseAtMillis,
                                        declaredCharset = declaredCharset, method = response.request.method))
                                }
                            }
                        }
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
            }
        }
        try { guard.commit { if (continuation.isActive) call.enqueue(callback) } }
        catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
    }

    private fun checkOpen() { if (closed) throw CancellationException("Source session retired") }
    @Synchronized override fun close() {
        lifetime.cancel()
        denied.clear()
        valuesCache.release()
        certificates.close()
        if (client.isInitialized()) client.value.dispatcher.cancelAll()
        routeClients.forEach { (key, transport) -> key.first.detach(transport.connectionPool) }
        routeClients.clear()
        if (client.isInitialized()) {
            client.value.connectionPool.evictAll()
            client.value.dispatcher.executorService.shutdown()
        }
    }
}

/** Account-owned script values survive process restarts; zero TTL has no deadline. */
internal class ValueCache(private val limits: BrokerLimits, private val storage: SourceStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis) {
    @Serializable private data class Entry(val value: String, val deadline: Long)
    private var snapshot: String? = null
    private var decoded: MutableMap<String, Entry>? = null

    @Synchronized fun read(key: String): StorageResult = access { entries -> StorageResult.Value(entries[key]?.value) }

    @Synchronized fun release() { snapshot = null; decoded = null; storage.releaseReadSnapshot() }

    @Synchronized fun clear(): StorageResult { release(); return storage.clear() }

    @Synchronized fun write(request: StorageRequest): StorageResult = access { entries ->
        if (request.value == null) entries.remove(request.key)
        else {
            val ttl = request.ttlMillis ?: limits.cacheTtlMillis
            // A permanent value has no deadline to renew; do not rewrite the whole cache for a no-op.
            if (ttl == 0L && entries[request.key] == Entry(request.value, 0L))
                return@access StorageResult.Value(request.value)
            val size = entries.entries.filter { it.key != request.key }.sumOf { (it.key.length.toLong() + it.value.value.length) * 2 } +
                (request.key.length.toLong() + request.value.length) * 2
            if (size > limits.maxCacheBytes || request.key !in entries && entries.size >= limits.maxStorageEntries)
                return@access StorageResult.Failure(FailureCode.StorageQuota)
            entries[request.key] = Entry(request.value, if (ttl == 0L) 0 else Math.addExact(nowMillis(), ttl))
        }
        val encoded = Json.encodeToString(entries)
        when (val saved = storage.write("entries", encoded)) {
            is StorageResult.Failure -> { release(); saved }
            is StorageResult.Value -> { snapshot = encoded; StorageResult.Value(request.value) }
        }
    }

    private inline fun access(block: (MutableMap<String, Entry>) -> StorageResult): StorageResult {
        val stored = storage.read("entries", reuse = true)
        if (stored !is StorageResult.Value) { release(); return stored }
        return try {
            // Re-read storage so replacement, deletion and failures remain visible across sessions.
            // Reuse only the decoded snapshot; a small key must not repeatedly parse the entire cache.
            val entries = decoded?.takeIf { snapshot == stored.value } ?:
                (stored.value?.let { Json.decodeFromString<Map<String, Entry>>(it).toMutableMap() } ?: linkedMapOf()).also {
                    decoded = it
                }
            snapshot = stored.value
            val now = nowMillis()
            entries.entries.removeAll { it.value.deadline != 0L && it.value.deadline <= now }
            block(entries)
        } catch (_: Exception) { release(); StorageResult.Failure(FailureCode.StorageUnavailable) }
    }
}

private class ResponseCache(private val limits: BrokerLimits) {
    private val entries = linkedMapOf<String, Pair<Long, BrokerResponse>>()
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun get(key: String): BrokerResponse? {
        val cached = entries[key] ?: return null
        if ((System.nanoTime() - cached.first) / 1_000_000 >= limits.cacheTtlMillis) { entries.remove(key); return null }
        return copy(cached.second).copy(fromCache = true)
    }
    @Synchronized fun put(key: String, response: BrokerResponse) {
        if (size(response) > limits.maxCacheBytes) return
        entries[key] = System.nanoTime() to copy(response)
        while (entries.values.sumOf { size(it.second) } > limits.maxCacheBytes || entries.size > 256) entries.remove(entries.keys.first())
    }
    private fun size(response: BrokerResponse) = response.body.size.toLong() + response.finalUrl.length * 2L +
        response.headers.entries.sumOf { (it.key.length + it.value.sumOf(String::length)) * 2L }
    private fun copy(response: BrokerResponse) = response.copy(body = response.body.copyOf(), headers = response.headers.mapValues { it.value.toList() })
}
