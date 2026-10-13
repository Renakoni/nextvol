package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import indi.renakoni.nextvol.ui.home.settings.sources.SourcesViewModel
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class PixivSourceUpdatesTest {
    private fun adapted(): JsonObject = Json.parseToJsonElement(
        SourceCatalog(RuntimeEnvironment.getApplication()).definitions(setOf(PixivUpdateAdapter.KEY))
    ).jsonArray.single().jsonObject

    private class Download(context: Context, val server: MockWebServer) : SourceUpdateDownloader(context) {
        val addresses = mutableListOf<String>()
        var beforeDownload: suspend () -> Unit = {}
        override suspend fun preview(importer: SourceDefinitionImporter, address: String, profile: String,
            downloadGrant: NetworkGrant?): ImportPreview {
            addresses += address
            beforeDownload()
            return super.preview(importer, server.url("/pixiv.json").toString(), profile,
                NetworkGrant(server.url("/").toString(), true))
        }
    }

    private class Host(val context: Context, val fixture: RuleSourceFixture, val server: MockWebServer) {
        val accounts = SourceSessionManager(fixture.authority)
        val registry = WebSourceRegistry(fixture.authority)
        val sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
        val download = Download(context, server)
        val updates = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority, downloader = download)
        val grants = listOf(NetworkGrant("https://www.pixiv.net/"))
        lateinit var id: Identifier
        fun commit(raw: JsonObject): SourceDefinition {
            val preview = sources.importer.preview(raw.toString(), EXTENSION_PROFILE)
            assertTrue(preview.issues.toString(), preview.issues.isEmpty())
            val row = preview.candidates.single()
            val result = sources.importer.commit(preview, listOf(ImportSelection(row.index,
                row.existing?.let(ImportDecision::Replace) ?: ImportDecision.Add)))
            assertNull(result.error)
            assertNull(result.items.single().error)
            return sources.definitions.list().single()
        }
        fun reply(raw: JsonObject = pixivUpdateFixture()) = server.enqueue(MockResponse().setBody(raw.toString()))
        suspend fun installed() = sources.installedSources().single()
        suspend fun stored(area: StorageArea, key: String) = sources.loginTarget(id).session.read(StorageRequest(area, key))
        suspend fun rejects(code: RevisionError, action: suspend () -> Unit) {
            val failure = runCatching { action() }.exceptionOrNull()
            assertTrue(failure.toString(), failure is RevisionException)
            assertEquals(code, (failure as RevisionException).code)
        }
    }

    private suspend fun withSource(raw: JsonObject = adapted(), action: suspend Host.() -> Unit) {
        val root = Files.createTempDirectory("pixiv-update").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        RuleSourceFixture().use { fixture -> MockWebServer().use { server ->
            server.start()
            val host = Host(context, fixture, server)
            try {
                // Install the current maintained definition before exercising update requests.
                host.sources.restore()
                host.id = host.sources.activate(host.commit(raw).reference(), host.grants)
                host.action()
            } finally { host.sources.stop(); root.deleteRecursively() }
        } }
    }

    @Test fun knownCurrentReleaseDownloadsWithoutAccountAndExecutesNoScript(): Unit = runBlocking {
        withSource {
            sources.rotateAccount(id)
            assertTrue(accounts.setCookies(accounts.current(id), mapOf("session" to "private")))
            sources.loginTarget(id).session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, "private-account"))
            val before = installed()
            val account = accounts.current(id)
            var executions = 0
            fixture.beforeRun = { _, _ -> executions++ }
            reply()
            val check = updates.check(id)
            assertTrue(check.unchanged)
            assertEquals(listOf(PixivUpdateAdapter.UPDATE_URL), download.addresses)
            assertEquals(before, installed())
            assertEquals(account, accounts.current(id))
            assertEquals(before.definition, sources.definitions.list().single())
            assertEquals(0, executions)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/pixiv.json", request.path)
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
            assertTrue(File(context.cacheDir, "source-update-downloads").listFiles()!!.isEmpty())
        }
    }

    @Test fun unknownMalformedAndFailedDownloadsLeaveInstalledDataUntouched(): Unit = runBlocking {
        withSource {
            sources.createGroup("Keep", setOf(id))
            sources.setPreferences(id, discoveryVisible = false)
            val before = installed()
            val groups = sources.sourceGroups()
            val account = accounts.current(id)
            val unknown = JsonObject(pixivUpdateFixture() + ("jsLib" to JsonPrimitive("throw new Error('unreviewed');")))
            reply(unknown)
            rejects(RevisionError.UpstreamNotAdapted) { updates.check(id) }
            server.enqueue(MockResponse().setBody("not json"))
            rejects(RevisionError.UpstreamNotAdapted) { updates.check(id) }
            server.enqueue(MockResponse().setResponseCode(503))
            rejects(RevisionError.UpdateDownloadFailed) { updates.check(id) }
            assertEquals(before, installed())
            assertEquals(groups, sources.sourceGroups())
            assertEquals(account, accounts.current(id))
            assertEquals(before.definition, sources.definitions.list().single())
        }
    }

    @Test fun directApplyCannotBypassTheAdapterAndPermissionEditsStillWork(): Unit = runBlocking {
        withSource {
            val before = installed()
            val unsafe = commit(pixivUpdateFixture())
            rejects(RevisionError.UpstreamNotAdapted) { updates.apply(id, unsafe.reference(), grants) }
            assertEquals(before, installed())
            val permissions = grants + NetworkGrant("https://i.pximg.net/")
            updates.updatePermissions(id, permissions)
            assertEquals(before.definition, installed().definition)
            assertEquals(permissions, installed().origins)
        }
    }

    @Test fun staleChecksAreRejectedBeforeDownloading(): Unit = runBlocking {
        withSource {
            val before = installed().definition
            rejects(RevisionError.Stale) { updates.check(id, expected = before.reference().copy(revision = -1)) }
            assertTrue(download.addresses.isEmpty())
            assertEquals(before, installed().definition)
        }
    }

    @Test fun panelButtonUsesReaderUpdatesAndShowsConciseRetryableResults(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { withSource {
            val model = SourcesViewModel(context, sources, updates, SourceLoginService(sources, accounts), registry,
                ZLibrarySources(context, registry, StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle(); model.select(id); idle(); model.beginConfiguration(id)
                val form = idle().loginForm!!
                val button = form.fields.single { it.action == "updateSource()" }
                val before = installed()
                val account = accounts.current(id)
                var executions = 0
                fixture.beforeRun = { _, _ -> executions++ }
                server.enqueue(MockResponse().setResponseCode(503))
                model.submitLogin(form.values, button.id, form.id)
                assertEquals(R.string.sources_pixiv_update_failed, idle().message)
                assertNotNull(idle().loginForm)
                server.enqueue(MockResponse().setBody("{}"))
                model.submitLogin(form.values, button.id, form.id)
                assertEquals(R.string.sources_pixiv_update_unsupported, idle().message)
                assertNotNull(idle().loginForm)
                reply()
                model.submitLogin(form.values, button.id, form.id)
                assertEquals(R.string.sources_up_to_date, idle().message)
                assertNotNull(idle().loginForm)
                model.consumeMessage(R.string.sources_up_to_date)
                assertNull(idle().message)
                assertEquals(0, executions)
                assertEquals(List(3) { PixivUpdateAdapter.UPDATE_URL }, download.addresses)
                assertEquals(before, installed())
                assertEquals(account, accounts.current(id))
            } finally { model.cancel() }
        } } finally { Dispatchers.resetMain() }
    }

    @Test fun retiredPanelCannotDownloadOrPublishUpdateResults(): Unit = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { for (duringDownload in listOf(false, true)) withSource {
            val model = SourcesViewModel(context, sources, updates, SourceLoginService(sources, accounts), registry,
                ZLibrarySources(context, registry, StorageCipher.Plain))
            suspend fun idle() = withTimeout(10000) { model.state.first { !it.busy } }
            try {
                idle(); model.select(id); idle(); model.beginConfiguration(id)
                val form = idle().loginForm!!
                val before = installed()
                if (duringDownload) {
                    download.beforeDownload = { sources.rotateAccount(id) }
                    reply()
                } else sources.rotateAccount(id)
                model.submitLogin(form.values, form.fields.single { it.action == "updateSource()" }.id, form.id)
                assertNotNull(idle().message)
                assertNotEquals(R.string.sources_up_to_date, idle().message)
                assertNull(idle().preview)
                assertEquals(if (duringDownload) 1 else 0, download.addresses.size)
                assertEquals(before, installed())
            } finally { model.cancel() }
        } } finally { Dispatchers.resetMain() }
    }
}
