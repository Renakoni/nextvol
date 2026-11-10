package hnovel.execution

import hnovel.network.*
import hnovel.rhino.HostBridge
import hnovel.rules.ScriptArgumentType.String
import hnovel.rules.ScriptHostCall
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ScriptExecutionTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun foregroundBudgetKeepsHttpAndRemoteLibrariesWithinNetworkLimits() = runBlocking {
        val authority = ExecutionAuthority()
        val requests = mutableListOf<Long>()
        val browser = BrowserExecutor { _, request, _, _, _ ->
            requests += request.timeoutMillis
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "page".toByteArray(),
                "UTF-8", 0, protocol = "", kind = ResponseKind.BrowserDocument))
        }
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath(), browser = browser).use { sessions ->
                for (budget in listOf(5000L, 300000L)) {
                    val id = authority.issue("budget-$budget", "legado", "1", "fixture")
                    val session = sessions.open(SourceScope("fixture", id.sourceId, "legado"),
                        listOf(NetworkGrant(server.url("/").toString(), true)))
                    SourceExecutionBroker(id, authority, session, ExecutionLimits(timeoutMillis = budget),
                        server.url("/").toString(), allowInteraction = true).use { bridge ->
                        server.enqueue(MockResponse().setBody("function answer(){return 7;}"))
                        val library = JsonObject(mapOf("login" to JsonPrimitive(server.url("/library.js").toString()))).toString()
                        assertEquals(listOf("function answer(){return 7;}"), bridge.loadLibrary(library))
                        server.enqueue(MockResponse().setBody("http"))
                        assertEquals(ExecutionResult.Success("\"page:http\""), runScript(id, bridge,
                            "java.startBrowserAwait('/login','Login',false).body()+':'+java.ajax('/http')"))
                    }
                }
                assertEquals(listOf(5000L, 60000L), requests)
                assertEquals(4, server.requestCount)
            }
        }
    }

    @Test fun foregroundBudgetRemainsBoundedAndDoesNotChangeDefaults() {
        assertEquals(5000L, ExecutionLimits().timeoutMillis)
        assertEquals(5000L, hnovel.rhino.ScriptLimits().timeoutMillis)
        assertEquals(300000L, ExecutionLimits(timeoutMillis = 300000).timeoutMillis)
        assertEquals(300000L, hnovel.rhino.ScriptLimits(timeoutMillis = 300000).timeoutMillis)
        for (invalid in listOf(0L, 300001L)) {
            assertThrows(IllegalArgumentException::class.java) { ExecutionLimits(timeoutMillis = invalid) }
            assertThrows(IllegalArgumentException::class.java) { hnovel.rhino.ScriptLimits(timeoutMillis = invalid) }
        }
    }

    @Test fun completedNativeLoginPersistsTheScriptSuccessBranchAcrossSessions() = runBlocking {
        val authority = ExecutionAuthority()
        val scope = SourceScope("fixture", "login", "legado")
        val browser = BrowserExecutor { session, request, options, guard, _ ->
            assertTrue(options.interactive)
            guard.commit { session.setCookie(request.url, "sid=fixture-session") }
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "signed in".toByteArray(),
                "UTF-8", 0, protocol = "", kind = ResponseKind.BrowserDocument))
        }
        MockWebServer().use { server ->
            server.start()
            val url = server.url("/login").toString()
            val grants = listOf(NetworkGrant(server.url("/").toString(), true))
            SourceBroker(directory.root.toPath(), browser = browser).use { sessions ->
                val identity = authority.issue(scope.sourceId, scope.profile, "1", scope.namespace)
                val session = sessions.open(scope, grants)
                SourceExecutionBroker(identity, authority, session, ExecutionLimits(), allowInteraction = true).use { bridge ->
                    assertEquals(ExecutionResult.Success("true"), runScript(identity, bridge, """
                        var response = java.startBrowserAwait(${JsonPrimitive(url)}, 'Login', false);
                        if (response.code() === 200) cache.put('login-ready', 'saved', 0);
                        response.code() === 200 && cookie.getCookie(${JsonPrimitive(url)}).indexOf('sid=fixture-session') >= 0
                    """.trimIndent()))
                    assertEquals(0, server.requestCount)
                }
            }
            SourceBroker(directory.root.toPath()).use { sessions ->
                val restored = sessions.open(scope, grants)
                assertEquals("sid=fixture-session", restored.cookie(url))
                assertEquals(StorageResult.Value("saved"), restored.read(StorageRequest(StorageArea.Cache, "value:login-ready")))
                assertEquals(StorageResult.Value(null), sessions.open(scope.copy(sourceId = "other"), grants)
                    .read(StorageRequest(StorageArea.Cache, "value:login-ready")))
            }
        }
    }

    @Test fun sourceVariableAliasUsesTheSameStorageAndScopeAsSetVariable() = runBlocking {
        val authority = ExecutionAuthority()
        SourceBroker(directory.root.toPath()).use { sessions ->
            val first = authority.issue("a", "legado", "1", "fixture")
            val session = sessions.open(SourceScope("fixture", "a", "legado"), emptyList())
            SourceExecutionBroker(first, authority, session, ExecutionLimits()).use { bridge ->
                assertEquals(ExecutionResult.Success("[null,\"one\",null,\"two\",null,\"\"]"), runScript(first, bridge,
                    "[source.setVariable('one'),source.getVariable(),source.putVariable('two'),source.getVariable(),source.putVariable(null),source.getVariable()]"))
                assertEquals(ExecutionResult.Success("null"), runScript(first, bridge, "source.putVariable('kept')"))
                assertEquals(StorageResult.Value("kept"), session.read(StorageRequest(StorageArea.Config, "variable")))
                assertEquals(ExecutionResult.Success("[\"\",0]"), runScript(first, bridge, "[source.bookSourceName,source.lastUpdateTime]"))
            }
            for (scope in listOf(SourceScope("fixture", "b", "legado"), SourceScope("fixture", "a", "extension"), SourceScope("other", "a", "legado"))) {
                val id = authority.issue(scope.sourceId, scope.profile, "1", scope.namespace)
                SourceExecutionBroker(id, authority, sessions.open(scope, emptyList()), ExecutionLimits()).use { bridge ->
                    assertEquals(ExecutionResult.Success("\"\""), runScript(id, bridge, "source.getVariable()"))
                    assertEquals(ExecutionResult.Success("null"), runScript(id, bridge, "source.putVariable('other')"))
                }
            }
            SourceExecutionBroker(first, authority, session, ExecutionLimits()).use { bridge ->
                assertEquals(ExecutionResult.Success("\"kept\""), runScript(first, bridge, "source.getVariable()"))
                authority.revoke(first)
                assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("source.getBookSourceName", 0, emptyList())), runScript(first, bridge, "source.bookSourceName"))
            }
        }
    }

    @Test fun imageVerificationRequiresForegroundAndReturnsOnlyNonblankInput() = runBlocking {
        val authority = ExecutionAuthority()
        var calls = 0
        var answer = " A7c "
        val browser = BrowserExecutor { _, request, options, guard, _ ->
            assertTrue(options.interactive)
            assertTrue(options.verificationCode)
            assertTrue(request.url.endsWith("/captcha.png"))
            guard.commit { calls++ }
            BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), answer.toByteArray(), "UTF-8", 0))
        }
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath(), browser = browser).use { broker ->
                val id = authority.issue("A", "legado", "1", "verification")
                val base = server.url("/").toString()
                val session = broker.open(SourceScope("verification", "A", "legado"), listOf(NetworkGrant(base, true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { bridge ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.getVerificationCode", 1, listOf(String))), runScript(id, bridge, "java.getVerificationCode('/captcha.png')"))
                    assertTrue(bridge.interactionRequired)
                    assertEquals(0, calls)
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base, allowInteraction = true).use { bridge ->
                    assertEquals(ExecutionResult.Success("\" A7c \""), runScript(id, bridge, "java.getVerificationCode('/captcha.png')"))
                    answer = " "
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.getVerificationCode", 1, listOf(String))), runScript(id, bridge, "java.getVerificationCode('/captcha.png')"))
                    assertEquals(2, calls)
                    authority.revoke(id)
                    assertTrue(runCatching { bridge.call("java.getVerificationCode", listOf(JsonPrimitive(base + "captcha.png"))) }.isFailure)
                    assertEquals(2, calls)
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun browserVerificationRequiresForegroundAndReturnsTheRequestedResponse() = runBlocking {
        val authority = ExecutionAuthority()
        val opened = mutableListOf<String>()
        val browser = BrowserExecutor { _, request, options, guard, _ ->
            assertTrue(options.interactive)
            guard.commit { opened += options.title }
            val body = if (options.title == "large") "rendered".repeat(30000) else "rendered"
            BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), body.toByteArray(), "UTF-8", 0))
        }
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath(), browser = browser).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val url = JsonPrimitive(server.url("/verify").toString())
                SourceExecutionBroker(id, authority, session, ExecutionLimits()).use { bridge ->
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.startBrowser", 2, listOf(String, String))), runScript(id, bridge, "java.startBrowser($url,'verify')"))
                    assertTrue(bridge.interactionRequired)
                    assertTrue(opened.isEmpty())
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), allowInteraction = true).use { bridge ->
                    assertEquals(ExecutionResult.Success("\"rendered\""), runScript(id, bridge,
                        "java.startBrowserAwait($url,'verify',false).body()"))
                    assertEquals(0, server.requestCount)
                    server.enqueue(MockResponse().setBody("refetched"))
                    assertEquals(ExecutionResult.Success("\"refetched\""), runScript(id, bridge,
                        "java.startBrowserAwait($url,'large').body()"))
                    assertEquals(1, server.requestCount)
                    assertEquals(listOf("verify", "large"), opened)
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1), allowInteraction = true).use { bridge ->
                    server.enqueue(MockResponse().setBody("must not refetch"))
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.startBrowserAwait", 2, listOf(String, String))), runScript(id, bridge,
                        "java.startBrowserAwait($url,'verify').body()"))
                    assertEquals(1, server.requestCount)
                    assertEquals(3, opened.size)
                }
            }
        }
    }

    @Test fun browserCallsCompileInlineOptionsBeforeOpeningAndRefetching() = runBlocking {
        val authority = ExecutionAuthority()
        val opened = mutableListOf<BrokerRequest>()
        val browser = BrowserExecutor { _, request, options, guard, _ ->
            assertTrue(options.interactive)
            guard.commit { opened += request }
            BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), "rendered".toByteArray(), "UTF-8", 0))
        }
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath(), browser = browser).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val rule = JsonPrimitive("""../verify, {"headers":{"User-Agent":"inline-agent","X-Explicit":"kept"}}""")
                session.configureSource(server.url("/").toString(), cookiesEnabled = true, browserRead = true)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), server.url("/root/").toString(), allowInteraction = true).use { bridge ->
                    assertEquals(ExecutionResult.Success("\"opened\""), runScript(id, bridge, "java.startBrowser($rule,'open');'opened'"))
                    assertEquals(ExecutionResult.Success("\"rendered\""), runScript(id, bridge, "java.startBrowserAwait($rule,'rendered',false).body()"))
                    assertEquals(0, server.requestCount)
                    server.enqueue(MockResponse().setBody("refetched"))
                    assertEquals(ExecutionResult.Success("\"refetched\""), runScript(id, bridge, """
                        var response=java.startBrowserAwait($rule,'refetch');
                        if(response.code()!==200 || response.getUrl()!==${JsonPrimitive(server.url("/verify").toString())}) throw 'StrResponse';
                        response.getBody();
                    """.trimIndent()))
                    val refetched = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("/verify", refetched.path)
                    assertEquals("inline-agent", refetched.getHeader("User-Agent"))
                    assertEquals("kept", refetched.getHeader("X-Explicit"))
                    assertEquals(1, server.requestCount)
                    assertEquals(3, opened.size)
                    opened.forEach { request ->
                        assertEquals(server.url("/verify").toString(), request.url)
                        assertEquals("inline-agent", request.headers["User-Agent"])
                        assertEquals("kept", request.headers["X-Explicit"])
                    }
                }
            }
        }
    }

    @Test fun browserOptionsDoNotBypassForegroundPermissionsOrRequestLimits() = runBlocking {
        val authority = ExecutionAuthority()
        var opened = 0
        val browser = BrowserExecutor { _, request, _, guard, _ ->
            guard.commit { opened++ }
            BrokerResult.Success(BrokerResponse(200, request.url, emptyMap(), "rendered".toByteArray(), "UTF-8", 0))
        }
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath(), browser = browser).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                val rule = JsonPrimitive("""/verify, {"headers":{"X-Explicit":"kept"}}""")
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base).use { bridge ->
                    assertEquals(FailureCode.BridgeDenied, (runScript(id, bridge, "java.startBrowser($rule,'verify')") as ExecutionResult.Failure).code)
                    assertTrue(bridge.interactionRequired)
                }
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), base, allowInteraction = true).use { bridge ->
                    for (options in listOf("""{"unknown":true}""", """{"js":"1+1"}""", """{"serverID":"remote"}""", """{"method":"TRACE"}""")) {
                        val invalid = JsonPrimitive("/verify, $options")
                        assertEquals(FailureCode.BridgeDenied, (runScript(id, bridge, "java.startBrowserAwait($invalid,'verify',false)") as ExecutionResult.Failure).code)
                    }
                    val denied = JsonPrimitive("""https://ungranted.invalid/login, {"headers":{"X-Explicit":"kept"}}""")
                    assertEquals(FailureCode.BridgeDenied, (runScript(id, bridge, "java.startBrowserAwait($denied,'verify',false)") as ExecutionResult.Failure).code)
                    assertEquals(hnovel.network.FailureCode.OriginDenied, bridge.requestFailure?.code)
                }
                val privateId = authority.issue("private", "legado", "1", "fixture")
                val privateBase = server.url("/").newBuilder().host("127.0.0.1").build().toString()
                val privateSession = sessions.open(SourceScope("fixture", "private", "legado"), listOf(NetworkGrant(privateBase)))
                SourceExecutionBroker(privateId, authority, privateSession, ExecutionLimits(), privateBase, allowInteraction = true).use { bridge ->
                    assertEquals(FailureCode.BridgeDenied, (runScript(privateId, bridge, "java.startBrowser($rule,'verify')") as ExecutionResult.Failure).code)
                    assertEquals(hnovel.network.FailureCode.AddressDenied, bridge.requestFailure?.code)
                }
                assertEquals(0, opened)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1), base, allowInteraction = true).use { bridge ->
                    assertEquals(FailureCode.BridgeDenied, (runScript(id, bridge, "java.startBrowserAwait($rule,'verify')") as ExecutionResult.Failure).code)
                    assertTrue(bridge.requestLimitExceeded)
                }
                assertEquals(1, opened)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun deeplyNestedWorkerPayloadIsRejectedBeforeHostParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgeWire.arguments(("[".repeat(10000) + "]".repeat(10000)).toByteArray())
        }
        val text = JsonArray(listOf(JsonPrimitive("[".repeat(100) + "\\\"}"), JsonPrimitive(7)))
        assertEquals(text, JsonArray(BridgeWire.arguments(text.toString().toByteArray())))
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionWire.decodeResult(("{\"output\":" + "[".repeat(10000) + "]".repeat(10000) + "}").toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { ExecutionWire.decodeResult(ByteArray(BridgeWire.MAX_BYTES + 1)) }
        val result = ExecutionResult.Success("[".repeat(100) + "\\\"}")
        assertEquals(result, ExecutionWire.decodeResult(ExecutionWire.encodeResult(result)))
    }

    @Test(timeout = 10000) fun realChildWorkerRunsRhinoAndTerminatesUnboundedScript() {
        val authority = ExecutionAuthority()
        val id = authority.issue("source-a", "legado", "1")
        val worker = IsolatedExecutor(authority = authority)
        assertEquals(ExecutionResult.Success("[\"source-a\",42]"), worker.execute(id,
            ExecutionTask.Script("[source.id,result*2]", JsonPrimitive(21))))
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), worker.execute(id, ExecutionTask.Script("while(true){}")))
        assertEquals(ExecutionResult.Success("1"), worker.execute(id, ExecutionTask.Script("1")))
    }

    @Test(timeout = 40000) fun childWorkerWaitConsumesItsDeadlineAndNextExecutionRecovers() {
        val authority = ExecutionAuthority()
        val id = authority.issue("source-wait", "legado", "1")
        val worker = IsolatedExecutor(authority = authority)
        assertEquals(ExecutionResult.Success("true"), worker.execute(id, ExecutionTask.Script("""
            var start = Packages.java.lang.System.currentTimeMillis();
            Packages.java.lang.Thread.sleep(30);
            Packages.java.lang.System.currentTimeMillis() - start >= 25;
        """.trimIndent()), ExecutionLimits(timeoutMillis = 15000)))
        assertEquals(ExecutionResult.Failure(FailureCode.Timeout), worker.execute(id,
            ExecutionTask.Script("try { Packages.java.lang.Thread.sleep(60000) } catch(e) { 'caught' }"),
            ExecutionLimits(timeoutMillis = 2000)))
        assertEquals(ExecutionResult.Success("42"), worker.execute(id, ExecutionTask.Script("42"), ExecutionLimits(timeoutMillis = 15000)))
    }

    @Test fun scriptUsesRealBrokerForAjaxAndScopedStorage() = runBlocking<Unit> {
        val authority = ExecutionAuthority()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                SourceExecutionBroker(id, authority, session, ExecutionLimits(), server.url("/").toString()).use { bridge ->
                    server.enqueue(MockResponse().setBody("chapter text"))
                    val result = runScript(id, bridge, "source.put('chapter',java.ajax('/chapter')); source.get('chapter')")
                    assertEquals(ExecutionResult.Success("\"chapter text\""), result)
                    assertEquals("/chapter", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.ajax", 1, listOf(String))), runScript(id, bridge, "java.ajax('https://not-granted.invalid/')"))
                }
                val b = authority.issue("b", "legado", "1", "fixture")
                val other = sessions.open(SourceScope("fixture", "b", "legado"), emptyList())
                SourceExecutionBroker(b, authority, other, ExecutionLimits()).use { bridge ->
                    assertEquals(ExecutionResult.Success("\"\""), runScript(b, bridge, "source.get('chapter')"))
                }
                assertThrows(IllegalArgumentException::class.java) { SourceExecutionBroker(id, authority, other, ExecutionLimits()) }
            }
        }
    }

    @Test fun revokedAndExhaustedTicketsCannotCommitStorage() = runBlocking {
        val authority = ExecutionAuthority()
        SourceBroker(directory.root.toPath()).use { sessions ->
            val id = authority.issue("a", "legado", "1", "fixture")
            val session = sessions.open(SourceScope("fixture", "a", "legado"), emptyList())
            SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1)).use { bridge ->
                bridge.call("source.put", listOf(JsonPrimitive("key"), JsonPrimitive("before")))
                assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("source.put", 2, listOf(String, String))), runScript(id, bridge, "source.put('key','exhausted')"))
                authority.revoke(id)
                assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("source.put", 2, listOf(String, String))), runScript(id, bridge, "source.put('key','revoked')"))
                assertEquals(StorageResult.Value("before"), session.read(StorageRequest(StorageArea.Config, "value:key")))
            }
        }
    }

    @Test fun repeatedSourceIdentityReadsLeaveTheRequestBudgetForIoAndRespectRevocation() = runBlocking {
        val authority = ExecutionAuthority()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val base = server.url("/").toString()
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(base, true)))
                session.configureSource(base, true, false)
                SourceExecutionBroker(id, authority, session, ExecutionLimits(maxRequests = 1), base).use { bridge ->
                    server.enqueue(MockResponse().setBody("chapter"))
                    assertEquals(ExecutionResult.Success("\"chapter\""), runScript(id, bridge, """
                        for(var i=0;i<128;i++) {
                            if(source.getKey()!==${JsonPrimitive(base)} || source.key!==source.bookSourceUrl) throw new Error('identity');
                        }
                        java.ajax('/chapter')
                    """.trimIndent()))
                    assertEquals(1, server.requestCount)
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("java.ajax", 1, listOf(String))), runScript(id, bridge, "java.ajax('/second')"))
                    assertTrue(bridge.requestLimitExceeded)
                    assertEquals(1, server.requestCount)
                    authority.revoke(id)
                    assertEquals(ExecutionResult.Failure(FailureCode.BridgeDenied, hostCall = ScriptHostCall("source.getKey", 0, emptyList())), runScript(id, bridge, "source.getKey()"))
                }
            }
        }
    }

    @Test fun revokeBeforeResponseCommitDoesNotSaveCookies() = runBlocking {
        val authority = ExecutionAuthority()
        MockWebServer().use { server ->
            server.start()
            SourceBroker(directory.root.toPath()).use { sessions ->
                val id = authority.issue("a", "legado", "1", "fixture")
                val session = sessions.open(SourceScope("fixture", "a", "legado"), listOf(NetworkGrant(server.url("/").toString(), true)))
                val bridge = SourceExecutionBroker(id, authority, session, ExecutionLimits(), server.url("/").toString())
                server.enqueue(MockResponse().setBody("late").addHeader("Set-Cookie", "auth=late; Path=/").setBodyDelay(500, TimeUnit.MILLISECONDS))
                val pending = async(Dispatchers.IO) { runCatching { bridge.call("java.ajax", listOf(JsonPrimitive("/login"))) } }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
                authority.revoke(id)
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                bridge.close()
                server.enqueue(MockResponse().setBody("next"))
                session.execute(BrokerRequest("next", server.url("/next").toString()))
                assertNull(server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
            }
        }
    }

    private fun runScript(id: ExecutionIdentity, bridge: SourceExecutionBroker, script: String): ExecutionResult {
        val wire = ExecutionWire.encode(id, ExecutionTask.Script(script), bridge.limits)
        val output = WorkerMain.executeSerialized(wire.toString(Charsets.UTF_8), HostBridge { name, args ->
            runBlocking { bridge.call(name, args) }.also {
                require(it.toString().toByteArray(Charsets.UTF_8).size <= BridgeWire.MAX_BYTES)
            }
        })
        return ExecutionWire.decodeResult(output.toByteArray(Charsets.UTF_8))
    }
}
