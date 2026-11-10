package indi.renakoni.nextvol.sourceexecution

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hnovel.execution.*
import hnovel.network.*
import hnovel.rules.OutputKind
import hnovel.rules.RuleValue
import indi.renakoni.nextvol.sourcebrowser.AndroidSourceBrowser
import indi.renakoni.nextvol.sourcebrowser.BrowserTestHostActivity
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Real Chromium assertions on API 24 and 35. No public site or account participates. */
@RunWith(AndroidJUnit4::class)
class SourceBrowserInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun scriptMessagesAndChapterContextCrossTheIsolatedBridge(): Unit = runBlocking {
        val root = File(context.cacheDir, "source-feedback-${System.nanoTime()}")
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { sessions ->
                val session = sessions.open(SourceScope("feedback", "A", "legado"), emptyList())
                val identity = authority.issue("A", "legado", "1", "feedback")
                val limits = ExecutionLimits(timeoutMillis = 15000)
                SourceExecutionBroker(identity, authority, session, limits).use { bridge ->
                    val task = ExecutionTask.Script("""
                        var toastResult=typeof java.toast('Source feedback fixture');
                        book.setReverseToc(true);
                        [toastResult,title,nextChapterUrl,book.getReverseToc()]
                    """.trimIndent(), chapter = kotlinx.serialization.json.buildJsonObject {
                        put("title", kotlinx.serialization.json.JsonPrimitive("Chapter one"))
                    }, nextChapterUrl = "https://fixture.invalid/chapter-two")
                    assertEquals(ExecutionResult.Success("[\"undefined\",\"Chapter one\",\"https://fixture.invalid/chapter-two\",true]"),
                        executor.execute(identity, task, limits, bridge))
                    assertFalse(bridge.interactionRequired)
                    for (expand in listOf(false, true)) {
                        val rule = ExecutionTask.Rule("@js:'{{page}}'", RuleValue.Empty, OutputKind.Text,
                            page = 3, scriptTemplates = expand)
                        val result = SourceExecutionBroker(identity, authority, session, limits, page = 3).use {
                            executor.execute(identity, rule, limits, it)
                        }
                        assertTrue(result.toString(), result is ExecutionResult.Success)
                        val evaluated = kotlinx.serialization.json.Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output)
                        assertEquals(RuleValue.Text(if (expand) "3" else "{{page}}"), evaluated.value)
                    }
                }
            }
        } finally { executor.close(); root.deleteRecursively() }
    }

    @Test fun headerScriptsCanReadTheDeviceWebViewUserAgent(): Unit = runBlocking {
        val expected = withContext(Dispatchers.Main) { android.webkit.WebSettings.getDefaultUserAgent(context) }
        val root = File(context.cacheDir, "webview-ua-${System.nanoTime()}")
        val authority = ExecutionAuthority()
        val executor = AndroidIsolatedExecutor(context, authority)
        try {
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { sessions ->
                val session = sessions.open(SourceScope("webview-ua", "A", "legado"), emptyList())
                val identity = authority.issue("A", "legado", "1", "webview-ua")
                val limits = ExecutionLimits(timeoutMillis = 15000)
                SourceExecutionBroker(identity, authority, session, limits).use { bridge ->
                    val result = executor.execute(identity, ExecutionTask.Script("String(java.getWebViewUA())"), limits, bridge)
                    assertTrue(result.toString(), result is ExecutionResult.Success)
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(expected).toString(), (result as ExecutionResult.Success).output)
                    assertTrue(expected.contains("Mozilla/5.0"))
                    assertFalse(bridge.interactionRequired)
                }
            }
        } finally { executor.close(); root.deleteRecursively() }
    }

    @Test fun sourceUserAgentMatchesNavigatorScriptsAndFetch(): Unit = runBlocking {
        MockWebServer().use { server ->
            val agents = ConcurrentLinkedQueue<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    agents.add(request.getHeader("User-Agent").orEmpty())
                    return when (request.path) {
                        "/script.js" -> MockResponse().setHeader("Content-Type", "application/javascript")
                            .setBody("fetch('/chapter').then(function(r){return r.text()}).then(function(){window.finished=true;});")
                        "/chapter" -> MockResponse().setBody("chapter")
                        else -> MockResponse().setHeader("Content-Type", "text/html")
                            .setBody("<html><body><script src='/script.js'></script></body></html>")
                    }
                }
            }
            server.start()
            val root = File(context.cacheDir, "browser-agent-${System.nanoTime()}")
            try {
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                    val session = broker.open(SourceScope("agent", "A", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                    val events = ConcurrentLinkedQueue<RequestDiagnostic>()
                    session.traceRequests { events += it }
                    val result = session.execute(BrokerRequest("render", server.url("/").toString(),
                        headers = mapOf("user-agent" to "NovelFixture/1.0"), timeoutMillis = 60000,
                        browser = BrowserOptions(script = "window.finished ? navigator.userAgent : null")))
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    assertEquals("NovelFixture/1.0", (result as BrokerResult.Success).response.text())
                    assertTrue(agents.size >= 3)
                    assertTrue(agents.toString(), agents.all { it == "NovelFixture/1.0" })
                    val rootId = events.first().requestId
                    assertTrue(events.any { it.path == RequestPath.MediatedWebView && it.reason == RequestReason.BrowserRendering })
                    val settings = events.single { it.evidence == RequestEvidence.WebViewSettings }
                    assertEquals(rootId, settings.requestId)
                    assertEquals(true, settings.webView?.matchesRequested)
                    assertEquals(UserAgentFamily.Other, settings.webView?.userAgent?.family)
                    val environment = checkNotNull(settings.webView?.environment)
                    assertNotNull(environment.packageName)
                    assertNotNull(environment.versionName)
                    assertEquals(androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.GET_COOKIE_INFO), environment.cookieInfo)
                    val metadata = if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.USER_AGENT_METADATA))
                        UserAgentMetadataStatus.UnhandledUserAgent else UserAgentMetadataStatus.Unsupported
                    assertEquals(metadata, settings.webView?.metadata)
                    val sent = events.filter { it.evidence == RequestEvidence.TransportHeaders }
                    assertTrue(sent.size >= 3)
                    assertTrue(sent.all { it.requestId != rootId && it.parentRequestId == rootId })
                    assertTrue(events.any { it.parentRequestId == rootId && it.cookies?.store == CookieStore.HttpJar })
                    assertFalse(events.toString().contains("NovelFixture/1.0"))
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun defaultRenderingWaitsForDynamicChapterText(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <html><head><title>Just a moment</title></head><body><main id="chapter"></main><script>
                setTimeout(function(){document.getElementById('chapter').textContent='dynamic chapter';},500);
                </script></body></html>
            """.trimIndent()))
            server.start()
            val root = File(context.cacheDir, "browser-delay-${System.nanoTime()}")
            try {
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                    val session = broker.open(SourceScope("delay", "A", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                    val result = session.execute(BrokerRequest("render", server.url("/").toString(), timeoutMillis = 60000,
                        browser = BrowserOptions()))
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    val html = (result as BrokerResult.Success).response.text()
                    assertEquals("dynamic chapter", org.jsoup.Jsoup.parse(html).selectFirst("#chapter")?.text())
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun redirectRefusalPreservesRedactedOriginAcrossBrowserBinder(): Unit = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { cdn ->
            source.start(); cdn.start()
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/private?signature=secret")))
            val root = File(context.cacheDir, "browser-origin-${System.nanoTime()}")
            try {
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                    val session = broker.open(SourceScope("permission", "A", "legado"), listOf(NetworkGrant(source.url("/").toString(), true)))
                    val result = session.execute(BrokerRequest("browser", source.url("/").toString(), browser = BrowserOptions())) as BrokerResult.Failure
                    assertEquals(hnovel.network.FailureCode.OriginDenied, result.code)
                    assertEquals(OriginDenial(sourceOrigin(cdn.url("/").toString())!!, ResourceKind.Document), result.denial)
                    assertEquals(listOf(result.denial), session.deniedOrigins)
                    assertEquals(0, cdn.requestCount)
                }
            } finally { root.deleteRecursively() }
        } }
    }

    @Test fun dnsAndAddressFailuresRemainTypedAcrossBrowserBinder(): Unit = runBlocking {
        for ((dns, expected) in listOf(
            okhttp3.Dns { throw java.net.UnknownHostException() } to hnovel.network.FailureCode.Dns,
            okhttp3.Dns { listOf(java.net.InetAddress.getByName("198.18.0.1")) } to hnovel.network.FailureCode.AddressDenied
        )) {
            val root = File(context.cacheDir, "browser-network-${System.nanoTime()}")
            try {
                SourceBroker(root.toPath(), dns, browser = AndroidSourceBrowser(context)).use { broker ->
                    val session = broker.open(SourceScope("network", "A", "legado"), listOf(NetworkGrant("https://source.invalid/")))
                    val result = session.execute(BrokerRequest("browser", "https://source.invalid/", browser = BrowserOptions()))
                    assertTrue(result.toString(), result is BrokerResult.Failure)
                    assertEquals(expected, (result as BrokerResult.Failure).code)
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun imageVerificationReloadKeepsSourceHeadersAndReturnsTheNewCodeAcrossBinder(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use {
            MockWebServer().use { server ->
                val bytes = java.io.ByteArrayOutputStream().also { output ->
                    android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
                        .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                }.toByteArray()
                repeat(2) {
                    server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
                        .addHeader("Set-Cookie", "challenge=fixture; Path=/").setBody(okio.Buffer().write(bytes)))
                }
                server.start()
                val base = server.url("/").toString()
                val root = File(context.cacheDir, "image-verification-${System.nanoTime()}")
                val authority = ExecutionAuthority()
                val executor = AndroidIsolatedExecutor(context, authority)
                try {
                    SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                        val grants = listOf(NetworkGrant(base, true))
                        val a = broker.open(SourceScope("verification", "A", "legado"), grants)
                        val b = broker.open(SourceScope("verification", "B", "legado"), grants)
                        a.setCookie(base, "account=alice"); b.setCookie(base, "account=bob")
                        val identity = authority.issue("A", "legado", "1", "verification")
                        val limits = ExecutionLimits(timeoutMillis = 60000)
                        SourceExecutionBroker(identity, authority, a, limits, base, allowInteraction = true).use { bridge ->
                            val task = ExecutionTask.Rule("@js:java.getVerificationCode('/captcha.png')", RuleValue.Empty, OutputKind.Text,
                                baseUrl = base, sourceHeaderRule = "@js:JSON.stringify({Authorization:'Bearer fixture','User-Agent':'captcha-agent',Cookie:'account=source-alice'})")
                            val pending = async { executor.execute(identity, task, limits, bridge) }
                            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                            fun input(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                                if (node == null) return null
                                if (node.isEditable) return node
                                for (index in 0 until node.childCount) input(node.getChild(index))?.let { return it }
                                return null
                            }
                            suspend fun enterCode(code: String) {
                                val arguments = android.os.Bundle().apply {
                                    putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
                                }
                                withTimeout(20000) { while (input(automation.rootInActiveWindow)?.performAction(
                                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) != true) delay(100)
                                }
                            }
                            enterCode("old-code")
                            val reload = context.getString(indi.renakoni.nextvol.R.string.source_browser_reload)
                            withTimeout(10000) {
                                while (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(reload)?.firstOrNull()
                                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) != true) delay(100)
                            }
                            withTimeout(10000) {
                                while (input(automation.rootInActiveWindow)?.let { it.text.isNullOrEmpty() } != true) delay(100)
                            }
                            enterCode(" A7c ")
                            val label = context.getString(android.R.string.ok)
                            withTimeout(10000) {
                                while (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull()
                                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) != true) delay(100)
                            }
                            val result = withTimeout(10000) { pending.await() }
                            assertTrue(result.toString(), result is ExecutionResult.Success)
                            val value = kotlinx.serialization.json.Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value
                            assertEquals(RuleValue.Text(" A7c "), value)
                        }
                        assertTrue(a.cookie(base).contains("challenge=fixture"))
                        assertEquals("account=bob", b.cookie(base))
                        repeat(2) {
                            val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
                            assertEquals("/captcha.png", request.path)
                            assertTrue(request.getHeader("Cookie").orEmpty().split("; ").contains("account=source-alice"))
                            assertEquals("Bearer fixture", request.getHeader("Authorization"))
                            assertEquals("captcha-agent", request.getHeader("User-Agent"))
                        }
                        assertEquals(2, server.requestCount)
                    }
                } finally { executor.close(); root.deleteRecursively() }
            }
        }
    }

    @Test fun ruleWebViewPassesDynamicHeadersThroughWorkerAndChromium(): Unit = runBlocking {
        MockWebServer().use { server ->
            val seen = ConcurrentLinkedQueue<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    if (request.path == "/token") return MockResponse().setBody("browser-token")
                    val accepted = request.getHeader("Authorization") == "browser-token" &&
                        request.getHeader("User-Agent") == "rule-browser-agent" && request.getHeader("Cookie") == "source=rule"
                    return MockResponse().setHeader("Content-Type", "text/html").setBody(
                        "<html><head><link rel='icon' href='data:,'><title>${if (accepted) "accepted" else "missing header"}</title></head></html>")
                }
            }
            server.start()
            val base = server.url("/").toString()
            val root = File(context.cacheDir, "rule-browser-header-${System.nanoTime()}")
            val authority = ExecutionAuthority()
            val executor = AndroidIsolatedExecutor(context, authority)
            try {
                SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { sessions ->
                    val session = sessions.open(SourceScope("browser-header", "A", "legado"), listOf(NetworkGrant(base, true)))
                    val identity = authority.issue("A", "legado", "1", "browser-header")
                    val limits = ExecutionLimits(timeoutMillis = 60000)
                    SourceExecutionBroker(identity, authority, session, limits, base).use { bridge ->
                        val task = ExecutionTask.Rule("@js:java.webView(null,baseUrl+'protected','document.title')",
                            RuleValue.Empty, OutputKind.Text, baseUrl = base,
                            sourceHeaderRule = "@js:JSON.stringify({Authorization:java.ajax('/token'),'User-Agent':'rule-browser-agent',Cookie:'source=rule'})")
                        val result = executor.execute(identity, task, limits, bridge)
                        assertTrue(result.toString(), result is ExecutionResult.Success)
                        val value = kotlinx.serialization.json.Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value
                        assertEquals(RuleValue.Text("accepted"), value)
                    }
                }
                assertEquals(listOf("/token", "/protected"), seen.map { it.path })
                assertNull(seen.first().getHeader("Authorization"))
            } finally { executor.close(); root.deleteRecursively() }
        }
    }

    @Test fun rendersThroughBrokerAndKeepsSameDomainAccountsAndBrowserStorageSeparate(): Unit = runBlocking {
        val seen = ConcurrentLinkedQueue<String>()
        MockWebServer().use { denied -> MockWebServer().use { server ->
            denied.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/redirect") return MockResponse().setResponseCode(302).addHeader("Location", "/page")
                    if (request.path == "/api") {
                        seen.add(request.getHeader("Cookie").orEmpty())
                        return MockResponse().setBody("ok").addHeader("Set-Cookie", "hidden=server; HttpOnly; Path=/")
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><head><title>initial</title></head><body><main></main><script>
                        var prior=localStorage.getItem('owner')||'empty';
                        localStorage.setItem('owner','persisted');document.cookie='visible=page; Path=/';
                        var blocked='${denied.url("/forbidden")}';
                        fetch(blocked).catch(function(){});
                        try{new WebSocket(blocked.replace('http','ws'))}catch(e){}
                        var frame=document.createElement('iframe');frame.src=blocked;document.body.appendChild(frame);
                        var worker;
                        try {worker=navigator.serviceWorker ? navigator.serviceWorker.register('/service-worker.js')
                            .then(function(){return 'worker-allowed'},function(){return 'worker-blocked'}) : Promise.resolve('worker-unavailable');}
                        catch(e){worker=Promise.resolve('worker-blocked');}
                        Promise.all([worker,fetch('/api',{method:'POST',body:'field=value',headers:{'Content-Type':'application/x-www-form-urlencoded','Cookie':'page-forged=blocked'}})
                          .then(function(r){return r.text()})]).then(function(values){
                            document.title=prior+':'+values[1]+':'+document.cookie+':'+values[0];window.finished=true;});
                        </script></body></html>
                    """.trimIndent())
                }
            }
            server.start()
            val root = File(context.cacheDir, "browser-test-${System.nanoTime()}")
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val grants = listOf(NetworkGrant(server.url("/").toString(), true))
                fun session(id: String) = broker.open(SourceScope("browser-fixture", id, "legado", 1), grants).apply {
                    configureSource(server.url("/").toString(), true)
                }
                val a = session("A"); val b = session("B")
                a.setCookie(server.url("/").toString(), "account=alice")
                b.setCookie(server.url("/").toString(), "account=bob")
                suspend fun render(session: SourceSession, path: String = "/page"): String {
                    val result = session.execute(BrokerRequest("render", server.url(path).toString(), timeoutMillis = 60000,
                        browser = BrowserOptions(script = "window.finished ? document.title : null")))
                    assertTrue(result.toString(), result is BrokerResult.Success)
                    return (result as BrokerResult.Success).response.text().also {
                        assertTrue(it, it.endsWith(":worker-blocked") || it.endsWith(":worker-unavailable"))
                    }
                }
                assertTrue(render(a).startsWith("empty:ok:"))
                assertTrue(render(b).startsWith("empty:ok:"))
                assertTrue(render(a, "/redirect").startsWith("persisted:ok:"))
                a.clearAccount()
                val next = broker.open(SourceScope("browser-fixture", "A", "legado", 2), grants)
                assertEquals("", next.cookie(server.url("/").toString()))
                assertTrue(render(next).startsWith("persisted:ok:"))
                assertEquals(4, seen.size)
                assertTrue(seen.elementAt(0).contains("account=alice"))
                assertTrue(seen.elementAt(1).contains("account=bob"))
                assertFalse(seen.any { it.contains("page-forged") })
                assertFalse(next.browserCookie(server.url("/").toString()).contains("hidden"))
                assertTrue(next.cookie(server.url("/").toString()).contains("hidden=server"))
                assertEquals(0, denied.requestCount)
                assertTrue(List(server.requestCount) { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!! }
                    .none { it.path == "/service-worker.js" })
                val name = if (android.os.Build.VERSION.SDK_INT >= 28) "app_webview_source_browser" else "app_webview"
                assertFalse(File(context.applicationInfo.dataDir, name).exists())
            }
            root.deleteRecursively()
        } }
    }

    @Test fun cancelledPageCannotRestoreCookiesAndTheNextOwnerCanRender(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<p>late</p>")
                .addHeader("Set-Cookie", "late=secret; Max-Age=3600; Path=/").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<title>next</title>"))
            server.start()
            val root = File(context.cacheDir, "browser-cancel-${System.nanoTime()}")
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val grant = listOf(NetworkGrant(server.url("/").toString(), true))
                val old = broker.open(SourceScope("cancel", "A", "legado", 1), grant)
                val pending = async { runCatching { old.execute(BrokerRequest("cancel", server.url("/").toString(),
                    timeoutMillis = 60000, browser = BrowserOptions())) } }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(15, java.util.concurrent.TimeUnit.SECONDS)) }
                old.clearAccount()
                withTimeout(10000) { pending.join() }
                val next = broker.open(SourceScope("cancel", "A", "legado", 2), grant)
                val result = next.execute(BrokerRequest("next", server.url("/").toString(), timeoutMillis = 60000,
                    browser = BrowserOptions("document.title"))) as BrokerResult.Success
                assertEquals("next", result.response.text())
                assertEquals("", next.cookie(server.url("/").toString()))
            }
            root.deleteRecursively()
        }
    }

    @Test fun inlineBrowserPostCrossesWorkerAndAllowsExplicitConfirmation(): Unit = runBlocking {
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { MockWebServer().use { server ->
            val pageLoaded = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/" -> MockResponse().setHeader("Content-Type", "text/html").setBody(
                        "<html><head><link rel='icon' href='data:,'></head><body>Signed in" +
                            "<script>window.addEventListener('load',function(){fetch('/loaded')});</script></body></html>")
                    "/loaded" -> MockResponse().setResponseCode(204).also { pageLoaded.complete(Unit) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val root = File(context.cacheDir, "browser-confirm-${System.nanoTime()}")
            val authority = ExecutionAuthority()
            val executor = AndroidIsolatedExecutor(context, authority)
            try { SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val session = broker.open(SourceScope("confirm", "A", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val identity = authority.issue("A", "legado", "1", "confirm")
                val limits = ExecutionLimits(timeoutMillis = 60000)
                val rule = kotlinx.serialization.json.JsonPrimitive("""${server.url("/")}, {"method":"POST","body":"user=fixture","headers":{"User-Agent":"inline-agent","X-Inline":"kept"}}""")
                val pending = async { SourceExecutionBroker(identity, authority, session, limits, allowInteraction = true).use { bridge ->
                    executor.execute(identity, ExecutionTask.Script("java.startBrowserAwait($rule,'Fixture login',false).body()"), limits, bridge)
                } }
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                val label = context.getString(indi.renakoni.nextvol.R.string.source_browser_done)
                fun find(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
                    node ?: return null
                    if (node.isVisibleToUser && (node.text?.contains(text, ignoreCase = true) == true ||
                            node.contentDescription?.contains(text, ignoreCase = true) == true)) return node
                    for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
                    return null
                }
                // Page readiness must not depend on Chromium publishing its accessibility text.
                // Keep accessibility for the native confirmation button and verify the returned HTML below.
                withTimeout(20000) { pageLoaded.await() }
                assertFalse(pending.isCompleted)
                withTimeout(10000) {
                    while (automation.windows.none { window ->
                        find(window.root?.takeIf { it.packageName == context.packageName }, label)
                            ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                    }) delay(100)
                }
                val result = withTimeout(10000) { pending.await() }
                assertTrue(result.toString(), result is ExecutionResult.Success)
                assertTrue((result as ExecutionResult.Success).output.contains("Signed in"))
                val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("/", request.path)
                assertEquals("POST", request.method)
                assertEquals("user=fixture", request.body.readUtf8())
                assertEquals("inline-agent", request.getHeader("User-Agent"))
                assertEquals("kept", request.getHeader("X-Inline"))
                val loaded = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
                assertEquals("/loaded", loaded.path)
                assertEquals("GET", loaded.method)
                assertEquals(2, server.requestCount)
            } } finally { executor.close(); root.deleteRecursively() }
        } }
    }

    @Test fun foregroundLoginPreservesPostResponseAndCommitsOnlyItsOwnCookies(): Unit = runBlocking {
        // Await RESUMED using the Activity lifecycle; accessibility is only needed for the
        // browser's cross-process window below. Close the host after each attempt.
        ActivityScenario.launch(BrowserTestHostActivity::class.java).use { MockWebServer().use { server ->
            val posted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST") {
                        assertEquals("user=fixture", request.body.readUtf8())
                        posted.complete(Unit)
                        return MockResponse().setHeader("Content-Type", "text/html").addHeader("Set-Cookie", "auth=accepted; Path=/; HttpOnly")
                            .setBody("<html><title>POST accepted</title><body>Signed in</body></html>")
                    }
                    return MockResponse().setHeader("Content-Type", "text/html").setBody("""
                        <html><body><form method="post" action="/login"><input name="user" value="fixture"></form>
                        <script>setTimeout(function(){document.forms[0].submit()},100);</script></body></html>
                    """.trimIndent())
                }
            }
            server.start()
            val root = File(context.cacheDir, "browser-login-${System.nanoTime()}")
            SourceBroker(root.toPath(), browser = AndroidSourceBrowser(context)).use { broker ->
                val session = broker.open(SourceScope("login", "A", "legado", 1), listOf(NetworkGrant(server.url("/").toString(), true)))
                val login = async { session.execute(BrokerRequest("login", server.url("/login").toString(), timeoutMillis = 60000,
                    headers = mapOf("User-Agent" to "source-login-agent", "Authorization" to "Bearer source-login", "Cookie" to "source=login"),
                    browser = BrowserOptions(script = "document.title === 'POST accepted' ? document.title : null", interactive = true))) }
                val formPosted = withTimeoutOrNull(20000) { posted.await(); true } == true
                assertTrue("Form POST did not reach broker; requests=${server.requestCount}, browserCompleted=${login.isCompleted}", formPosted)
                // The source's completion expression returns only after the form succeeds.
                // A user does not have to declare that the login is complete.
                val result = withTimeout(20000) { login.await() }
                assertTrue(result.toString(), result is BrokerResult.Success)
                assertEquals("POST accepted", (result as BrokerResult.Success).response.text())
                assertEquals("auth=accepted", session.cookie(server.url("/").toString()))
                val navigation = List(server.requestCount) { server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!! }
                    .filter { it.path == "/login" }
                assertEquals(listOf("GET", "POST"), navigation.map { it.method })
                assertEquals("source-login-agent", navigation.first().getHeader("User-Agent"))
                assertEquals("source-login-agent", navigation.last().getHeader("User-Agent"))
                assertEquals("Bearer source-login", navigation.first().getHeader("Authorization"))
                assertEquals("source=login", navigation.first().getHeader("Cookie"))
            }
            root.deleteRecursively()
        } }
    }
}
