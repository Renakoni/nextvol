package indi.renakoni.nextvol.sourceexecution

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import hnovel.execution.SourceExecutionBroker
import hnovel.network.*
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.sourcebrowser.BrowserTestHostActivity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeBrowserInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scopes = mutableListOf<SourceSession>()

    @Test fun nativeBrowserCompletionResumesTheLoginSuccessBranch(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html")
                    .addHeader("Set-Cookie", "auth=completion-fixture; Path=/; Max-Age=600; HttpOnly")
                    .setBody("<html><head><link rel='icon' href='data:,'></head><body>Native login ready</body></html>")
            }
            server.start(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
            val root = File(context.cacheDir, "login-completion-${System.nanoTime()}")
            val authority = ExecutionAuthority()
            val executor = AndroidIsolatedExecutor(context, authority)
            try { SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val baseUrl = server.url("/").toString()
                val session = broker.open(SourceScope("login-completion", root.name, "legado"),
                    listOf(NetworkGrant(baseUrl, true)))
                session.configureSource(baseUrl, true, browserRead = true)
                val identity = authority.issue(root.name, "legado", "1", "login-completion")
                val limits = ExecutionLimits(timeoutMillis = 60000)
                try { SourceExecutionBroker(identity, authority, session, limits, baseUrl, allowInteraction = true).use { bridge ->
                    val pending = async { executor.execute(identity, ExecutionTask.Script("""
                        var response = java.startBrowserAwait('/login', 'Login completion fixture', false);
                        if (response.code() === 200) cache.put('login-ready', 'saved', 0);
                        response.isBrowserDocument() && response.raw() === null &&
                            cache.get('login-ready') === 'saved' &&
                            cookie.getCookie(baseUrl).indexOf('auth=completion-fixture') >= 0
                    """.trimIndent(), baseUrl = baseUrl), limits, bridge) }
                    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                    automation.serviceInfo = automation.serviceInfo.apply {
                        flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    }
                    fun find(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
                        node ?: return null
                        if (node.isVisibleToUser && (node.text?.contains(text) == true ||
                                node.contentDescription?.contains(text) == true || node.viewIdResourceName == text)) return node
                        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
                        return null
                    }
                    withTimeout(30000) {
                        while (automation.windows.none {
                            find(it.root?.takeIf { node -> node.packageName == context.packageName }, "Native login ready") != null
                        }) delay(100)
                    }
                    val done = "android:id/button1"
                    withTimeout(10000) {
                        while (automation.windows.none {
                            find(it.root?.takeIf { node -> node.packageName == context.packageName }, done)
                                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                        }) delay(100)
                    }
                    assertEquals(ExecutionResult.Success("true"), withTimeout(15000) { pending.await() })
                } } finally { session.clearAccount() }
            } } finally { executor.close(); root.deleteRecursively() }
        } }
    }

    private suspend fun fixture(block: suspend (SourceBroker, MockWebServer) -> Unit) {
        val root = File(context.cacheDir, "native-test-${UUID.randomUUID()}")
        MockWebServer().use { server ->
            server.start(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), 0)
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                try { block(broker, server) }
                finally { scopes.forEach { it.clearAccount() }; scopes.clear() }
            }
        }
        root.deleteRecursively()
    }
    private fun session(broker: SourceBroker, server: MockWebServer, id: String = UUID.randomUUID().toString(), generation: Long = 0): SourceSession =
        broker.open(SourceScope("native-tests", id, "legado", generation), listOf(NetworkGrant(server.url("/").toString(), true)))
            .apply { configureSource(server.url("/").toString(), true, browserRead = true, defaultUserAgent = DESKTOP_USER_AGENT); scopes += this }

    private suspend fun render(session: SourceSession, url: String, script: String = "document.title", interactive: Boolean = false): BrokerResponse {
        val result = session.execute(BrokerRequest("render", url, timeoutMillis = 60000,
            browser = BrowserOptions(script = script, interactive = interactive)))
        assertTrue(result.toString(), result is BrokerResult.Success)
        return (result as BrokerResult.Success).response
    }

    @Test fun bundledBilinovelReadsVisibleParagraphsAndLazyImagesWithoutMergingChapters(): Unit = runBlocking { fixture { broker, server ->
        val definition = context.assets.open("source-catalog/Anime.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonArray.map { row -> row.jsonObject }
                .single { row -> row["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.bilinovel.com" }
        }
        val script = definition.getValue("ruleContent").jsonObject.getValue("webJs").jsonPrimitive.content
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val next = if (request.path == "/novel/1/100.html") "/novel/1/100_2.html" else "/novel/1/101.html"
                return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("""
                    <html><head><link rel="icon" href="data:,"></head><body>
                    <script>window.ReadParams={url_next:'$next'};</script>
                    <div id="acontent">
                        <p style="position:absolute;transform:scale(0)">隐藏的干扰段落</p>
                        <p>第一段 &amp; 引号“保持原样”。</p>
                        <div class="csgo">广告</div><p>第二段。</p>
                        <img data-src="/images/one.jpg">
                        <img data-src="/images/two.jpg" style="position:absolute;top:10000px">
                    </div><div id="comments">评论内容</div></body></html>
                """.trimIndent())
            }
        }
        val account = session(broker, server)
        for (path in listOf("/novel/1/100.html", "/novel/1/100_2.html")) {
            val html = render(account, server.url(path).toString(), script).text()
            val doc = org.jsoup.Jsoup.parse(html)
            assertEquals(listOf("第一段 & 引号“保持原样”。", "第二段。"), doc.select("#chapter p").map { it.text() })
            assertEquals(listOf("one.jpg", "two.jpg"), doc.select("#chapter img").map { it.attr("src").substringAfterLast('/') })
            assertFalse(html.contains("广告"))
            assertFalse(html.contains("评论内容"))
            assertEquals(if (path.endsWith("100.html")) server.url("/novel/1/100_2.html").toString() else "",
                doc.select("#next-page").attr("href"))
        }
    } }

    @Test fun automaticIdentityMatchesTheProviderAcrossHttpNativeBrowserAndJavascript(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/http") return MockResponse().setBody(request.getHeader("User-Agent").orEmpty())
                return MockResponse().setHeader("Content-Type", "text/html").setBody("""
                    <html><head><link rel='icon' href='data:,'></head><body><script>
                    window.identity=JSON.stringify({sent:${JsonPrimitive(request.getHeader("User-Agent").orEmpty())},
                        actual:navigator.userAgent,platform:navigator.platform});</script></body></html>
                """.trimIndent())
            }
        }
        val account = session(broker, server)
        val base = server.url("/").toString()
        account.configureSource(base, true, browserRead = true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
        val expected = account.requestUserAgent(base)
        assertEquals(expected, account.webViewUserAgent())
        val http = account.execute(BrokerRequest("http", server.url("/http").toString(), kind = ResourceKind.Api))
        assertTrue(http.toString(), http is BrokerResult.Success)
        assertEquals(expected, (http as BrokerResult.Success).response.text())
        repeat(2) {
            val sample = Json.parseToJsonElement(render(account, base, "window.identity").text()).jsonObject
            assertEquals(expected, sample.getValue("sent").jsonPrimitive.content)
            assertEquals(expected, sample.getValue("actual").jsonPrimitive.content)
            assertFalse(expected.contains("Windows NT"))
        }
        val explicit = account.execute(BrokerRequest("explicit", base, headers = mapOf("User-Agent" to DESKTOP_USER_AGENT),
            browser = BrowserOptions(script = "window.identity")))
        assertTrue(explicit.toString(), explicit is BrokerResult.Success)
        val sample = Json.parseToJsonElement((explicit as BrokerResult.Success).response.text()).jsonObject
        assertEquals(DESKTOP_USER_AGENT, sample.getValue("sent").jsonPrimitive.content)
        assertEquals(DESKTOP_USER_AGENT, sample.getValue("actual").jsonPrimitive.content)
    } }

    @Test fun desktopUserAgentReachesNativeNavigationAndClientHints(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html")
                .setHeader("Set-Cookie", "private-probe=private-token; Path=/; SameSite=Lax").setBody("""
                <html><head><link rel="icon" href="data:,"></head><body><script>
                window.answer=JSON.stringify({http:${JsonPrimitive(request.getHeader("User-Agent"))},
                    dom:navigator.userAgent,hints:navigator.userAgentData ? navigator.userAgentData.toJSON() : null});
                </script></body></html>
            """.trimIndent())
        }
        val events = java.util.concurrent.ConcurrentLinkedQueue<RequestDiagnostic>()
        val session = session(broker, server).apply { traceRequests { events += it } }
        val page = Json.parseToJsonElement(render(session, server.url("/book").toString(), "window.answer").text()).jsonObject
        val ua = page.getValue("http").jsonPrimitive.content
        assertTrue(ua, ua.contains("Windows NT"))
        assertEquals(ua, page.getValue("dom").jsonPrimitive.content)
        assertTrue(events.any { it.path == RequestPath.NativeWebView && it.reason == RequestReason.NativeSourceRead })
        val settings = checkNotNull(events.single { it.evidence == RequestEvidence.WebViewSettings }.webView)
        assertEquals(true, settings.matchesRequested)
        assertEquals(UserAgentSummary.from(ua), settings.userAgent)
        val environment = checkNotNull(settings.environment)
        assertNotNull(environment.packageName)
        assertNotNull(environment.versionName)
        assertEquals(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA), environment.userAgentMetadata)
        assertEquals(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.GET_COOKIE_INFO), environment.cookieInfo)
        val snapshots = events.filter { it.evidence == RequestEvidence.CookieSnapshot }.map { checkNotNull(it.cookies) }
        assertTrue(snapshots.isNotEmpty())
        assertTrue(snapshots.all { it.store == CookieStore.NativeBrowser && it.completeMetadata == environment.cookieInfo })
        assertTrue(snapshots.any { (it.selected ?: 0) > 0 })
        assertFalse(events.toString().contains("private-probe"))
        assertFalse(events.toString().contains("private-token"))
        assertEquals(1, events.map { it.requestId }.distinct().size)
        assertTrue(events.none { it.evidence == RequestEvidence.TransportHeaders })
        assertFalse(events.toString().contains(ua))
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA)) {
            assertEquals(UserAgentMetadataStatus.Applied, settings.metadata)
            val hints = page.getValue("hints").jsonObject
            assertEquals("Windows", hints.getValue("platform").jsonPrimitive.content)
            assertFalse(hints.getValue("mobile").jsonPrimitive.boolean)
            assertFalse(hints.getValue("brands").jsonArray.any { it.jsonObject.getValue("brand").jsonPrimitive.content == "Android WebView" })
        } else assertEquals(UserAgentMetadataStatus.Unsupported, settings.metadata)
    } }

    @Test fun largeRenderedDocumentsCrossThePipeAndRespectTheCallerLimit(): Unit = runBlocking { fixture { broker, server ->
        val text = "文".repeat(600000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<html><head><link rel='icon' href='data:,'></head><body><article>$text</article></body></html>")
        }
        val account = session(broker, server)
        val request = BrokerRequest("large", server.url("/book").toString(), maxResponseBytes = 2 * 1024 * 1024, timeoutMillis = 60000)
        val result = account.execute(request)
        assertTrue(result.toString(), result is BrokerResult.Success)
        val response = (result as BrokerResult.Success).response
        assertTrue(response.body.size > 1024 * 1024)
        assertEquals(text, org.jsoup.Jsoup.parse(response.text()).selectFirst("article")!!.text())
        assertEquals(FailureCode.ResponseTooLarge, (account.execute(request.copy(maxResponseBytes = 1024 * 1024)) as BrokerResult.Failure).code)
    } }

    @Test fun siteCaptchaRedirectResumesTheOriginalChapterWithItsVerifiedCookies(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { fixture { broker, server ->
            val accepted = java.util.concurrent.atomic.AtomicBoolean()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/chapter/1" -> if (request.getHeader("Cookie").orEmpty().contains("verified=fixture"))
                        MockResponse().setHeader("Content-Type", "text/html").setBody("<title>free chapter</title><article>Readable</article>")
                    else MockResponse().setResponseCode(307).setHeader("Location", "/signup/man_machine_verify")
                    "/signup/man_machine_verify" -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><title>验证码</title><form id='J_ManMachineVerify' action='/accepted'></form>
                        ${if (accepted.get()) "<script>document.forms[0].submit()</script>" else ""}</html>
                    """.trimIndent())
                    "/accepted" -> MockResponse().setResponseCode(302).setHeader("Location", "/chapter/1")
                        .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val account = session(broker, server)
            account.configureSource(server.url("/").toString(), false, browserRead = true,
                defaultUserAgent = DESKTOP_USER_AGENT)
            val target = server.url("/chapter/1").toString()
            val blocked = account.execute(BrokerRequest("read", target)) as BrokerResult.Failure
            assertEquals(BrowserChallengeKind.SiteVerification, blocked.challenge)
            assertEquals(target, blocked.verificationRequest!!.url)
            accepted.set(true)
            assertEquals("free chapter", render(account, target, interactive = true).text())
            assertEquals("Readable", render(account, target, "document.querySelector('article').textContent").text())
        } }
    }

    @Test fun nativeIframeFetchCookiesAndFinalUrlArePreserved() = iframeCookies(true)

    @Test fun legadoWebViewOptionUsesNativeWebsiteWithoutRequiringBrowserRead() = iframeCookies(false)

    private fun iframeCookies(browserRead: Boolean): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/start" -> MockResponse().setResponseCode(302).setHeader("Location", "/page")
                "/frame" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<p>frame-ready</p>")
                "/echo" -> MockResponse().setBody("${request.method}|${request.body.readUtf8()}|${request.getHeader("Cookie")}")
                "/page" -> MockResponse().setHeader("Content-Type", "text/html")
                    .addHeader("Set-Cookie", "visible=fixture; Path=/")
                    .addHeader("Set-Cookie", "hidden=fixture; HttpOnly; Path=/")
                    .setBody("""
                        <html><head><link rel="icon" href="data:,"></head><body><iframe src="/frame"></iframe>
                        <iframe srcdoc="<p>srcdoc-ready</p>"></iframe><iframe src="about:blank"></iframe>
                        <script>window.addEventListener('load',async function(){
                            var echo=await (await fetch('/echo',{method:'POST',body:'field=value'})).text();
                            window.answer=JSON.stringify({echo:echo,frame:frames[0].document.body.textContent,
                                cookies:document.cookie,bridge:typeof SourceBrowser,
                                srcdoc:frames[1].document.body.textContent,blank:frames[2].location.href,
                                nativeFetch:/\[native code\]/.test(Function.prototype.toString.call(fetch)),
                                webdriver:navigator.webdriver,frameWebdriver:frames[0].navigator.webdriver});
                        });</script></body></html>
                    """.trimIndent())
                else -> MockResponse().setResponseCode(404)
            }
        }
        val account = session(broker, server)
        account.configureSource(server.url("/").toString(), true, browserRead = browserRead)
        val response = if (browserRead) render(account, server.url("/start").toString(), "window.answer || null") else {
            val rule = server.url("/start").toString() + "," + buildJsonObject {
                put("webView", true); put("webJs", "window.answer || null")
            }
            val compiled = RequestCompiler().compile("render", rule, server.url("/").toString()) as CompiledRequest.Ready
            val result = account.execute(compiled.request.copy(timeoutMillis = 60000))
            assertTrue(result.toString(), result is BrokerResult.Success)
            (result as BrokerResult.Success).response
        }
        assertEquals(ResponseKind.BrowserDocument, response.kind)
        assertEquals(0, response.status); assertEquals("", response.protocol); assertTrue(response.headers.isEmpty())
        assertEquals(server.url("/page").toString(), response.finalUrl)
        val result = Json.parseToJsonElement(response.text()).jsonObject
        assertEquals("frame-ready", result.getValue("frame").jsonPrimitive.content)
        assertEquals("srcdoc-ready", result.getValue("srcdoc").jsonPrimitive.content)
        assertEquals("about:blank", result.getValue("blank").jsonPrimitive.content)
        assertEquals("visible=fixture", result.getValue("cookies").jsonPrimitive.content)
        assertEquals("undefined", result.getValue("bridge").jsonPrimitive.content)
        assertTrue(result.getValue("nativeFetch").jsonPrimitive.boolean)
        assertFalse(result.getValue("webdriver").jsonPrimitive.boolean)
        assertEquals(result["webdriver"], result["frameWebdriver"])
        val echo = result.getValue("echo").jsonPrimitive.content
        assertTrue(echo, echo.startsWith("POST|field=value|"))
        assertTrue(echo, echo.contains("hidden=fixture"))
    } }

    @Test fun tasksKeepSessionCookiesAndAccountSwitchesKeepStorageIsolated(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val set = request.path == "/set"
                return MockResponse().setHeader("Content-Type", "text/html").apply {
                    if (set) {
                        addHeader("Set-Cookie", "persistent=fixture; Max-Age=3600; Path=/")
                        addHeader("Set-Cookie", "session=fixture; Path=/; HttpOnly")
                    }
                    setBody("""<html><head><link rel="icon" href="data:,"></head><body><script>
                        ${if (set) "localStorage.setItem('owner','alice');" else ""}
                        document.title=(localStorage.getItem('owner')||'empty')+'|'+${JsonPrimitive(request.getHeader("Cookie").orEmpty())};
                        </script></body></html>""")
                }
            }
        }
        val a = session(broker, server)
        render(a, server.url("/set").toString())
        val same = render(a, server.url("/read").toString()).text()
        assertTrue(same, same.startsWith("alice|")); assertTrue(same, same.contains("session=fixture"))
        assertEquals("empty|", render(session(broker, server), server.url("/read").toString()).text())
        val restored = render(a, server.url("/read").toString()).text()
        assertTrue(restored, restored.startsWith("alice|")); assertTrue(restored, restored.contains("persistent=fixture"))
        a.clearAccount()
        val next = session(broker, server, a.scope.sourceId, 1)
        assertEquals("empty|", render(next, server.url("/read").toString()).text())
    } }

    @Test fun foregroundNativeFormReturnsItsPostDocument(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { fixture { broker, server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setHeader("Content-Type", "text/html").setBody(
                    if (request.method == "POST") {
                        assertEquals("user=fixture", request.body.readUtf8())
                        "<html><title>accepted</title><body>fixture</body></html>"
                    } else """<html><body><form method="post" action="/signed-in"><input name="user" value="fixture"></form>
                        <script>document.forms[0].submit()</script></body></html>""")
            }
            val response = render(session(broker, server), server.url("/login").toString(),
                "document.title==='accepted' ? document.title : null", interactive = true)
            assertEquals("accepted", response.text())
            assertEquals(server.url("/signed-in").toString(), response.finalUrl)
        } }
    }

    @Test fun cancellationRetiresTheOldAccountBeforeAnotherRequest(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html").setBody(
                if (request.path == "/wait") "<html><script>setTimeout(function(){document.cookie='late=fixture';document.title='late'},5000)</script></html>"
                else "<html><script>document.title=document.cookie || 'empty'</script></html>")
        }
        val old = session(broker, server)
        val pending = async { runCatching { render(old, server.url("/wait").toString(), "document.title==='late' ? document.title : null") } }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(20, TimeUnit.SECONDS)) }
        withContext(Dispatchers.IO) { old.clearAccount() }
        withTimeout(10000) { pending.await() }
        val next = session(broker, server, old.scope.sourceId, 1)
        assertEquals("empty", render(next, server.url("/read").toString()).text())
    } }

    @Test fun challengeKeepsPendingTargetAndForegroundSessionCanResume(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { fixture { broker, server ->
            val accepted = java.util.concurrent.atomic.AtomicBoolean()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/protected" -> when {
                        request.getHeader("Cookie").orEmpty().contains("verified=fixture") ->
                            MockResponse().setHeader("Content-Type", "text/html").setBody("<title>session-resumed</title>")
                        accepted.get() -> MockResponse().setResponseCode(302).setHeader("Location", "/accepted")
                        else -> MockResponse().setResponseCode(302).setHeader("Location", "/antibot")
                    }
                    "/antibot" -> MockResponse().setHeader("Content-Type", "text/html")
                        .setBody("<html><title>Just a moment</title></html>")
                    "/accepted" -> MockResponse().setHeader("Content-Type", "text/html")
                        .addHeader("Set-Cookie", "verified=fixture; HttpOnly; Path=/")
                        .setBody("<html><title>accepted</title></html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val account = session(broker, server)
            val target = server.url("/protected").toString()
            val blocked = account.execute(BrokerRequest("read", target, timeoutMillis = 60000))
            assertEquals(FailureCode.BrowserRequired, (blocked as BrokerResult.Failure).code)
            assertEquals(target, (account.read(StorageRequest(StorageArea.Account,
                StorageRequestKey.BROWSER_PENDING_URL)) as StorageResult.Value).value)
            accepted.set(true)
            val resumed = withTimeout(30000) {
                render(account, target, "document.title==='accepted' ? document.title : null", interactive = true)
            }
            assertEquals("accepted", resumed.text())
            assertEquals(server.url("/accepted").toString(), resumed.finalUrl)
            assertEquals("session-resumed", render(account, target).text())
        } }
    }

    @Test fun cancellingARequestKeepsPersistentStateWithoutClearingTheAccount(): Unit = runBlocking { fixture { broker, server ->
        val started = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/hold") started.complete(Unit)
                return MockResponse().setHeader("Content-Type", "text/html").setHeader("Cache-Control", "no-store").apply {
                    if (path == "/set") addHeader("Set-Cookie", "persistent=fixture; Max-Age=3600; HttpOnly; Path=/")
                    val body = when (path) {
                        "/set" -> "localStorage.setItem('owner','fixture');window.answer='ready'"
                        "/hold" -> "window.answer=null"
                        else -> "window.answer=JSON.stringify({owner:localStorage.getItem('owner'),cookie:${JsonPrimitive(request.getHeader("Cookie").orEmpty())}})"
                    }
                    setBody("<html><head><link rel='icon' href='data:,'></head><body><script>$body</script></body></html>")
                }
            }
        }
        val account = session(broker, server)
        render(account, server.url("/set").toString(), "window.answer || null")
        val pending = launch { render(account, server.url("/hold").toString(), "window.answer || null") }
        withTimeout(20000) { started.await() }
        withTimeout(20000) { pending.cancelAndJoin() }
        val restored = Json.parseToJsonElement(render(account, server.url("/read").toString(), "window.answer || null").text()).jsonObject
        assertEquals("fixture", restored.getValue("owner").jsonPrimitive.content)
        assertEquals("persistent=fixture", restored.getValue("cookie").jsonPrimitive.content)
        assertEquals(0L, account.scope.accountGeneration)
        // Session cookies are deliberately not given an invented persistence guarantee.
    } }

    @Test fun publicLoginLinkIsReadableAndActualLoginAndVerificationPagesHaveDistinctKinds(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html").setBody(
                when (request.path) {
                    "/login" -> "<html><form><input type='password'></form></html>"
                    "/antibot" -> "<html><title>Site verification</title></html>"
                    else -> "<html><title>Public catalog</title><nav><a href='/login'>Login</a></nav><ul><li>Fixture</li></ul></html>"
                })
        }
        val account = session(broker, server)
        assertEquals("Public catalog", render(account, server.url("/public").toString(), "document.title").text())
        for ((path, kind) in listOf("/login" to BrowserChallengeKind.Login, "/antibot" to BrowserChallengeKind.SiteVerification)) {
            val result = account.execute(BrokerRequest("classification", server.url(path).toString())) as BrokerResult.Failure
            assertEquals(FailureCode.BrowserRequired, result.code)
            assertEquals(kind, result.challenge)
        }
    } }

    @Test fun onlyVisibleAliyunSliderRequiresVerification(): Unit = runBlocking { fixture { broker, server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "text/html")
                .setBody("""<html><head><title>Reading fixture</title><link rel='icon' href='data:,'></head><body>
                    <div ${if (request.path == "/hidden") "style='display:none'" else ""}>
                    <div id='aliyunCaptcha-sliding-wrapper'>Slide to verify</div></div><p>Chapter fixture</p></body></html>""")
        }
        val account = session(broker, server)
        assertEquals("Reading fixture", render(account, server.url("/hidden").toString()).text())
        val target = server.url("/visible").toString()
        val blocked = account.execute(BrokerRequest("slider", target)) as BrokerResult.Failure
        assertEquals(FailureCode.BrowserRequired, blocked.code)
        assertEquals(BrowserChallengeKind.SiteVerification, blocked.challenge)
        assertEquals(target, blocked.verificationRequest?.url)
    } }

    @Test fun suppliedHtmlUsesTheSyntheticBrowserWithoutNetwork(): Unit = runBlocking { fixture { broker, server ->
        val result = session(broker, server).execute(BrokerRequest("html", server.url("/").toString(),
            browser = BrowserOptions(html = "<html><head><link rel='icon' href='data:,'></head><body>synthetic</body></html>",
                script = "document.body.textContent")))
        assertTrue(result.toString(), result is BrokerResult.Success)
        assertEquals("synthetic", (result as BrokerResult.Success).response.text().trim())
        val unexpected = List(server.requestCount) { server.takeRequest(1, TimeUnit.SECONDS)?.path }
        assertEquals("Synthetic HTML unexpectedly fetched $unexpected", emptyList<String>(), unexpected)
    } }

    @Test fun backgroundDocumentHasAnActualViewport(): Unit = runBlocking { fixture { broker, server ->
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(
            "<meta name='viewport' content='width=device-width,initial-scale=1'><div style='width:50vw' id='half'></div>"))
        val result = Json.parseToJsonElement(render(session(broker, server), server.url("/").toString(),
            "JSON.stringify({width:innerWidth,height:innerHeight,half:document.getElementById('half').getBoundingClientRect().width})").text()).jsonObject
        val width = result.getValue("width").jsonPrimitive.double
        assertTrue(width > 0)
        assertTrue(result.getValue("height").jsonPrimitive.double > 0)
        assertEquals(width / 2, result.getValue("half").jsonPrimitive.double, 1.0)
    } }

}
