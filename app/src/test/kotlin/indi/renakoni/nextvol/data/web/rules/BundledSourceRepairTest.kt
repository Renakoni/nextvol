package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.web.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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
class BundledSourceRepairTest {
    private class Host : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val root = Files.createTempDirectory("bundled-repair").toFile()
        override fun getFilesDir() = root
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    private fun commit(sources: ImportedRuleSources, raw: JsonObject): SourceDefinition {
        val preview = sources.importer.preview(raw.toString())
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        val candidate = preview.candidates.single()
        val result = sources.importer.commit(preview, listOf(ImportSelection(candidate.index,
            candidate.existing?.let(ImportDecision::Replace) ?: ImportDecision.Add)))
        assertNull(result.error)
        return sources.definitions.list().single { it.reference() == result.items.single().reference }
    }

    private fun catalog(before: SourceDefinition, next: JsonObject) = mockk<SourceCatalog> {
        every { entry(any()) } returns null
        every { replacement(any()) } answers {
            next.toString().takeIf { firstArg<SourceDefinition>().contentDigest == before.contentDigest }
        }
    }

    @Test fun aRetiredStoredCategoryStillRestoresAndTakesTheCatalogCategory() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            fun category(value: SourceCategory) = mockk<SourceCatalog> {
                every { replacement(any()) } returns null
                every { entry(any()) } answers {
                    val definition = firstArg<SourceDefinition>()
                    CatalogSource(definition.importKey, value, definition.displayName, "", 0)
                }
            }
            var sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority,
                accounts, fixture.runner, catalog = category(SourceCategory.Platforms))
            try {
                val definition = commit(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(definition.reference(), grants)
                sources.setPreferences(id, enabled = false, discoveryVisible = false)
                val before = sources.installedSources().single()
                val generation = accounts.current(id).generation
                sources.stop()
                // Installs from before Official Sites was dissolved still store that name.
                val snapshot = File(host.root, "rule-sources/active.json")
                val stored = snapshot.readText()
                assertTrue(stored.contains("\"category\":\"Platforms\""))
                snapshot.writeText(stored.replace("\"category\":\"Platforms\"", "\"category\":\"Official\""))
                sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority,
                    accounts, fixture.runner, catalog = category(SourceCategory.Female))
                sources.restore()
                val after = sources.installedSources().single()
                assertEquals(before.definition, after.definition)
                assertEquals(before.origins, after.origins)
                assertEquals(before.preferences.copy(category = SourceCategory.Female), after.preferences)
                assertEquals(generation, accounts.current(id).generation)
                assertEquals(0, fixture.server.requestCount)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun repairPreservesIdentityPreferencesGrantsAccountAndStoredSettings() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
            try {
                val before = commit(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(before.reference(), grants)
                val account = sources.rotateAccount(id)
                account.session.setCookie(fixture.server.url("/").toString(), "session=retained")
                sources.saveVariable(id, "reader choice")
                sources.setPreferences(id, enabled = false, discoveryVisible = false)
                val preferences = sources.installedSources().single().preferences
                val repaired = JsonObject(fixture.raw() + ("enabledCookieJar" to JsonPrimitive(true)))
                sources.stop()
                sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner,
                    catalog = catalog(before, repaired))
                sources.restore()
                val installed = sources.installedSources().single()
                assertEquals(id, ImportedRuleSources.id(installed.definition))
                assertEquals(repaired, Json.parseToJsonElement(installed.definition.rawJson))
                assertEquals(before, installed.previous)
                assertEquals(grants, installed.origins)
                assertEquals(preferences, installed.preferences)
                assertEquals(account.generation, accounts.current(id).generation)
                assertEquals("reader choice", sources.storedSettings(id).variable)
                assertEquals(0, fixture.documents.get())
                sources.setPreferences(id, enabled = true)
                assertEquals("session=retained", sources.loginTarget(id).session.cookie(fixture.server.url("/").toString()))
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun pendingCustomImportAndEditedInstalledDefinitionArePreserved() = runBlocking {
        for (activateCustom in listOf(false, true)) {
            val host = Host()
            RuleSourceFixture().use { fixture ->
                val accounts = SourceSessionManager(fixture.authority)
                var sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                try {
                    val before = commit(sources, fixture.raw())
                    val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                    val id = sources.activate(before.reference(), grants)
                    val custom = commit(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("My edits"))))
                    if (activateCustom) SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                        .apply(id, custom.reference(), grants)
                    sources.stop()
                    sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner,
                        catalog = catalog(before, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("Repair")))))
                    sources.restore()
                    assertEquals(if (activateCustom) custom else before, sources.installedSources().single().definition)
                    assertEquals(custom, sources.definitions.list().single())
                } finally { sources.stop(); host.root.deleteRecursively() }
            }
        }
    }

    @Test fun interruptedRepairResumesAndAnExplicitRollbackSurvivesRestart() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
            try {
                val before = commit(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(before.reference(), grants)
                val raw = JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("Repair")))
                val repaired = commit(sources, raw) // Process stopped before writing active.json.
                val catalog = catalog(before, raw)
                sources.stop()
                sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner, catalog = catalog)
                sources.restore()
                assertEquals(repaired, sources.installedSources().single().definition)
                assertEquals(before, sources.installedSources().single().previous)
                assertEquals(repaired.revision, sources.definitions.list().single().revision)
                SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority).rollback(id, grants)
                sources.stop()
                sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner, catalog = catalog)
                sources.restore()
                assertEquals(before, sources.installedSources().single().definition)
                assertEquals(repaired, sources.installedSources().single().previous)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }
}
