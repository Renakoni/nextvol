package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import hnovel.rules.ScriptArgumentType.Array
import hnovel.rules.ScriptArgumentType.Object
import hnovel.rules.ScriptArgumentType.String
import hnovel.rules.ScriptHostCall
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class NetworkBridgeTest {
    @get:Rule val folder = TemporaryFolder()
    private val authority = ExecutionAuthority()
    private val id = authority.issue("a", "legado", "1", "fixture")

    @Test fun browserRefetchStaysHttpAndUsesTheSourceAdmissionGate() = runBlocking {
        val browser = BrowserExecutor { _, _, _, _, _ -> error("HTTP refetch must not reopen the browser") }
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            server.enqueue(MockResponse().setBody("chapter"))
            server.enqueue(MockResponse().setBody("unpaced"))
            SourceBroker(folder.root.toPath(), browser = browser).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, browserRead = true, concurrentRate = "60000")
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base, allowInteraction = true).use { bridge ->
                    assertEquals("chapter", bridge.call("browser.refetch", listOf(JsonPrimitive(base))).jsonObject["body"]?.jsonPrimitive?.content)
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(timeoutMillis = 100), base, allowInteraction = true).use { bridge ->
                    assertTrue(runCatching { bridge.call("browser.refetch", listOf(JsonPrimitive(base))) }.isFailure)
                    assertEquals(hnovel.network.FailureCode.Timeout, bridge.requestFailure?.code)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun scriptValueAndFileCachesCannotCarryAnAccountIntoItsReplacement() = runBlocking {
        val scope = SourceScope("fixture", "a", "legado")
        SourceBroker(folder.root.toPath()).use { sessions ->
            val session = sessions.open(scope, emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { old ->
                assertEquals(ExecutionResult.Success("\"ready\""), script(old,
                    "cache.put('token','synthetic-a',0);cache.putFile('favorites','[1,2]',0);source.put('theme','dark');'ready'"))
                session.clearAccount()
                val fresh = sessions.open(scope.copy(accountGeneration = 1), emptyList())
                val ticket = authority.issue("a", "legado", "1", "fixture", 1)
                SourceExecutionBroker(ticket, authority, fresh, ExecutionLimits()).use { next ->
                    assertEquals(JsonNull, next.call("cache.get", listOf(JsonPrimitive("token"))))
                    assertEquals(JsonNull, next.call("cache.getFile", listOf(JsonPrimitive("favorites"))))
                    assertEquals(JsonPrimitive("dark"), next.call("source.get", listOf(JsonPrimitive("theme"))))
                    assertTrue(runCatching { old.call("cache.put", listOf(JsonPrimitive("token"), JsonPrimitive("late"))) }.isFailure)
                    next.call("cache.put", listOf(JsonPrimitive("token"), JsonPrimitive("synthetic-b"), JsonPrimitive(0)))
                }
            }
        }
        SourceBroker(folder.root.toPath()).use { sessions ->
            val session = sessions.open(scope.copy(accountGeneration = 1), emptyList())
            assertEquals(StorageResult.Value("synthetic-b"), session.read(StorageRequest(StorageArea.Cache, "value:token")))
            assertEquals(StorageResult.Value(null), session.read(StorageRequest(StorageArea.Cache, "file:favorites")))
        }
    }

    @Test fun cookieOverloadMatchesThePinnedReferenceAndWebWritesUseTheOwnedBrowser() = runBlocking {
        val url = "https://www.example.org/"
        val writes = mutableListOf<String>()
        var delayed: RequestCommitGuard? = null
        val browser = object : BrowserExecutor {
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult {
                assertEquals(SourceScope("fixture", "a", "legado"), session.scope)
                assertEquals(url, request.url)
                assertTrue(request.headers.isEmpty())
                assertFalse(options.interactive)
                assertTrue(options.nativeWebsite)
                delayed = guard
                guard.commit { writes += checkNotNull(options.webCookie) }
                return BrokerResult.Success(BrokerResponse(0, url, emptyMap(), byteArrayOf(), "UTF-8", 0, kind = ResponseKind.BrowserDocument))
            }
        }
        SourceBroker(folder.root.toPath(), okhttp3.Dns { error("Cookie operations must not resolve DNS") }, browser = browser).use { sessions ->
            val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(url)))
            session.configureSource(url, true)
            session.setCookie(url, "sid=token=part; visible=yes")
            SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 32), url).use { bridge ->
                // E JsExtensions.getCookie delegates null to getCookie, otherwise to getKey;
                // CookieStore.getKey returns an empty string for a missing name.
                assertEquals(ExecutionResult.Success("[\"sid=token=part; visible=yes\",\"sid=token=part; visible=yes\",\"token=part\",\"\"]"),
                    script(bridge, "[java.getCookie('$url'),java.getCookie('$url',null),java.getCookie('$url','sid'),java.getCookie('$url','absent')]"))
                session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS, """{"Cookie":"http=only"}"""))
                assertEquals(ExecutionResult.Success("true"), script(bridge, "cookie.setWebCookie('$url','sid=web');true"))
                assertEquals(listOf("sid=web"), writes)
                assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("cookie.setWebCookie", 2, listOf(String, String))), script(bridge,
                    "cookie.setWebCookie('https://accounts.example.org/','sid=denied')"))
                assertEquals(1, writes.size)
                session.clearAccount()
                assertThrows(IllegalStateException::class.java) { delayed!!.commit { writes += "late" } }
                assertTrue(runCatching { bridge.call("cookie.setWebCookie", listOf(JsonPrimitive(url), JsonPrimitive("late=yes"))) }.isFailure)
            }
        }
    }

    @Test fun toastFeedbackPreservesBrowserHandoffAndRejectsRetiredDisplay(): Unit = runBlocking {
        val messages = mutableListOf<Pair<String, Boolean>>()
        var delayed: RequestCommitGuard? = null
        val browser = object : BrowserExecutor {
            override suspend fun showMessage(message: String, long: Boolean, guard: RequestCommitGuard) {
                delayed = guard
                guard.commit { messages += message to long }
            }
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("Feedback must not navigate")
        }
        SourceBroker(folder.root.toPath(), okhttp3.Dns { error("Feedback must not resolve DNS") }, browser = browser).use { sessions ->
            val session = sessions.open(SourceScope("fixture", "a", "legado"), emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { broker ->
                assertEquals(ExecutionResult.Success("[\"undefined\",\"undefined\"]"), script(broker,
                    "[typeof java.toast('short'),typeof java.longToast('long')]"))
                assertEquals(listOf("short" to false, "long" to true), messages)
                assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.startBrowserAwait", 2, listOf(String, String))), script(broker,
                    "java.toast('verify');java.startBrowserAwait('https://fixture.invalid/','verification')"))
                assertTrue(broker.interactionRequired)
                authority.revoke(id)
                assertThrows(IllegalStateException::class.java) { delayed!!.commit { messages += "retired" to false } }
                assertEquals(listOf("short" to false, "long" to true, "verify" to false), messages)
            }
        }
    }

    @Test fun inlineTextAndConnectResponsesDoNotNeedANetworkGrant(): Unit = runBlocking {
        SourceBroker(folder.root.toPath(), okhttp3.Dns { error("Inline data must not resolve DNS") }).use { sessions ->
            val session = sessions.open(SourceScope("fixture", "a", "legado"), emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { broker ->
                assertEquals(ExecutionResult.Success("[\"chapter\",\"63686170746572\",\"http://localhost/\",200]"), script(broker, """
                    var url='data:;base64,Y2hhcHRlcg==,{"type":"fixture"}';
                    var r=java.connect(url);
                    [java.hexDecodeToString(java.ajax(url)),r.body(),r.url(),r.raw().code()]
                """))
            }
        }
    }

    @Test fun webViewUserAgentIsAvailableToHeaderScriptsWithoutOpeningABrowser(): Unit = runBlocking {
        val browser = object : BrowserExecutor {
            override suspend fun defaultUserAgent() = "DeviceWebView/1.0/"
            override suspend fun execute(session: SourceSession, request: BrokerRequest, options: BrowserOptions,
                guard: RequestCommitGuard, route: SourceNetworkRoute): BrokerResult = error("UA lookup must not navigate")
        }
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath(), browser = browser).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { broker ->
                    assertEquals(ExecutionResult.Success("[\"DeviceWebView/1.0/\",\"DeviceWebView/1.0/\"]"),
                        script(broker, "[java.getUserAgent(),java.getWebViewUA()]"))
                    server.enqueue(MockResponse().setBody("chapter"))
                    val task = ExecutionTask.Rule("@js:java.ajax(baseUrl)", hnovel.rules.RuleValue.Empty,
                        baseUrl = base, sourceHeaderRule = """@js:JSON.stringify({
                            'User-Agent':String(java.getWebViewUA()).replace(/\/$/,'')+'/JINJIANG-Android',versiontype:'reading'})""")
                    val wire = ExecutionWire.encode(id, task, broker.limits)
                    val result = ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8),
                        HostBridge { name, args -> runBlocking { broker.call(name, args) } }).toByteArray())
                    assertTrue(result.toString(), result is ExecutionResult.Success)
                    val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                    assertEquals("DeviceWebView/1.0/JINJIANG-Android", request.getHeader("User-Agent"))
                    assertEquals("reading", request.getHeader("versiontype"))
                    assertFalse(broker.interactionRequired)
                    assertThrows(IllegalArgumentException::class.java) {
                        runBlocking { broker.call("java.getWebViewUA", listOf(JsonPrimitive("unexpected"))) }
                    }
                }
            }
        }
    }
    private fun script(broker: SourceExecutionBroker, code: String): ExecutionResult {
        val wire = ExecutionWire.encode(id, ExecutionTask.Script(code), broker.limits)
        return ExecutionWire.decodeResult(WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8), HostBridge { name, args ->
            val reply = runBlocking { broker.call(name, args) }.toString().toByteArray(Charsets.UTF_8)
            Json.parseToJsonElement(BridgeWire.readReply(reply.inputStream()))
        }).toByteArray())
    }

    @Test fun largeBatchInputKeepsItsSmallRuleOutputAllowance(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                val limits = ExecutionLimits(timeoutMillis = 15000, maxOutputBytes = 1024, maxDataBytes = 4 * 1024 * 1024)
                SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) = MockResponse().setBody("x".repeat(700000) + request.path!!.last())
                    }
                    assertEquals(ExecutionResult.Success("[\"0\",\"1\",\"2\"]"), script(broker,
                        "java.ajaxAll(['/0','/1','/2']).map(r=>r.body().slice(-1))"))
                    assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit), script(broker, "java.ajax(baseUrl)"))
                }
            }
        }
    }

    @Test fun multiBookHtmlBatchCrossesThePipeWithoutGrowingTheFinalOutputBudget() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("文".repeat(200000) + request.path)
            }
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                val limits = ExecutionLimits(timeoutMillis = 30000, maxRequests = 48, maxOutputBytes = 1024, maxDataBytes = 32 * 1024 * 1024)
                SourceExecutionBroker(id, authority, session, limits, base).use { broker ->
                    val paths = JsonArray((0 until 40).map { JsonPrimitive("/$it") })
                    assertEquals(ExecutionResult.Success(paths.toString()), script(broker,
                        "java.ajaxAll($paths).map(r=>r.body().slice(200000))"))
                    assertFalse(broker.responseLimitExceeded)
                }
            }
        }
    }

    @Test fun aggregateResponseLimitIsDistinguishedFromPermissionDenialAndResets() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxDataBytes = 4096), base).use { broker ->
                    repeat(2) { server.enqueue(MockResponse().setBody("x".repeat(3000))) }
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.ajaxAll", 1, listOf(Array))), script(broker, "java.ajaxAll(['/1','/2'])"))
                    assertTrue(broker.responseLimitExceeded)
                    assertNull(broker.requestFailure)
                    server.enqueue(MockResponse().setBody("ok"))
                    assertEquals(ExecutionResult.Success("\"ok\""), script(broker, "java.ajax('/ok')"))
                    assertFalse(broker.responseLimitExceeded)
                }
            }
        }
    }

    @Test fun responsePayloadIsNotDuplicatedAndOrdinaryPagesFitThroughTheWire() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 32), base).use { broker ->
                    for (body in listOf("x".repeat(28 * 1024), "x".repeat(60000), "\u4E2D".repeat(20000), "\"\\".repeat(12000))) {
                        for (call in listOf("java.get('$base',{})", "java.connect('$base')", "java.ajaxAll(['$base'])[0]")) {
                            server.enqueue(MockResponse().setBody(body))
                            assertEquals("$call, ${body.length} chars", ExecutionResult.Success(body.length.toString()),
                                script(broker, "$call.body().length"))
                        }
                    }
                    server.enqueue(MockResponse().setBody("x".repeat(30000)))
                    server.enqueue(MockResponse().setBody("x".repeat(30000)))
                    assertEquals(ExecutionResult.Success("[30000,30000]"), script(broker,
                        "java.ajaxAll(['$base','$base']).map(function(r){return r.body().length})"))
                    for ((operation, args, field) in listOf(
                        Triple("java.get", listOf(JsonPrimitive(base), JsonObject(emptyMap())), "bytes"),
                        Triple("java.connect", listOf(JsonPrimitive(base)), "body"))) {
                        server.enqueue(MockResponse().setBody("x".repeat(60000)))
                        val snapshot = broker.call(operation, args).jsonObject
                        assertTrue(field in snapshot)
                        assertFalse((if (field == "bytes") "body" else "bytes") in snapshot)
                        assertTrue(snapshot.toString().toByteArray().size < BridgeWire.MAX_BYTES)
                    }
                }
            }
        }
    }

    @Test fun responseBodyMetadataAndBatchLimitsRemainBounded() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { broker ->
                    server.enqueue(MockResponse().setBody("x".repeat(hnovel.rhino.ScriptLimits.DEFAULT_BRIDGE_CHARS + 1)))
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.get", 2, listOf(String, Object))), script(broker, "java.get('$base',{})"))
                    server.enqueue(MockResponse().setBody("x".repeat(20000)).addHeader("X-Large", "y".repeat(50000)))
                    assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit, hostCall = ScriptHostCall("java.get", 2, listOf(String, Object))), script(broker, "java.get('$base',{})"))
                    repeat(2) { server.enqueue(MockResponse().setBody("x".repeat(35000))) }
                    assertEquals(ExecutionResult.Failure(FailureCode.OutputLimit, hostCall = ScriptHostCall("java.ajaxAll", 1, listOf(Array))), script(broker, "java.ajaxAll(['$base','$base'])"))
                    server.enqueue(MockResponse().setBody("next"))
                    assertEquals(ExecutionResult.Success("\"next\""), script(broker, "java.connect('$base').body()"))
                }
            }
        }
    }

    @Test fun bomBytesAndDeclaredCharsetSurviveBrokerWireBeforeWorkerParsing() = runBlocking {
        val html = "<p>caf\u00e9 \u4E2D</p>"
        val encodings = listOf(
            "UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            "UTF-32LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0),
            "UTF-32BE" to byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()))
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { broker ->
                    for ((encoding, bom) in encodings) for (declared in listOf(null, encoding)) {
                        val bytes = bom + html.toByteArray(charset(encoding))
                        server.enqueue(MockResponse().setHeader("Content-Type", "text/html" +
                            (declared?.let { "; charset=$it" } ?: "")).setBody(okio.Buffer().write(bytes)))
                        val result = script(broker, """
                            var r=java.get('$base',{}),before=r.charset(),body=r.body();
                            [before,body,r.parse().select('p').text(),r.bodyAsBytes()]
                        """)
                        assertTrue("$encoding / $declared: $result", result is ExecutionResult.Success)
                        val expected = buildJsonArray {
                            add(declared?.let(::JsonPrimitive) ?: JsonNull)
                            add(bytes.toString(charset(declared ?: "UTF-8")))
                            add("caf\u00e9 \u4E2D")
                            add(JsonArray(bytes.map { JsonPrimitive(it.toInt()) }))
                        }
                        assertEquals("$encoding / $declared", expected, Json.parseToJsonElement((result as ExecutionResult.Success).output))
                    }
                }
            }
        }
    }

    @Test fun connectFollowsRedirectsButGetHeadPostExposeTheOriginalResponse() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { broker ->
                    server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
                    server.enqueue(MockResponse().setBody("chapter").addHeader("X-Test", "one").addHeader("X-Test", "two"))
                    assertEquals(ExecutionResult.Success("[200,\"chapter\",\"two\"]"), script(broker,
                        "var r=java.connect('/start','{\"X-Request\":\"yes\"}');[r.code(),r.body(),r.headers().get('x-test')]"))
                    assertEquals("yes", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("X-Request"))
                    assertEquals("/final", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                    for (method in listOf("get", "head", "post")) {
                        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://not-granted.invalid/"))
                        val extra = if (method == "post") "'raw=value'," else ""
                        assertEquals(ExecutionResult.Success("[302,\"https://not-granted.invalid/\"]"), script(broker,
                            "var r=java.$method('$base',${extra}{});[r.statusCode(),r.header('Location')]"))
                        val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                        assertEquals(method.uppercase(), request.method)
                        if (method == "post") assertEquals("raw=value", request.body.readUtf8())
                    }
                    assertEquals(5, server.requestCount)
                }
            }
        }
    }

    @Test fun parallelBatchPreservesInputOrderAndReservesItsWholeBudget() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(request.path!!)
                    .setBodyDelay(if (request.path == "/first") 150 else 0, TimeUnit.MILLISECONDS)
            }
            server.start()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1), base).use { broker ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.ajaxAll", 1, listOf(Array))), script(broker, "java.ajaxAll(['/first','/second'])"))
                }
                assertEquals(0, server.requestCount)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 2), base).use { broker ->
                    assertEquals(ExecutionResult.Success("[\"/first\",\"/second\"]"), script(broker,
                        "java.ajaxAll(['/first','/second']).map(function(r){return r.body()})"))
                }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun cacheHitsRespectPerRequestByteLimitsAndRedirectMode() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(folder.root.toPath()).use { sessions ->
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val request = BrokerRequest("cache", server.url("/small").toString(), cache = CacheMode.ReadThrough)
                server.enqueue(MockResponse().setBody("123456"))
                assertTrue(session.execute(request) is BrokerResult.Success)
                assertEquals(BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.ResponseTooLarge),
                    session.execute(request.copy(cache = CacheMode.Only, maxResponseBytes = 3)))
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
                server.enqueue(MockResponse().setBody("final"))
                val redirect = request.copy(url = server.url("/redirect").toString())
                assertTrue(session.execute(redirect) is BrokerResult.Success)
                assertEquals(BrokerResult.Failure(RequestStage.Response, hnovel.network.FailureCode.CacheMiss),
                    session.execute(redirect.copy(cache = CacheMode.Only, followRedirects = false)))
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun batchCancellationReleasesNetworkPermitsAndOversizedResponsesAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            SourceBroker(folder.root.toPath(), limits = BrokerLimits(concurrency = 1)).use { sessions ->
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                val broker = SourceExecutionBroker(id, authority, session, ExecutionLimits(), base)
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val pending = async { runCatching { broker.call("java.ajaxAll", listOf(JsonArray(listOf(JsonPrimitive("/one"), JsonPrimitive("/two"))))) } }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                broker.close()
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                server.enqueue(MockResponse().setBody("next"))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { next ->
                    assertEquals(JsonPrimitive("next"), withTimeout(3000) { next.call("java.ajax", listOf(JsonPrimitive("/next"))) })
                    server.enqueue(MockResponse().setBody("x".repeat(BridgeWire.MAX_BYTES + 1)))
                    assertTrue(runCatching { next.call("java.connect", listOf(JsonPrimitive("/large"))) }.isFailure)
                }
            }
        }
    }
}
