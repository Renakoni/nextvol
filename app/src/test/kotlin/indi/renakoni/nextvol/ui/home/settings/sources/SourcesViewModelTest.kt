package indi.renakoni.nextvol.ui.home.settings.sources

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.ImportDecision
import hnovel.imports.ImportSelection
import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.StorageArea
import hnovel.network.StorageRequest
import hnovel.network.StorageRequestKey
import hnovel.network.NetworkGrant
import io.mockk.*
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourcesViewModelTest {
    @Test fun permissionsAndEditorKeepDraftFailuresLocalAndOnlySaveExplicitChanges(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-editor").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val model = SourcesViewModel(context, sources, SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority),
                SourceLoginService(sources, accounts), registry, ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + ("bookSourceUrl" to JsonPrimitive("https://old.example/")))
                val reference = sources.importer.commit(sources.importer.preview(raw.toString()),
                    listOf(ImportSelection(0, ImportDecision.Add))).items.single().reference!!
                val id = sources.activate(reference, listOf(NetworkGrant("https://old.example:443")))
                model.select(id); idle()
                model.savePermissions(id, "https://new.example/")
                val denied = idle()
                assertNull(denied.message)
                assertEquals(indi.renakoni.nextvol.R.string.sources_missing_origin, denied.configurationError?.message)
                assertEquals("https://old.example:443", denied.configurationError?.detail)
                model.savePermissions(id, "https://old.example/\ninvalid")
                assertEquals("2", idle().configurationError?.detail)
                model.savePermissions(id, "https://old.example/\nhttps://new.example/")
                assertNull(idle().configurationError)
                model.select(null); idle(); model.select(id)
                assertEquals(2, idle().installed.single().origins.size)
                model.beginEditor(id)
                val original = idle().editor!!
                assertFalse(original.dirty)
                val snapshot = File(context.filesDir, "rule-sources/active.json").readBytes()
                model.editDraft("{}")
                model.validateEditor()
                val validated = idle().editor!!
                assertTrue(validated.jsonValid)
                assertEquals("{}", validated.rawJson)
                assertArrayEquals(snapshot, File(context.filesDir, "rule-sources/active.json").readBytes())
                model.editDraft("{\n  \"name\":\n}")
                assertFalse(model.state.value.editor!!.jsonValid)
                model.validateEditor()
                assertEquals(3, idle().editor!!.error?.line)
                model.editDraft("{")
                model.saveEditor()
                val failed = idle().editor!!
                assertEquals("{", failed.rawJson)
                assertEquals(indi.renakoni.nextvol.R.string.sources_editor_syntax_error, failed.error?.message)
                assertArrayEquals(snapshot, File(context.filesDir, "rule-sources/active.json").readBytes())
                model.editDraft(original.rawJson)
                model.editDraft(original.rawJson.replace("old.example", "new.example"))
                assertArrayEquals(snapshot, File(context.filesDir, "rule-sources/active.json").readBytes())
                assertTrue(model.state.value.editor!!.dirty)
                model.saveEditor()
                val saved = idle().editor!!
                assertTrue(saved.saved)
                assertFalse(saved.dirty)
                assertNull(saved.error)
                assertEquals(id, ImportedRuleSources.id(saved.installed.definition))
                assertEquals("https://new.example/", Json.parseToJsonElement(saved.rawJson).jsonObject["bookSourceUrl"]?.jsonPrimitive?.content)
                assertEquals(0, fixture.server.requestCount)
                val savedSnapshot = File(context.filesDir, "rule-sources/active.json").readBytes()
                model.editDraft(saved.rawJson.replace("new.example", "third.example"))
                model.saveEditor()
                assertEquals(listOf("https://third.example:443"), idle().editor!!.pendingOrigins)
                assertArrayEquals(savedSnapshot, File(context.filesDir, "rule-sources/active.json").readBytes())
                model.dismissEditorOrigins()
                assertTrue(model.state.value.editor!!.pendingOrigins.isEmpty())
                assertArrayEquals(savedSnapshot, File(context.filesDir, "rule-sources/active.json").readBytes())
                model.saveEditor(); idle()
                model.editDraft(saved.rawJson)
                model.approveEditorOrigins(); idle()
                assertArrayEquals(savedSnapshot, File(context.filesDir, "rule-sources/active.json").readBytes())
                model.editDraft(saved.rawJson.replace("new.example", "third.example"))
                model.saveEditor(); idle()
                model.editDraft(saved.rawJson.replace("new.example", "fourth.example"))
                model.saveEditor()
                assertEquals(listOf("https://fourth.example:443"), idle().editor!!.pendingOrigins)
                model.formatEditor(); idle()
                model.approveEditorOrigins(); idle()
                assertArrayEquals(savedSnapshot, File(context.filesDir, "rule-sources/active.json").readBytes())
                model.saveEditor(); idle()
                model.approveEditorOrigins()
                val approved = idle().editor!!
                assertTrue(approved.saved)
                assertEquals(saved.installed.origins + NetworkGrant("https://fourth.example:443"), approved.installed.origins)
                assertEquals("https://fourth.example/", Json.parseToJsonElement(approved.rawJson).jsonObject["bookSourceUrl"]?.jsonPrimitive?.content)
                assertTrue(approved.pendingOrigins.isEmpty())
                assertEquals(0, fixture.server.requestCount)
                model.closeEditor()
                assertNull(model.state.value.editor)
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

    @Test fun configurationDoesNotAuthenticateAndLoginRunsOnlyTheConventionalAction(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-configuration").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val model = SourcesViewModel(context, sources, SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority),
                SourceLoginService(sources, accounts), registry, ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + mapOf(
                    "loginUrl" to JsonPrimitive("function login(){cache.put('authenticated','yes');java.toast('Signed in');}"),
                    "loginUi" to JsonPrimitive("""@js:JSON.stringify([
                        {name:'Account',type:'button',action:'login()'},
                        {name:'Debug',type:'button',action:"cache.put('configured',String(isLongClick));java.longToast('  Debug enabled  ');"}
                    ])""")))
                model.previewText(raw.toString(), EXTENSION_PROFILE)
                val committed = sources.importer.commit(idle().preview!!, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                model.select(id); idle()
                val generation = accounts.current(id).generation
                model.beginConfiguration(id)
                val opened = idle()
                assertTrue(opened.configurationPanel)
                val form = opened.loginForm!!
                val session = sources.loginTarget(id).session
                fun stored(key: String) = (session.read(StorageRequest(StorageArea.Cache, "value:$key")) as hnovel.network.StorageResult.Value).value
                assertNull(stored("authenticated"))
                model.submitLogin(form.values, form.fields.last().id, form.id, longClick = true)
                idle()
                assertEquals("true", stored("configured"))
                model.submitLogin(form.values, form.fields.last().id, form.id)
                idle()
                assertEquals("false", stored("configured"))
                assertNull(stored("authenticated"))
                model.cancelLogin(); idle()
                model.beginLogin(id)
                val authenticated = idle()
                assertNull(authenticated.loginForm)
                assertFalse(authenticated.configurationPanel)
                assertEquals("yes", stored("authenticated"))
                assertEquals(generation, accounts.current(id).generation)
                model.select(null); idle()
                model.select(id); idle()
                model.logout(id); idle()
                assertTrue(accounts.current(id).generation > generation)
                model.beginConfiguration(id)
                assertNotNull(idle().loginForm)
                model.cancelLogin(); idle()
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

    @Test fun dynamicPanelUsesTheSavedAccountAndCancellingItsLoadKeepsTheAccount(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("dynamic-login").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val login = spyk(SourceLoginService(sources, accounts))
            val model = SourcesViewModel(context, sources, SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority), login, registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + mapOf("loginUrl" to JsonPrimitive("function login(){}"),
                    "loginUi" to JsonPrimitive("@js:var saved=source.getLoginInfoMap();JSON.stringify([{name:'user',default:saved?saved.get('user'):'new-account'}])")))
                model.previewText(raw.toString(), EXTENSION_PROFILE)
                val preview = idle().preview!!
                assertEquals(EXTENSION_PROFILE, preview.candidates.single().profile)
                val committed = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                sources.loginTarget(id).session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, "{\"user\":\"old-account\"}"))
                model.beginLogin(id)
                assertEquals("old-account", idle().loginForm!!.values["user"])
                model.cancelLogin(); idle()
                val previous = accounts.current(id).generation
                val entered = CompletableDeferred<Unit>()
                fixture.afterRun = { entered.complete(Unit); awaitCancellation() }
                model.beginLogin(id)
                withTimeout(10000) { entered.await() }
                model.cancel()
                assertNull(idle().loginForm)
                assertEquals(previous, accounts.current(id).generation)
                assertEquals(LoginStatus.LoggedOut, login.status(id))
                assertEquals(0, fixture.documents.get())
                fixture.afterRun = {}
                model.beginLogin(id); idle()
                val closed = CompletableDeferred<LoginAttempt>()
                coEvery { login.cancel(any()) } coAnswers {
                    callOriginal()
                    closed.complete(firstArg())
                    Unit
                }
                androidx.lifecycle.ViewModelStore().apply { put("sources", model); clear() }
                val retired = withTimeout(10000) { closed.await() }
                assertEquals(previous, accounts.current(id).generation)
                assertTrue(runCatching { login.form(retired) }.isFailure)
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

    @Test fun previewApprovalUpdateAndRemovalUseProductionServices(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-management").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val updates = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority)
            val login = SourceLoginService(sources, accounts)
            val model = SourcesViewModel(context, sources, updates, login, registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + ("bookSourceUrl" to JsonPrimitive("https://fixture.invalid/source")))
                model.previewText(raw.toString()); assertEquals(1, idle().preview!!.candidates.size)
                assertTrue(sources.installedSources().isEmpty())
                val permissions = mapOf(0 to "https://fixture.invalid/")
                model.commit(setOf(0), permissions, false)
                model.commit(setOf(0), permissions, false)
                val first = idle().installed.single()
                assertEquals(1, registry.sources.value.size)
                val id = ImportedRuleSources.id(first.definition)
                model.previewText(JsonObject(raw + ("bookSourceName" to JsonPrimitive("New name"))).toString()); idle()
                model.commit(setOf(0), permissions, false)
                val updated = idle().installed.single()
                assertEquals(id, ImportedRuleSources.id(updated.definition))
                assertEquals("New name", updated.definition.displayName)
                model.saveVariable(id, "saved after update")
                assertEquals(indi.renakoni.nextvol.R.string.sources_saved, idle().message)
                model.select(id)
                assertEquals("saved after update", idle().variable)
                model.checkUpdate(id)
                assertEquals(indi.renakoni.nextvol.R.string.sources_no_update_url, idle().message)
                model.remove(id)
                assertTrue(idle().installed.isEmpty())
                assertTrue(registry.sources.value.isEmpty())
                val secondRaw = JsonObject(raw + ("bookSourceUrl" to JsonPrimitive("https://fixture.invalid/second")))
                val batchPermissions = mapOf(0 to "https://fixture.invalid/", 1 to "https://fixture.invalid/")
                model.previewText(JsonArray(listOf(raw, secondRaw)).toString()); idle()
                model.commit(setOf(0, 1), batchPermissions, false)
                val installed = idle().installed
                assertEquals(2, installed.size)
                val second = installed.single { it.definition.importKey != first.definition.importKey }
                val secondId = ImportedRuleSources.id(second.definition)
                val changed = JsonObject(raw + ("bookSourceName" to JsonPrimitive("Batch success")))
                val broken = JsonObject(secondRaw + ("jsLib" to JsonPrimitive("function broken(")))
                model.previewText(JsonArray(listOf(changed, broken)).toString()); idle()
                model.commit(setOf(0, 1), batchPermissions, false)
                val partial = idle()
                assertEquals(indi.renakoni.nextvol.R.string.sources_import_partial, partial.message)
                assertNull(partial.preview)
                assertTrue(partial.installed.any { it.definition.displayName == "Batch success" })
                assertEquals(second.definition, partial.installed.single { ImportedRuleSources.id(it.definition) == secondId }.definition)
                model.saveVariable(secondId, "kept after rejected candidate")
                assertEquals(indi.renakoni.nextvol.R.string.sources_saved, idle().message)

                val applied = JsonObject(raw + ("bookSourceName" to JsonPrimitive("Applied before cancellation")))
                val pending = JsonObject(secondRaw + ("bookSourceName" to JsonPrimitive("Cancelled")))
                model.previewText(JsonArray(listOf(applied, pending)).toString()); idle()
                val entered = CompletableDeferred<Unit>()
                fixture.afterRun = { task ->
                    if (task is hnovel.execution.ExecutionTask.Rule && task.baseUrl == "https://fixture.invalid/second") {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                model.commit(setOf(0, 1), batchPermissions, false)
                withTimeout(10000) { entered.await() }
                assertEquals(setOf("Applied before cancellation", "Cancelled"), sources.definitions.list().map { it.displayName }.toSet())
                model.cancel()
                val cancelled = idle()
                assertNull(cancelled.preview)
                assertTrue(cancelled.previewOrigins.isEmpty())
                assertNull(cancelled.message)
                assertEquals("Applied before cancellation", cancelled.installed.single { ImportedRuleSources.id(it.definition) == id }.definition.displayName)
                assertEquals(second.definition, cancelled.installed.single { ImportedRuleSources.id(it.definition) == secondId }.definition)
            } finally { model.cancel(); sources.stop(); root.deleteRecursively(); Dispatchers.resetMain() }
        }
    }

    @Test fun websiteVerificationUsesItsSavedAccountWithoutALoginDeclaration(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("settings-verification").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val coordinator = SourceVerificationCoordinator(registry)
            var failureCode: hnovel.network.FailureCode? = hnovel.network.FailureCode.Dns
            var waitForCancellation = false
            var interactiveCalls = 0
            val opened = CompletableDeferred<Unit>()
            val browser = hnovel.network.BrowserExecutor { _, request, options, _, _ ->
                if (!options.interactive) hnovel.network.BrokerResult.Failure(hnovel.network.RequestStage.Response,
                    hnovel.network.FailureCode.BrowserRequired, challenge = hnovel.network.BrowserChallengeKind.SiteVerification,
                    verificationRequest = request)
                else {
                    interactiveCalls++
                    if (waitForCancellation) { opened.complete(Unit); awaitCancellation() }
                    failureCode?.let { hnovel.network.BrokerResult.Failure(hnovel.network.RequestStage.Connect, it) }
                        ?: hnovel.network.BrokerResult.Success(hnovel.network.BrokerResponse(0, request.url, emptyMap(),
                            "ready".toByteArray(), "UTF-8", 0, kind = hnovel.network.ResponseKind.BrowserDocument))
                }
            }
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner,
                browser = browser, verification = coordinator)
            val raw = JsonObject(fixture.raw() + ("browserRead" to JsonPrimitive(true)))
            val committed = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
            val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val target = sources.loginTarget(id)
            target.session.write(StorageRequest(StorageArea.Account, "login/status", "authenticated"))
            val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
            val model = SourcesViewModel(context, sources, SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority),
                SourceLoginService(sources, accounts), registry, ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain),
                verification = coordinator)
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            suspend fun challenge() {
                assertTrue(runtime.getBookInformation(fixture.server.url("/book/one").toString()).isErr)
                withTimeout(10000) { model.state.first { it.verification != null } }
                assertEquals(target.generation, model.state.value.verification!!.owner.generation)
            }
            try {
                idle(); model.select(id); idle()
                assertFalse(model.state.value.ruleSettings!!.loginDeclared)
                challenge()
                model.verifyPending()
                assertEquals(indi.renakoni.nextvol.R.string.sources_dns_failed, idle().message)
                assertEquals(LoginStatus.LoginSubmitted, idle().loginStatus)
                assertEquals(target.generation, accounts.current(id).generation)
                assertNull(idle().loginForm)
                failureCode = null
                challenge()
                // Completing through the global host also refreshes an already-open settings page.
                coordinator.verifyBackground(coordinator.prompts.value.single().id)
                withTimeout(10000) { model.state.first { it.loginStatus == LoginStatus.SessionSaved } }
                assertEquals(target.generation, accounts.current(id).generation)
                waitForCancellation = true
                challenge(); model.verifyPending()
                withTimeout(10000) { opened.await() }
                model.cancel()
                assertEquals(LoginStatus.SessionSaved, idle().loginStatus)
                assertTrue(coordinator.prompts.value.isEmpty())
                assertEquals(target.generation, accounts.current(id).generation)
                challenge()
                val retired = coordinator.prompts.value.single()
                sources.rotateAccount(id)
                withTimeout(10000) { model.state.first { it.verification == null } }
                model.verifyPending(); idle()
                assertEquals(3, interactiveCalls)
                assertTrue(runCatching { coordinator.verifyBackground(retired.id) }.isFailure)
                assertEquals(3, interactiveCalls)
                assertEquals(0, fixture.server.requestCount)
            } finally { model.cancel(); sources.stop(); root.deleteRecursively(); Dispatchers.resetMain() }
        }
    }

}
