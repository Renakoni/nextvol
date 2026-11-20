package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.web.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourcePreferencesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun host() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val root = folder.newFolder()
        override fun getFilesDir() = root
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    private fun definition(sources: ImportedRuleSources, raw: JsonObject): SourceDefinition {
        val preview = sources.importer.preview(raw.toString())
        val reference = sources.importer.commit(preview, listOf(ImportSelection(0,
            preview.candidates.single().existing?.let(ImportDecision::Replace) ?: ImportDecision.Add))).items.single().reference!!
        return sources.definitions.list().single { it.reference() == reference }
    }

    @Test fun restorationRefreshesOnlyExactCatalogMappings() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val entry = SourceCatalog(context).entries.first()
            try {
                val known = definition(sources, JsonObject(fixture.raw() + ("bookSourceUrl" to JsonPrimitive(entry.key))))
                sources.activate(known.reference(), emptyList())
                val unknown = definition(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive(entry.name))))
                sources.activate(unknown.reference(), emptyList())
                sources.stop()
                val snapshot = File(context.filesDir, "rule-sources/active.json")
                val saved = Json.parseToJsonElement(snapshot.readText()).jsonObject
                val rows = saved.getValue("sources").jsonArray.map { row ->
                    JsonObject(row.jsonObject + ("preferences" to JsonObject(row.jsonObject.getValue("preferences").jsonObject - "category")))
                }
                snapshot.writeText(JsonObject(saved + ("sources" to JsonArray(rows))).toString())
                registry = WebSourceRegistry(fixture.authority)
                sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
                sources.restore()
                assertEquals(entry.category, sources.installedSources().first { it.definition.sourceId == known.sourceId }.preferences.category)
                assertNull(sources.installedSources().first { it.definition.sourceId == unknown.sourceId }.preferences.category)
                assertTrue(registry.sources.value.isEmpty())
            } finally { sources.stop() }
        }
    }

    @Test fun revisionUpdateAndProcessRestartKeepUserPreferencesOverNewDefaults() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
            try {
                val raw = JsonObject(fixture.raw() + mapOf("enabled" to JsonPrimitive(false), "exploreUrl" to JsonPrimitive("Latest::/search")))
                val first = definition(sources, raw)
                val id = sources.activate(first.reference(), grants)
                sources.setPreferences(id, enabled = true, discoveryVisible = false)
                val updated = definition(sources, JsonObject(raw + ("bookSourceName" to JsonPrimitive("Updated"))))
                SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority).apply(id, updated.reference(), grants)
                assertEquals(SourcePreferences(true, false, true), sources.installedSources().single().preferences)
                assertTrue(registry.resolve(id) is SourceResolution.Ready)
                sources.setPreferences(id, enabled = false)
                sources.stop()
                registry = WebSourceRegistry(fixture.authority)
                sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
                sources.restore()
                assertEquals(SourcePreferences(false, false, true), sources.installedSources().single().preferences)
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                assertEquals(updated.contentDigest, sources.installedSources().single().definition.contentDigest)
                sources.setPreferences(id, enabled = true)
                val restored = (registry.resolve(id) as SourceResolution.Ready).runtime
                assertEquals(id, restored.id)
                assertFalse(SourceCapability.Categories in restored.metadata.capabilities)
            } finally { sources.stop() }
        }
    }

    @Test fun disableRetiresBeforePublicationCancelsOwnedWorkAndKeepsOtherSourceAvailable() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host(), registry, fixture.authority, SourceSessionManager(fixture.authority), fixture.runner)
            try {
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val a = sources.activate(definition(sources, fixture.raw("A")).reference(), grants)
                val b = sources.activate(definition(sources, fixture.raw("B")).reference(), grants)
                val oldA = (registry.resolve(a) as SourceResolution.Ready).runtime
                val runtimeB = (registry.resolve(b) as SourceResolution.Ready).runtime
                val entered = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                fixture.afterRun = {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                val pending = async { runCatching { oldA.getBookInformation(fixture.server.url("/book/one").toString()) } }
                withTimeout(5000) { entered.await() }
                val availableAtPublication = async(Dispatchers.Unconfined) {
                    registry.sources.first { list -> list.none { it.metadata.id == a } }
                    oldA.isAvailable
                }
                sources.setPreferences(a, enabled = false)
                assertFalse(availableAtPublication.await())
                withTimeout(5000) { cancelled.await(); pending.await() }
                assertTrue(runtimeB.isAvailable)
                assertSame(runtimeB, (registry.resolve(b) as SourceResolution.Ready).runtime)
                assertTrue(registry.resolve(a) is SourceResolution.Missing)
            } finally { sources.stop() }
        }
    }

    @Test fun reenableKeepsConfigurationCachesAndSessionCookiesButNeverAnotherAccount() = runBlocking {
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(host(), registry, fixture.authority, accounts, fixture.runner)
            try {
                val origin = fixture.server.url("/").toString()
                val id = sources.activate(definition(sources, fixture.raw()).reference(), listOf(NetworkGrant(origin, true)))
                val old = sources.loginTarget(id).session
                old.setCookie(origin, "session=memory-only")
                old.write(StorageRequest(StorageArea.Config, "variable", "kept configuration"))
                old.write(StorageRequest(StorageArea.Cache, "metadata", "kept cache", ttlMillis = 60000))
                old.write(StorageRequest(StorageArea.Account, "credential", "old account"))
                sources.setPreferences(id, enabled = false)
                assertTrue(old.closed)
                sources.setPreferences(id, enabled = true)
                val next = sources.loginTarget(id).session
                assertEquals("session=memory-only", next.cookie(origin))
                assertEquals(StorageResult.Value("kept configuration"), next.read(StorageRequest(StorageArea.Config, "variable")))
                assertEquals(StorageResult.Value("kept cache"), next.read(StorageRequest(StorageArea.Cache, "metadata")))
                sources.setPreferences(id, enabled = false)
                val account = accounts.begin(id)
                sources.setPreferences(id, enabled = true)
                val changed = sources.loginTarget(id).session
                assertEquals(account.generation, changed.scope.accountGeneration)
                assertEquals("", changed.cookie(origin))
                assertEquals(StorageResult.Value(null), changed.read(StorageRequest(StorageArea.Account, "credential")))
                assertEquals(StorageResult.Value("kept configuration"), changed.read(StorageRequest(StorageArea.Config, "variable")))
            } finally { sources.stop() }
        }
    }

    @Test fun preferencePersistenceFailureDoesNotRetireTheCurrentRuntime() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority, SourceSessionManager(fixture.authority), fixture.runner)
            try {
                val id = sources.activate(definition(sources, fixture.raw()).reference(), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                val before = sources.installedSources().single().preferences
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val base = File(context.filesDir, "rule-sources/active.json")
                val saved = base.readBytes()
                val backup = File(context.filesDir, "rule-sources/active.json.bak").apply { writeBytes(saved) }
                check(base.delete()); check(base.mkdir()); File(base, "block").writeText("fixture")
                try {
                    assertTrue(runCatching { sources.setPreferences(id, enabled = false) }.isFailure)
                    assertTrue(runCatching { sources.setPreferences(id, discoveryVisible = !before.discoveryVisible) }.isFailure)
                } finally { base.deleteRecursively(); base.writeBytes(saved); backup.delete() }
                assertEquals(before, sources.installedSources().single().preferences)
                assertSame(runtime, (registry.resolve(id) as SourceResolution.Ready).runtime)
                assertTrue(runtime.isAvailable)
                assertEquals("Same title", runtime.getBookInformation(fixture.server.url("/book/one").toString()).get()!!.title)
            } finally { sources.stop() }
        }
    }
}
