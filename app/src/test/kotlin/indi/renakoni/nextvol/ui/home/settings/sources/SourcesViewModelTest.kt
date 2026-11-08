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
import hnovel.network.SourceNetworkMode
import io.mockk.*
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
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
                model.formatEditor(); idle()
                model.saveEditor(); idle()
                model.approveEditorOrigins()
                val approved = idle().editor!!
                assertTrue(approved.saved)
                assertEquals(saved.installed.origins + NetworkGrant("https://third.example:443"), approved.installed.origins)
                assertTrue(approved.pendingOrigins.isEmpty())
                assertEquals(0, fixture.server.requestCount)
                model.closeEditor()
                assertNull(model.state.value.editor)
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

    @Test fun bundledPixivKeepsAccountAndUpdateActionsWithSectionedSettingsAndFeedback(): Unit = runBlocking {
        val original = RuntimeEnvironment.getApplication().assets.open("source-catalog/Adult.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonArray.map { entry -> entry.jsonObject }
                .single { entry -> entry["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.pixiv.net/novel" }
        }
        RuleSourceFixture().use { fixture ->
            fixture.source(profile = EXTENSION_PROFILE) {
                Json.parseToJsonElement(original.toString().replace("https://www.pixiv.net", fixture.server.url("/").toString().removeSuffix("/"))).jsonObject
            }.use { source ->
                val collapsed = source.loginForm()
                val login = SourceLoginService.directLoginAction(collapsed)
                assertNotNull(login)
                assertTrue(collapsed.fields.none { it.action == "startGithubIssue()" })
                assertTrue(collapsed.fields.any { it.action == "updateSource()" })
                assertEquals(listOf("阅读与搜索", "发现页设置"), collapsed.fields.mapNotNull { it.section }.distinct())
                assertNull(collapsed.fields.single { it.action == "pixivBlockManager()" }.section)
                assertFalse(collapsed.fields.single { it.action == "startPixivSettings()" }.enabled)
                val expanded = collapsed
                val fast = expanded.fields.single { it.action == "editSettings('FAST')" }
                val feedback = source.login(expanded.values, fast.id, expanded.id).messages
                assertEquals(1, feedback.size)
                assertTrue(feedback.single().contains("快速模式"))
                assertTrue(feedback.single().startsWith("✅ 已开启"))
                assertEquals(login, SourceLoginService.directLoginAction(source.loginForm()))
                assertEquals(0, fixture.server.requestCount)
            }
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

    @Test fun catalogConfirmationAssignsEachSelectedSourceToItsOwnCategory(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("catalog-groups").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val model = SourcesViewModel(context, sources,
                SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority),
                SourceLoginService(sources, accounts), registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                val catalog = idle().catalog
                val chosen = catalog.filter { it.category == SourceCategory.Literature }.take(2) +
                    catalog.first { it.category == SourceCategory.Female }
                model.previewCatalog(chosen.map { it.key }.toSet())
                val preview = idle()
                assertTrue(preview.catalogPreview)
                assertTrue(sources.sourceGroups().isEmpty())
                model.commit(preview.preview!!.candidates.map { it.index }.toSet(), preview.previewOrigins, false)
                val saved = idle()
                assertNull(saved.preview)
                assertEquals(3, saved.installed.size)
                assertEquals(2, saved.groups.size)
                saved.installed.forEach { source ->
                    val category = chosen.single { it.key == source.definition.importKey }.category
                    assertEquals(context.getString(category.title), saved.groups.single { it.id in source.preferences.groupIds }.name)
                }
                assertEquals(0, fixture.server.requestCount)
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

    @Test fun defaultImportDraftsActivateNovelsAndInvalidManualLinesCommitNothing(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-import-origins").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val model = SourcesViewModel(context, sources,
                SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority),
                SourceLoginService(sources, accounts), registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonArray((0..2).map { index -> JsonObject(fixture.raw() + mapOf(
                    "bookSourceUrl" to JsonPrimitive("https://fixture.invalid/$index"),
                    "bookSourceType" to JsonPrimitive(if (index == 2) 2 else 0),
                    "jsLib" to JsonPrimitive("function isImage(url) { return url.startsWith('https://210.140'); }")
                )) })
                model.previewText(raw.toString())
                val preview = idle()
                assertEquals(listOf(0, 1), preview.preview!!.candidates.map { it.index })
                assertEquals(hnovel.imports.ImportCode.UnsupportedType, preview.preview.issues.single { it.index == 2 }.code)
                assertTrue(preview.previewOrigins.values.all { SourcesViewModel.invalidPermissionLines(it).isEmpty() })
                assertTrue(sources.definitions.list().isEmpty())
                assertTrue(registry.sources.value.isEmpty())
                val invalid = preview.previewOrigins + (1 to "https://fixture.invalid/\n\nhttps://210.140")
                assertEquals(listOf(3), SourcesViewModel.invalidPermissionLines(invalid.getValue(1)))
                model.commit(setOf(0, 1), invalid, false)
                val rejected = idle()
                assertNotNull(rejected.preview)
                assertNotNull(rejected.message)
                model.consumeSavedMessage()
                assertEquals(rejected.message, model.state.value.message)
                assertTrue(sources.definitions.list().isEmpty())
                assertTrue(sources.installedSources().isEmpty())
                assertTrue(registry.sources.value.isEmpty())
                model.commit(setOf(0, 1), preview.previewOrigins, false)
                assertNull(idle().preview)
                assertEquals(indi.renakoni.nextvol.R.string.sources_saved, idle().message)
                model.consumeSavedMessage()
                assertNull(model.state.value.message)
                assertEquals(2, idle().installed.size)
                assertTrue(idle().installed.all { it.preferences.enabled })
                assertEquals(2, registry.sources.value.size)
                idle().installed.forEach { assertTrue(registry.resolve(ImportedRuleSources.id(it.definition)) is SourceResolution.Ready) }
                assertEquals(0, fixture.server.requestCount)
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }

    @Test fun settingsReadStoredValuesBeforeExplicitLoginAttemptsInitialization(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-initialization-ui").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        val definition = hnovel.imports.SourceDefinition("broken", "legado", "fixture", "https://fixture.invalid/", "Broken", true,
            false, hnovel.imports.ImportOrigin(hnovel.imports.ImportOrigin.Kind.Paste), "digest", 1,
            """{"loginUi":"[{\"name\":\"account\"}]"}""")
        val id = ImportedRuleSources.id(definition)
        val registry = WebSourceRegistry(hnovel.execution.ExecutionAuthority())
        val source = mockk<io.nightfish.lightnovelreader.api.web.WebBookDataSource>()
        every { source.id } returns id
        every { source.onLoad() } throws IllegalStateException("controlled initialization failure")
        val registration = registry.register(SourceMetadata(io.nightfish.lightnovelreader.api.web.WebDataSourceItem(id, "Broken", "fixture"),
            setOf(SourceCapability.Search, SourceCapability.Login))) { source }
        val sources = mockk<ImportedRuleSources>()
        coEvery { sources.sourceGroups() } returns emptyList()
        coEvery { sources.installedSources() } returns listOf(InstalledRuleSource(definition, listOf(NetworkGrant("https://fixture.invalid/"))))
        coEvery { sources.storedSettings(id, "account") } returns RuleStoredSettings("saved variable", "session", "reader")
        val login = mockk<SourceLoginService>(relaxed = true)
        val zLibrary = mockk<ZLibrarySources>()
        every { zLibrary.state } returns MutableStateFlow(indi.renakoni.nextvol.data.web.zlibrary.ZLibraryState())
        val model = SourcesViewModel(context, sources, mockk(), login, registry, zLibrary)
        suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
        try {
            idle()
            assertEquals(SourceStatus.Registered, registry.sources.value.single().status)
            model.select(id)
            val selected = idle()
            assertEquals(id, selected.selected)
            assertEquals("saved variable", selected.variable)
            assertEquals(LoginStatus.SessionSaved, selected.loginStatus)
            assertEquals("reader", selected.accountName)
            assertEquals(SourceStatus.Registered, registry.sources.value.single().status)
            verify(exactly = 0) { source.onLoad() }
            coVerify(exactly = 0) { sources.loginTarget(any()) }
            coVerify(exactly = 0) { login.status(any()) }
            model.beginLogin(id); idle()
            withTimeout(10000) { model.state.first { it.registry.singleOrNull()?.status == SourceStatus.Failed } }
            verify(exactly = 1) { source.onLoad() }
            coVerify(exactly = 0) { login.begin(any(), any()) }
            model.select(id)
            assertEquals("saved variable", idle().variable)
            assertEquals(id, idle().selected)
            verify(exactly = 1) { source.onLoad() }
        } finally { model.cancel(); registration.unregister(); Dispatchers.resetMain(); root.deleteRecursively() }
    }

    @Test fun explicitImportsAreEnabledAndPermissionsNeverEraseInactiveConfiguration(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("source-preferences-ui").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files")
            override fun getCacheDir() = File(root, "cache")
        }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val updates = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority)
            val model = SourcesViewModel(context, sources, updates, SourceLoginService(sources, accounts), registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                val raw = JsonObject(fixture.raw() + mapOf("bookSourceUrl" to JsonPrimitive("https://fixture.invalid/"),
                    "enabled" to JsonPrimitive(false)))
                model.previewText(raw.toString()); idle()
                model.commit(setOf(0), mapOf(0 to ""), false)
                val installed = idle().installed.single()
                val id = ImportedRuleSources.id(installed.definition)
                assertTrue(installed.preferences.enabled)
                assertTrue(registry.sources.value.isEmpty())
                model.select(id)
                assertEquals(id, idle().selected)
                assertNull(idle().message)
                model.setBypassVpn(true)
                assertEquals(true, idle().network?.bypassVpn)
                assertTrue(registry.sources.value.isEmpty())
                model.setEnabled(id, true); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                model.savePermissions(id, "https://fixture.invalid/"); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Ready)
                model.savePermissions(id, "invalid"); idle()
                assertNotNull(idle().configurationError)
                model.saveVariable(id, "keep this")
                idle()
                assertEquals("keep this", sources.storedSettings(id).variable)
                assertEquals(listOf("https://fixture.invalid:443"), sources.installedSources().single().origins.map { it.origin })
                assertEquals("keep this", idle().variable)
                model.setEnabled(id, false)
                assertEquals("keep this", idle().variable)
                model.saveVariable(id, "edited while disabled")
                assertEquals("edited while disabled", idle().variable)
                model.savePermissions(id, ""); idle()
                model.savePermissions(id, "https://fixture.invalid/"); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                model.setEnabled(id, true)
                assertEquals("edited while disabled", idle().variable)
                model.saveVariable(id, "saved before revocation"); idle()
                model.savePermissions(id, ""); idle()
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                model.savePermissions(id, "https://fixture.invalid/")
                assertEquals("saved before revocation", idle().variable)
                assertEquals(0, fixture.server.requestCount)
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

    @Test fun discoveryLoginSelectsTheOriginAndDoesNotRestartOnRecreation(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("discovery-settings").toFile()
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
            val raw = JsonObject(fixture.raw() + mapOf("loginUrl" to JsonPrimitive("function login(){}"),
                "loginUi" to JsonPrimitive("[{\"name\":\"user\"}]")))
            val committed = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
            val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val model = SourcesViewModel(context, sources, updates, login, registry,
                ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                model.openFromDiscovery(id, true)
                val opened = idle()
                assertEquals(id, opened.selected)
                assertEquals("user", opened.loginForm!!.fields.single().name)
                val generation = accounts.current(id).generation
                assertEquals(0L, generation)
                model.openFromDiscovery(id, true)
                idle()
                assertEquals(generation, accounts.current(id).generation)
                model.submitLogin(mapOf("user" to "reader"))
                assertEquals(LoginStatus.LoginSubmitted, idle().loginStatus)
                assertEquals("reader", idle().accountName)
                assertNull(idle().loginForm)
                model.select(null); idle()
                model.select(id)
                assertEquals("reader", idle().accountName)
                assertEquals(generation, accounts.current(id).generation)
                model.setEnabled(id, false)
                assertEquals("reader", idle().accountName)
                assertEquals(LoginStatus.LoginSubmitted, idle().loginStatus)
                model.setEnabled(id, true); idle()
                model.logout(id)
                assertEquals(LoginStatus.LoggedOut, idle().loginStatus)
                assertNull(idle().accountName)
                model.beginLogin(id); idle()
                model.submitLogin(mapOf("user" to "next-reader"))
                assertEquals("next-reader", idle().accountName)
                model.beginLogin(id)
                assertEquals(LoginStatus.LoginSubmitted, idle().loginStatus)
                assertEquals("next-reader", idle().accountName)
                model.cancelLogin(); idle()
                model.relogin(id)
                assertEquals(LoginStatus.LoggedOut, idle().loginStatus)
                assertNull(idle().accountName)
                model.cancelLogin()
                assertEquals(LoginStatus.LoggedOut, idle().loginStatus)
                assertNull(idle().accountName)
                assertEquals(0, fixture.documents.get())
            } finally { model.cancel(); sources.stop(); root.deleteRecursively(); Dispatchers.resetMain() }
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
            } finally { model.cancel(); sources.stop(); root.deleteRecursively(); Dispatchers.resetMain() }
        }
    }

    @Test fun freshBrowserLoginResetsTheOldStateBeforeReportingTheNewAttempt(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("browser-account-card").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            var httpStatus = 200
            val browser = hnovel.network.BrowserExecutor { _, request, _, _, _ ->
                hnovel.network.BrokerResult.Success(hnovel.network.BrokerResponse(httpStatus, request.url, emptyMap(),
                    byteArrayOf(), "UTF-8", 0))
            }
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner, browser = browser)
            val raw = JsonObject(fixture.raw() + mapOf("browserRead" to JsonPrimitive(true),
                "loginUrl" to JsonPrimitive(fixture.server.url("/login").toString())))
            val committed = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add)))
            val id = sources.activate(committed.items.single().reference!!, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
            val model = SourcesViewModel(context, sources, SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority),
                SourceLoginService(sources, accounts), registry, ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                model.select(id); idle()
                model.beginLogin(id)
                assertEquals(LoginStatus.SessionSaved, idle().loginStatus)
                assertNull(idle().accountName)
                val previous = sources.loginTarget(id)
                httpStatus = 503
                model.relogin(id)
                assertEquals(LoginStatus.LoggedOut, idle().loginStatus)
                assertTrue(previous.session.closed)
                assertNotEquals(previous.generation, sources.loginTarget(id).generation)
                assertNotNull(idle().message)
                httpStatus = 401
                model.beginLogin(id)
                assertEquals(LoginStatus.Required, idle().loginStatus)
                assertNull(idle().accountName)
                assertTrue(idle().storedSettingsAvailable)
                assertEquals(0, fixture.server.requestCount)
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

    @Test fun builtinSettingsDoNotConstructLazyProviders(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("registered-settings").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        val registry = WebSourceRegistry()
        val id = io.nightfish.lightnovelreader.api.identifier.Identifier("lightnovelreader", "Wenku8")
        var constructions = 0
        val registration = registry.register(SourceMetadata(
            io.nightfish.lightnovelreader.api.web.WebDataSourceItem(id, id.id, "Fixture"), setOf(SourceCapability.Search), builtIn = true)) {
            constructions++; error("Opening basic settings must not create a provider")
        }
        val sources = mockk<ImportedRuleSources>()
        coEvery { sources.sourceGroups() } returns emptyList()
        coEvery { sources.installedSources() } returns emptyList()
        val zLibrary = mockk<ZLibrarySources>()
        every { zLibrary.state } returns MutableStateFlow(indi.renakoni.nextvol.data.web.zlibrary.ZLibraryState())
        val model = SourcesViewModel(context, sources, mockk(), mockk(), registry, zLibrary)
        suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
        try {
            idle()
            model.select(id)
            val state = idle()
            assertEquals(id, state.selected)
            assertNull(state.network?.limitation)
            model.setBypassVpn(true)
            assertTrue(idle().network!!.bypassVpn)
            assertEquals(SourceNetworkMode.BypassVpn, SourceNetworkSettings(context, mockk()).mode(id))
            assertNull(state.loginForm)
            assertEquals(0, constructions)
            assertTrue(registry.sources.value.all { it.status == SourceStatus.Registered })
            coVerify(exactly = 0) { sources.loginTarget(any()) }
            coVerify(exactly = 0) { sources.storedSettings(any(), any()) }
        } finally { model.cancel(); registration.unregister(); Dispatchers.resetMain(); root.deleteRecursively() }
    }

    @Test fun absentOrInvalidLoginCannotOpenAnEmptyDialogOrRotateAccounts(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val root = Files.createTempDirectory("login-declarations").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        RuleSourceFixture().use { fixture ->
            fixture.afterRun = { error("Opening settings must not evaluate rules") }
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val model = SourcesViewModel(context, sources, SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority),
                SourceLoginService(sources, accounts), registry, ZLibrarySources(context, registry, hnovel.network.StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle()
                for ((index, ui) in listOf("", "[]", "not a form").withIndex()) {
                    val raw = JsonObject(fixture.raw() + mapOf("bookSourceUrl" to JsonPrimitive(fixture.server.url("/source-$index").toString()),
                        "loginUi" to JsonPrimitive(ui), "loginCheckJs" to JsonPrimitive("@js:throw 'never run'")))
                    val reference = sources.importer.commit(sources.importer.preview(raw.toString()), listOf(ImportSelection(0, ImportDecision.Add))).items.single().reference!!
                    val id = sources.activate(reference, listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                    model.select(id)
                    assertTrue(idle().storedSettingsAvailable)
                    model.beginLogin(id)
                    assertNull(idle().loginForm)
                    assertEquals(0L, accounts.current(id).generation)
                    assertEquals(SourceStatus.Registered, registry.sources.value.single { it.metadata.id == id }.status)
                }
                assertEquals(0, fixture.server.requestCount)
            } finally { model.cancel(); sources.stop(); Dispatchers.resetMain(); root.deleteRecursively() }
        }
    }
}
