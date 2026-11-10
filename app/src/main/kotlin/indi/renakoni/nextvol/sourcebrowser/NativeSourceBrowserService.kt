package indi.renakoni.nextvol.sourcebrowser

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.*
import android.webkit.*
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.webkit.CookieManagerCompat
import hnovel.network.*
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.data.web.AndroidSourceNetworks
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Native website execution. Cookie handoff uses trusted Binder callbacks, never a page bridge. */
class NativeSourceBrowserService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var profile: String? = null
    private var networkHandle: Long? = null
    private var networkReady = false
    private val pages = linkedMapOf<String, Page?>()
    private var exclusivePage: String? = null
    private val pendingInitialization = mutableListOf<() -> Unit>()
    private var initializationFailed = false
    private var cookieVersion = -1L
    private val seededCookies = mutableSetOf<String>()
    private val cookieSeeds = linkedMapOf<String, () -> Unit>()
    private var seedingCookies = false
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var storageTask: Job? = null
    private var storagePending = false
    internal var activity: NativeSourceBrowserActivity? = null
    private val interactivePage get() = pages.values.filterNotNull().firstOrNull { it.job.options.interactive }
    internal val interactiveJobId get() = interactivePage?.job?.jobId
    internal val webView get() = interactivePage?.view
    internal val title get() = interactivePage?.job?.options?.title.orEmpty()
    companion object { internal var active: NativeSourceBrowserService? = null }

    override fun onCreate() {
        super.onCreate()
        // AndroidX Startup runs in the main process only. Domain cookie parsing here
        // also needs OkHttp's application context to load the public suffix list.
        okhttp3.OkHttp.initialize(applicationContext)
    }

    override fun onBind(intent: Intent): IBinder = object : IBrowserService.Stub() {
        override fun start(payload: String, callback: IBrowserHost) {
            check(Binder.getCallingUid() == applicationInfo.uid && payload.length <= 393216)
            val job = Json.decodeFromString<BrowserJob>(payload)
            require(job.jobId.isNotBlank() && job.jobId.length <= 64)
            handler.post {
                var registered = false
                try {
                    check(job.jobId !in pages && pages.size < NativeSourceBrowser.PAGE_LIMIT && storageTask == null && !storagePending)
                    check(exclusivePage == null && (job.options.sharedNativePage || pages.isEmpty()))
                    pages[job.jobId] = null
                    registered = true
                    if (!job.options.sharedNativePage) exclusivePage = job.jobId
                    initialize(job.profile, job.networkHandle) {
                        if (pages.containsKey(job.jobId)) openPage(job, callback)
                    }
                } catch (failure: Exception) { failStart(job.jobId.takeIf { registered }, callback, failure) }
            }
        }
        override fun localStorage(payload: ParcelFileDescriptor, callback: IBrowserHost) {
            check(Binder.getCallingUid() == applicationInfo.uid)
            val job = Json.decodeFromString<LocalStorageJob>(BrowserWire.read(payload, NativeBrowserRetention.MAX_BYTES))
            handler.post {
                try {
                    check(pages.isEmpty() && storageTask == null && !storagePending)
                    storagePending = true
                    initialize(job.profile, job.networkHandle) {
                        val task = storageScope.launch(start = CoroutineStart.LAZY) {
                            val result = try {
                                LocalStorageResult(withTimeout(15000) { transferLocalStorage(this@NativeSourceBrowserService, job) })
                            } catch (failure: Exception) {
                                LocalStorageResult(failure = (failure as? LocalStorageFailure)?.code ?: FailureCode.StorageUnavailable)
                            }
                            storageTask = null
                            send(callback, result)
                        }
                        storageTask = task
                        storagePending = false
                        task.start()
                    }
                } catch (failure: Exception) {
                    storagePending = false
                    send(callback, LocalStorageResult(failure = (failure as? RouteFailure)?.code ?: FailureCode.StorageUnavailable))
                }
            }
        }
        override fun cancel(jobId: String): Boolean {
            check(Binder.getCallingUid() == applicationInfo.uid && jobId.length <= 64)
            val stopped = java.util.concurrent.CountDownLatch(1)
            handler.post {
                try {
                    pages.remove(jobId)?.cancel()
                    if (exclusivePage == jobId) exclusivePage = null
                } finally { stopped.countDown() }
            }
            return stopped.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        override fun shutdown() {
            check(Binder.getCallingUid() == applicationInfo.uid)
            handler.post {
                storageScope.cancel()
                if (profile != null) CookieManager.getInstance().flush()
                pages.values.filterNotNull().forEach { it.view.destroy() }
                pages.clear(); activity?.finish()
                Process.killProcess(Process.myPid())
            }
        }
    }

    private class RouteFailure(val code: FailureCode) : Exception()

    private fun initialize(owner: String, handle: Long?, ready: () -> Unit) {
        if (profile != null) {
            check(profile == owner && networkHandle == handle && !initializationFailed)
            if (networkReady) ready() else pendingInitialization += ready
            return
        }
        bindNetwork(handle)
        NativeBrowserFiles(this).initialize(owner)
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        profile = owner
        networkHandle = handle
        pendingInitialization += ready
        if (handle != null) {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) throw RouteFailure(FailureCode.RouteUnsupported)
                // Binding covers sockets/DNS. The direct override also excludes a system HTTP proxy.
                ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addDirect().build(),
                    { handler.post(it) }, ::initialized)
            } catch (failure: Exception) {
                initializationFailed = true; pendingInitialization.clear(); throw failure
            }
        } else initialized()
    }

    private fun initialized() {
        networkReady = true
        val pending = pendingInitialization.toList()
        pendingInitialization.clear()
        pending.forEach { it() }
    }

    /** Seed each host cookie once per host revision, before navigating any page that needs it.
     * A second page must not restore an old seed over cookies just updated by the first website. */
    private fun seedCookies(job: BrowserJob, alive: () -> Boolean, ready: () -> Unit, failed: () -> Unit) {
        cookieSeeds[job.jobId] = {
            var completed = false
            fun done(success: Boolean) {
                if (completed) return
                completed = true
                try { if (alive()) { if (success) ready() else failed() } }
                finally { seedingCookies = false; nextCookieSeed() }
            }
            try {
                require(job.cookieVersion >= 0 && job.cookies.size <= 256 && job.cookies.sumOf(String::length) <= 65536)
                if (job.cookieVersion > cookieVersion) { cookieVersion = job.cookieVersion; seededCookies.clear() }
                val url = checkNotNull(job.request.url.toHttpUrlOrNull())
                val values = if (job.cookieVersion < cookieVersion) emptyList() else job.cookies.map { value ->
                    val cookie = checkNotNull(Cookie.parse(url, value))
                    "${cookie.name}\n${cookie.domain}\n${cookie.path}" to value
                }.filter { it.first !in seededCookies }.distinctBy { it.first }
                if (values.isEmpty()) done(true)
                else {
                    var pending = values.size
                    var acceptedAll = true
                    fun accepted(key: String, success: Boolean) {
                        if (success) seededCookies += key else acceptedAll = false
                        if (--pending == 0) done(acceptedAll)
                    }
                    values.forEach { (key, value) ->
                        try { CookieManager.getInstance().setCookie(job.request.url, value) { accepted(key, it) } }
                        catch (_: Exception) { accepted(key, false) }
                    }
                }
            } catch (_: Exception) { done(false) }
        }
        nextCookieSeed()
    }

    private fun nextCookieSeed() {
        if (seedingCookies || cookieSeeds.isEmpty()) return
        seedingCookies = true
        cookieSeeds.remove(cookieSeeds.keys.first())!!.invoke()
    }

    /** Called only in this dedicated process, before any Chromium initialization. */
    private fun bindNetwork(handle: Long?) {
        if (handle == null) return
        if (Build.VERSION.SDK_INT < 28) throw RouteFailure(FailureCode.RouteUnsupported)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = Network.fromNetworkHandle(handle)
        if (connectivity.getNetworkCapabilities(network)?.let(AndroidSourceNetworks::eligible) != true ||
            !connectivity.bindProcessToNetwork(network)) throw RouteFailure(FailureCode.RouteUnavailable)
    }

    private fun openPage(job: BrowserJob, callback: IBrowserHost) {
        try {
            check(networkReady && profile == job.profile && networkHandle == job.networkHandle && pages.containsKey(job.jobId))
            active = this
            val page = Page(job, callback)
            pages[job.jobId] = page
            page.open()
        } catch (failure: Exception) { failStart(job.jobId, callback, failure) }
    }

    private fun failStart(jobId: String?, callback: IBrowserHost, failure: Exception) {
        if (jobId != null) {
            pages.remove(jobId)?.cancel()
            if (exclusivePage == jobId) exclusivePage = null
        }
        send(callback, BrokerResult.Failure(RequestStage.Connect,
            if (failure is RouteFailure) failure.code else FailureCode.Network))
    }

    internal fun confirm(jobId: String?) { if (jobId == interactiveJobId) interactivePage?.evaluate() }
    internal fun cancel(jobId: String?) { if (jobId == interactiveJobId)
        interactivePage?.finish(BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired)) }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class Page(val job: BrowserJob, private val host: IBrowserHost) {
        private val startedAt = SystemClock.elapsedRealtime()
        val view = WebView(this@NativeSourceBrowserService)
        private val finished = AtomicBoolean()
        private var evaluatingNavigation: Int? = null
        private var navigation = 0
        private var staticDocumentCheck: String? = null
        private var scheduledEvaluation: Runnable? = null
        @Volatile private var httpError = false
        @Volatile private var httpChallenge: BrowserChallengeKind? = null

        private fun trace(stage: String) {
            if (BuildConfig.DEBUG) android.util.Log.d("NativePageTrace",
                "job=${job.jobId} stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - startedAt} navigation=$navigation")
        }

        fun open() {
            trace("Open")
            // WebView must re-check each new TLS handshake against this account's current exceptions.
            view.clearSslPreferences()
            CookieManager.getInstance().apply {
                // Legado's enabledCookieJar controls automatic HTTP cookie capture,
                // not the browser session required by login and human verification.
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
            job.options.webCookie?.let { header ->
                if (job.observeUserAgent) reportSourceUserAgent(host, view.settings, null, hnovel.network.UserAgentMetadataStatus.ProviderDefault)
                // Complete after openPage has registered this page; finishing inside open() would leave it active.
                val updates = try { nativeWebCookieUpdates(job.request.url, header, cookies(job.request.url)) } catch (failure: Exception) {
                    handler.post { fail(if (failure is IllegalArgumentException) FailureCode.InvalidRequest else FailureCode.StorageUnavailable) }
                    return
                }
                var pending = updates.size
                if (pending == 0) handler.post { completeText("", job.request.url) }
                else updates.forEach { cookie ->
                    CookieManager.getInstance().setCookie(job.request.url, cookie) { accepted ->
                        if (!accepted) fail(FailureCode.StorageUnavailable)
                        if (--pending == 0 && !finished.get()) completeText("", job.request.url)
                    }
                }
                return
            }
            ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
                blockNetworkLoads = false; allowFileAccess = false; allowContentAccess = false
            }
            view.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                blockNetworkLoads = false
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                configureSourceUserAgent(job, host, view.context)
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
            }
            if (!job.options.interactive && job.options.script.isBlank() && job.options.sourceRegex.isBlank())
                staticDocumentCheck = runCatching { view.observeStaticDocument() }.getOrNull()
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
                override fun onConsoleMessage(message: ConsoleMessage) = true
            }
            view.setDownloadListener { _, _, _, _, _ -> fail(FailureCode.InvalidRequest) }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Turnstile and ordinary frames use these browser-owned documents.
                    if (!request.isForMainFrame && request.url.toString() in setOf("about:blank", "about:srcdoc")) return false
                    if (request.url.scheme?.lowercase() !in setOf("http", "https")) return true
                    if (request.isForMainFrame && job.options.overrideUrl && matches(request.url.toString())) {
                        completeText(request.url.toString(), view.url ?: job.request.url)
                        return true
                    }
                    return false
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // Some WebViews deliver the response error before onPageStarted.
                    // Reset at request dispatch so onPageStarted cannot erase a challenge.
                    if (request.isForMainFrame) { httpError = false; httpChallenge = null }
                    if (!job.options.overrideUrl && matches(request.url.toString())) handler.post {
                        completeText(request.url.toString(), view.url ?: job.request.url)
                    }
                    return null
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    navigation++
                    scheduledEvaluation?.let(handler::removeCallbacks)
                    scheduledEvaluation = null
                    trace("Navigation")
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    trace("CommitVisible")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url != view.url) return
                    trace("PageFinished")
                    if (!job.options.interactive || job.options.script.isNotBlank()) scheduleEvaluation()
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) {
                        httpError = true
                        if (response.responseHeaders?.entries?.any { it.key.equals("cf-mitigated", true) && it.value == "challenge" } == true)
                            httpChallenge = BrowserChallengeKind.Cloudflare
                    }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(FailureCode.Network)
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                    val problem = nativeCertificateProblem(error)
                    if (problem != null && job.certificates.any { it.origin == problem.origin && it.fingerprint == problem.fingerprint }) {
                        handler.proceed()
                    } else {
                        handler.cancel()
                        finish(BrokerResult.Failure(RequestStage.Connect, FailureCode.Certificate, certificate = problem))
                    }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fail(FailureCode.Network); return true
                }
            }
            // A detached WebView has no layout pass. Give it a real rendering area
            // before navigation; the foreground Activity later lays it out normally.
            val display = resources.displayMetrics
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(display.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(display.heightPixels, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            fun navigate() {
                if (finished.get()) return
                view.loadUrl(job.request.url, job.request.headers)
                if (job.options.interactive) startActivity(Intent(this@NativeSourceBrowserService,
                    NativeSourceBrowserActivity::class.java).putExtra("jobId", job.jobId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            seedCookies(job, { !finished.get() }, ::navigate) { fail(FailureCode.StorageUnavailable) }
        }

        private fun matches(url: String) = job.options.sourceRegex.isNotBlank() && Regex(job.options.sourceRegex).containsMatchIn(url)

        private fun scheduleEvaluation() {
            if (job.options.interactive && job.options.waitForConfirmation) return
            val version = navigation
            val completedAt = SystemClock.uptimeMillis()
            fun schedule(static: Boolean) {
                if (finished.get() || version != navigation || pages[job.jobId] !== this) return
                trace(if (static) "StaticReady" else "CompatibilityWait")
                val due = completedAt + job.options.delayMillis + if (static) 0 else 1000
                scheduledEvaluation?.let(handler::removeCallbacks)
                scheduledEvaluation = Runnable {
                    scheduledEvaluation = null
                    if (version == navigation) evaluate()
                }.also { handler.postAtTime(it, due) }
            }
            val check = staticDocumentCheck
            if (check == null) schedule(false)
            else view.evaluateJavascript(check) { schedule(it == "true") }
        }

        fun evaluate() {
            if (finished.get() || evaluatingNavigation == navigation || pages[job.jobId] !== this) return
            trace("Evaluate")
            val version = navigation
            evaluatingNavigation = version
            val script = job.options.script.ifBlank { "document.documentElement.outerHTML" }
            val timing = if (BuildConfig.DEBUG && !job.options.interactive) """
                (function(){try{
                    var n=performance.getEntriesByType('navigation')[0], r=performance.getEntriesByType('resource');
                    if (!n) return null;
                    var scripts=0, images=0;
                    for (var i=0;i<r.length;i++) {
                        if (r[i].initiatorType==='script') scripts=Math.max(scripts,r[i].duration);
                        if (r[i].initiatorType==='img') images=Math.max(images,r[i].duration);
                    }
                    return {htmlStartMs:Math.round(n.responseStart),htmlEndMs:Math.round(n.responseEnd),
                        domReadyMs:Math.round(n.domContentLoadedEventEnd),loadMs:Math.round(n.loadEventEnd),
                        resources:r.length,scriptMaxMs:Math.round(scripts),imageMaxMs:Math.round(images)};
                }catch(e){return null;}})()
            """.trimIndent() else "null"
            val deferChallenge = job.request.deferChallenge && !job.options.interactive
            view.evaluateJavascript("""
                (function(){try {
                    var challenge=$websiteChallengeScript;
                    if (!challenge && ${httpChallenge != null}) challenge='Cloudflare';
                    return JSON.stringify({url:location.href,challenge:challenge,timing:$timing,
                        value:challenge ? ($deferChallenge ? document.documentElement.outerHTML : null) : eval(${JsonPrimitive(script)})});
                }catch(e){return null;}})()
            """.trimIndent()) { encoded ->
                if (evaluatingNavigation == version) evaluatingNavigation = null
                if (finished.get() || pages[job.jobId] !== this) return@evaluateJavascript
                if (version != navigation) {
                    if (job.options.interactive) handler.postDelayed({ evaluate() }, 100)
                    return@evaluateJavascript
                }
                try {
                    val outer = Json.parseToJsonElement(encoded)
                    val result = if (outer == JsonNull) null else Json.parseToJsonElement(outer.jsonPrimitive.content).jsonObject
                    if (BuildConfig.DEBUG) (result?.get("timing") as? JsonObject)?.let { values ->
                        // Numeric milestones only: never log resource URLs, headers or document content.
                        val fields = listOf("htmlStartMs", "htmlEndMs", "domReadyMs", "loadMs", "resources", "scriptMaxMs", "imageMaxMs")
                            .mapNotNull { key -> (values[key] as? JsonPrimitive)?.intOrNull?.let { "$key=$it" } }
                        trace("DocumentTiming ${fields.joinToString(" ")}")
                    }
                    val challenge = result?.get("challenge")?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: httpChallenge?.name
                    if (challenge != null) {
                        if (deferChallenge) completeText(result!!.getValue("value").jsonPrimitive.content,
                            result.getValue("url").jsonPrimitive.content,
                            if (httpChallenge == BrowserChallengeKind.Cloudflare) mapOf("cf-mitigated" to listOf("challenge")) else emptyMap())
                        else if (job.options.interactive) handler.postDelayed({ evaluate() }, 1000)
                        else finish(BrokerResult.Failure(RequestStage.Response, FailureCode.BrowserRequired,
                            challenge = BrowserChallengeKind.valueOf(challenge)))
                        return@evaluateJavascript
                    }
                    if (httpError) { fail(FailureCode.Network); return@evaluateJavascript }
                    val value = result?.get("value")
                    if (value == null || value == JsonNull || job.options.sourceRegex.isNotBlank()) {
                        handler.postDelayed({ evaluate() }, 100)
                    } else {
                        val url = result.getValue("url").jsonPrimitive.content
                        if (url != view.url) handler.postDelayed({ evaluate() }, 100)
                        else completeText(if (value is JsonPrimitive) value.content else value.toString(), url)
                    }
                } catch (_: Exception) { fail(FailureCode.Network) }
            }
        }

        private fun completeText(text: String, url: String, headers: Map<String, List<String>> = emptyMap()) {
            if (sourceOrigin(url) == null) { fail(FailureCode.InvalidRequest); return }
            val bytes = text.toByteArray()
            if (bytes.size > minOf(job.request.maxResponseBytes ?: BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES,
                    BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES)) {
                fail(FailureCode.ResponseTooLarge); return
            }
            finish(BrokerResult.Success(BrokerResponse(0, url, headers, bytes, "UTF-8", 0,
                protocol = "", kind = ResponseKind.BrowserDocument)))
        }
        private fun fail(code: FailureCode) = finish(BrokerResult.Failure(RequestStage.Response, code))
        private fun cookies(url: String): NativeCookieSnapshot {
            val manager = CookieManager.getInstance()
            val parsed = checkNotNull(url.toHttpUrlOrNull())
            val completeMetadata = WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)
            val values = if (completeMetadata) {
                CookieManagerCompat.getCookieInfo(manager, url)
            } else {
                // Older WebViews expose only a request header. Keep this handoff in memory,
                // host-only and no broader than the queried path; never invent an expiry.
                val root = parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
                val rootCookies = manager.getCookie(root).orEmpty().split(';').map(String::trim).toSet()
                manager.getCookie(url).orEmpty().split(';').mapNotNull { pair ->
                    val parts = pair.trim().split('=', limit = 2)
                    if (parts.size != 2) null else Cookie.Builder().name(parts[0]).value(parts[1])
                        .hostOnlyDomain(parsed.host).path(if (pair.trim() in rootCookies) "/" else parsed.encodedPath).httpOnly()
                        .apply { if (parsed.isHttps) secure() }.build().toString()
                }
            }
            return nativeCookieSnapshot(url, values, completeMetadata, job.observeUserAgent)
        }

        fun finish(result: BrokerResult) {
            if (!finished.compareAndSet(false, true)) return
            trace(if (result is BrokerResult.Success) "Success" else "Failure")
            view.stopLoading()
            CookieManager.getInstance().flush()
            val completed = if (result is BrokerResult.Success) try {
                val urls = listOf(job.request.url, result.response.finalUrl).mapNotNull { it.toHttpUrlOrNull() }
                    .flatMap { listOf(it.newBuilder().encodedPath("/").query(null).fragment(null).build().toString(),
                        it.newBuilder().query(null).fragment(null).build().toString()) }.distinct()
                BrowserWire.read(host.call("cookies", nativeCookieSnapshotPayload(urls.map(::cookies))))
                result
            } catch (_: Exception) { BrokerResult.Failure(RequestStage.Storage, FailureCode.StorageUnavailable) }
            else result
            dispose()
            send(host, completed)
        }

        fun cancel() {
            if (finished.compareAndSet(false, true)) { view.stopLoading(); dispose() }
        }

        private fun dispose() {
            scheduledEvaluation?.let(handler::removeCallbacks)
            scheduledEvaluation = null
            cookieSeeds.remove(job.jobId)
            val currentActivity = if (job.options.interactive) activity.also { activity = null } else null
            pages.remove(job.jobId)
            if (exclusivePage == job.jobId) exclusivePage = null
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy(); currentActivity?.finish()
        }
    }

    private fun send(host: IBrowserHost, result: BrokerResult) {
        runCatching { BrowserWire.pipeResult(result).use { host.complete(it) } }
    }
    private fun send(host: IBrowserHost, result: LocalStorageResult) {
        runCatching { BrowserWire.pipe(Json.encodeToString(result)).use { host.complete(it) } }
    }
    override fun onDestroy() { storageScope.cancel(); active = null; Process.killProcess(Process.myPid()) }
}
