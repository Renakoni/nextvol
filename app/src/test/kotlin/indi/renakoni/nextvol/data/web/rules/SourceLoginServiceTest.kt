package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.ContentError
import hnovel.content.RuleSourceFixture
import hnovel.content.SourceContentException
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.data.web.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceLoginServiceTest {
    @Test fun nativePanelAndVerificationKeepTheirAccountWhileExplicitReloginResetsIt() = runBlocking {
        val root = Files.createTempDirectory("native-login").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        val visited = mutableListOf<String>()
        val browser = BrowserExecutor { _, request, options, _, _ ->
            assertTrue(options.interactive); visited += request.url
            BrokerResult.Success(BrokerResponse(0, request.url, emptyMap(), "<p>verified</p>".toByteArray(), "UTF-8", 0,
                protocol = "", kind = ResponseKind.BrowserDocument))
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner, browser = browser)
            val login = SourceLoginService(sources, accounts)
            try {
                val raw = JsonObject(fixture.raw() + mapOf("browserRead" to JsonPrimitive(true),
                    "loginUrl" to JsonPrimitive(fixture.server.url("/login").toString())))
                val saved = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val original = sources.loginTarget(id)
                val url = fixture.server.url("/protected?p=3").toString()
                original.session.write(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL, url))
                val first = login.begin(id)
                assertEquals(original.generation, first.generation)
                login.cancel(first)
                assertFalse(original.session.closed)
                val second = login.begin(id)
                login.submit(second, emptyMap())
                assertEquals(listOf(url), visited)
                assertEquals(StorageResult.Value(null), original.session.read(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL)))
                original.session.setCookie(fixture.server.url("/").toString(), "old=account")
                val panel = login.begin(id)
                assertEquals(original.generation, panel.generation)
                login.cancel(panel)
                assertEquals("old=account", original.session.cookie(fixture.server.url("/").toString()))
                assertTrue(runCatching { login.submit(panel, emptyMap()) }.isFailure)
                val fresh = login.begin(id, LoginIntent.Relogin)
                assertNotEquals(original.generation, fresh.generation)
                assertTrue(original.session.closed)
                assertEquals("", sources.loginTarget(id).session.cookie(fixture.server.url("/").toString()))
                assertTrue(runCatching { login.submit(second, emptyMap()) }.isFailure)
                login.submit(fresh, emptyMap())
                assertEquals(listOf(url, fixture.server.url("/login").toString()), visited)
                login.logout(id)
                assertTrue(original.session.closed)
                assertNotEquals(original.generation, sources.loginTarget(id).generation)
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }

    @Test fun cancellingAnOperationPanelRetiresOnlyItsTicketAndPreservesSavedAccount() = runBlocking {
        val root = Files.createTempDirectory("panel-account").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            var holdSubmission = false
            val entered = CompletableDeferred<hnovel.execution.SourceExecutionBroker>()
            val runner = hnovel.content.RuleTaskRunner { identity, task, limits, bridge ->
                if (holdSubmission) { entered.complete(bridge); awaitCancellation() }
                fixture.runner.execute(identity, task, limits, bridge)
            }
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, runner)
            val login = SourceLoginService(sources, accounts)
            try {
                val raw = JsonObject(fixture.raw() + mapOf(
                    "loginUrl" to JsonPrimitive("function login(){}"),
                    "loginUi" to JsonPrimitive("""[{"name":"user"},{"name":"preference","type":"button","action":"source.put('theme','dark')"}]""")
                ))
                val saved = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val original = sources.loginTarget(id)
                val info = """{"user":"alice"}"""
                original.session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, info))
                original.session.write(StorageRequest(StorageArea.Account, "login/status", "session"))
                original.session.setCookie(fixture.server.url("/").toString(), "sid=alice")
                val panel = login.begin(id)
                assertEquals(mapOf("user" to "alice"), login.form(panel).values)
                login.submit(panel, mapOf("user" to "alice"), "preference")
                assertEquals(LoginStatus.SessionSaved, login.status(id))
                login.cancel(panel)
                assertEquals(original.generation, accounts.current(id).generation)
                assertEquals(StorageResult.Value(info), original.session.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO)))
                assertEquals("sid=alice", original.session.cookie(fixture.server.url("/").toString()))
                assertEquals(StorageResult.Value("dark"), original.session.read(StorageRequest(StorageArea.Config, "value:theme")))
                assertTrue(runCatching { login.submit(panel, mapOf("user" to "late")) }.isFailure)
                assertFalse(original.session.closed)
                val next = login.begin(id)
                assertEquals("alice", login.form(next).values["user"])
                holdSubmission = true
                val running = async { runCatching { login.submit(next, mapOf("user" to "alice")) } }
                val delayed = withTimeout(10000) { entered.await() }
                login.cancel(next)
                assertTrue(withTimeout(10000) { running.await() }.isFailure)
                assertTrue(runCatching { delayed.call("source.putLoginInfo", listOf(JsonPrimitive("""{"user":"late"}"""))) }.isFailure)
                assertEquals(original.generation, accounts.current(id).generation)
                holdSubmission = false
                val outdated = login.begin(id)
                val definition = sources.installedSources().single().definition
                val revision = JsonObject(raw + ("bookSourceName" to JsonPrimitive("Revised panel")))
                val updated = sources.importer.commit(sources.importer.preview(revision.toString()),
                    listOf(ImportSelection(0, ImportDecision.Replace(definition.reference()))))
                SourceRevisionUpdates(context, sources, accounts, runner, fixture.authority).apply(id,
                    updated.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                assertTrue(runCatching { login.form(outdated) }.isFailure)
                assertTrue(runCatching { login.submit(outdated, mapOf("user" to "late")) }.isFailure)
                val beforeLogout = login.begin(id)
                login.logout(id)
                assertTrue(runCatching { login.submit(beforeLogout, mapOf("user" to "late")) }.isFailure)
                assertEquals(LoginStatus.LoggedOut, login.status(id))
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }

    @Test fun browserLoginNetworkFailurePreservesStatusWhileHttpAuthenticationFailureRequiresLogin() = runBlocking {
        val root = Files.createTempDirectory("browser-login-status").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        val browser = BrowserExecutor { session, request, options, guard, _ ->
            assertTrue(options.interactive)
            session.execute(request.copy(browser = null), guard)
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner, browser = browser)
            val login = SourceLoginService(sources, accounts)
            try {
                val raw = JsonObject(fixture.raw() +
                    ("loginUrl" to JsonPrimitive(fixture.server.url("/book/one").toString())))
                val saved = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
                assertNull(saved.error)
                val id = sources.activate(saved.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val attempt = login.begin(id)
                fixture.status = 503
                val network = runCatching { login.submit(attempt, emptyMap()) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.Network, network.code)
                assertEquals(LoginStatus.LoggedOut, login.status(id))
                fixture.status = 401
                val authentication = runCatching { login.submit(attempt, emptyMap()) }.exceptionOrNull() as SourceContentException
                assertEquals(ContentError.LoginRequired, authentication.code)
                assertEquals(LoginStatus.Required, login.status(id))
                fixture.status = 200
                login.submit(attempt, emptyMap())
                assertEquals(LoginStatus.SessionSaved, login.status(id))
                fixture.status = 503
                assertEquals(ContentError.Network,
                    (runCatching { login.submit(attempt, emptyMap()) }.exceptionOrNull() as SourceContentException).code)
                assertEquals(LoginStatus.SessionSaved, login.status(id))
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }

    @Test fun twoSourcesShareAnOriginButNotAccountsAndLogoutPurgesOnlyItsOwnSecrets() = runBlocking {
        val root = Files.createTempDirectory("login-host").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        RuleSourceFixture().use { fixture ->
            val original = fixture.server.dispatcher
            val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/login") {
                        val user = request.body.readUtf8().substringAfter("user=")
                        return MockResponse().setBody("ok").addHeader("Set-Cookie", "sid=$user; Path=/; Max-Age=3600")
                    }
                    request.getHeader("X-Source")?.let { seen[it] = request.getHeader("Cookie").orEmpty() }
                    return original.dispatch(request)
                }
            }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val login = SourceLoginService(sources, accounts)
            try {
                suspend fun install(label: String): io.nightfish.lightnovelreader.api.identifier.Identifier {
                    val raw = JsonObject(fixture.raw(label) + mapOf(
                        "loginUi" to JsonPrimitive("[{\"name\":\"user\"},{\"name\":\"password\",\"type\":\"password\"}]"),
                        "loginUrl" to JsonPrimitive("""function login(){var info=source.getLoginInfoMap();
                            var response=java.post(baseUrl.substring(0,baseUrl.lastIndexOf('/')+1)+'login','user='+info.get('user'),{'Content-Type':'application/x-www-form-urlencoded'});
                            if(response.statusCode()!==200)throw new Error('login failed');
                            source.put('saved','note-'+info.get('user'));
                            cache.put('saved','cached-'+info.get('user'),3600);
                            source.putLoginHeader(JSON.stringify({'Authorization':'Bearer '+info.get('password')}));} """.trimIndent())
                    ))
                    val preview = sources.importer.preview(raw.toString())
                    assertTrue(preview.issues.toString(), preview.issues.isEmpty())
                    val result = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                    assertNull(result.error)
                    return sources.activate(result.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                }
                val a = install("A"); val b = install("B")
                val attemptA = login.begin(a); val attemptB = login.begin(b)
                login.submit(attemptA, mapOf("user" to "alice", "password" to "alice-secret"))
                login.submit(attemptB, mapOf("user" to "bob", "password" to "bob-secret"))
                for (id in listOf(a, b)) {
                    assertEquals(LoginStatus.LoginSubmitted, login.status(id))
                    assertTrue((registry.resolve(id) as SourceResolution.Ready).runtime.getBookInformation(fixture.server.url("/book/one").toString()).isOk)
                }
                assertEquals("sid=alice", seen["A"])
                assertEquals("sid=bob", seen["B"])
                val old = sources.loginTarget(a)
                val bookId = fixture.server.url("/book/one").toString()
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val record = old.session.read(StorageRequest(StorageArea.BookState, "content/book/$digest")) as StorageResult.Value
                assertNotNull(record.value)
                login.logout(a)
                assertTrue(old.session.closed)
                assertEquals(LoginStatus.LoggedOut, login.status(a))
                assertEquals(LoginStatus.LoginSubmitted, login.status(b))
                val fresh = sources.loginTarget(a).session
                assertEquals(StorageResult.Value(null), fresh.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO)))
                assertEquals(StorageResult.Value("note-alice"), fresh.read(StorageRequest(StorageArea.Config, "value:saved")))
                assertEquals(StorageResult.Value(null), fresh.read(StorageRequest(StorageArea.Cache, "value:saved")))
                assertEquals(record, fresh.read(StorageRequest(StorageArea.BookState, "content/book/$digest")))
                assertEquals(StorageResult.Value(null), fresh.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_HEADERS)))
                assertEquals("", fresh.cookie(fixture.server.url("/").toString()))
                assertTrue(runCatching { login.submit(attemptA, mapOf("user" to "alice")) }.isFailure)
                // The test cipher is plain: scanning account files proves actual old-account deletion.
                assertFalse(File(root, "rule-sources/runtime").walkTopDown().filter { it.isFile }.any { it.readText().contains("alice-secret") })
            } finally { sources.stop(); root.deleteRecursively() }
        }
    }
}
