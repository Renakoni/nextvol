package indi.renakoni.nextvol.data.web

import android.app.Application
import android.content.ContextWrapper
import hnovel.content.RuleTaskRunner
import hnovel.execution.ExecutionAuthority
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.web.rules.ImportedRuleSources
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceCatalogTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalog = SourceCatalog(RuntimeEnvironment.getApplication())

    private val assets get() = RuntimeEnvironment.getApplication().assets
    private fun allEntries() = assets.open("source-catalog/catalog.json").bufferedReader().use {
        Json.decodeFromString<List<CatalogSource>>(it.readText())
    }
    private fun raw(entry: CatalogSource) = assets.open("source-catalog/${entry.category.name}.json").bufferedReader().use {
        Json.parseToJsonElement(it.readText()).jsonArray[entry.index].jsonObject
    }

    @Test fun catalogEntriesUseTheirBundledDefinitionsAndPassTheProductionImporter() {
        assertEquals(listOf(18, 15, 10, 12, 8, 8), SourceCategory.entries.map { category -> catalog.entries.count { it.category == category } })
        assertEquals(71, catalog.entries.map { it.key }.toSet().size)
        val store = SourceDefinitionStore(folder.newFolder().toPath())
        val importer = SourceDefinitionImporter(store)
        val preview = importer.preview(catalog.definitions(catalog.entries.map { it.key }.toSet()), AUTO_PROFILE)
        assertEquals(emptyList<ImportIssue>(), preview.issues)
        assertEquals(catalog.entries.map { it.key }, preview.candidates.map { it.importKey })
        assertTrue(store.list().isEmpty())
        for (category in SourceCategory.entries) {
            val entries = catalog.entries.filter { it.category == category }
            if (entries.isNotEmpty()) assertEquals(JsonArray(entries.map(::raw)),
                Json.parseToJsonElement(catalog.definitions(entries.map { it.key }.toSet())))
        }
    }

    @Test fun everyAddableCategorySourceActivatesAndRestoresWithoutChangingOrdinaryBookIdentity() = runBlocking {
        val root = folder.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = root
        }
        val authority = ExecutionAuthority()
        val registry = WebSourceRegistry(authority)
        val accounts = SourceSessionManager(authority)
        val runner = RuleTaskRunner { _, _, _, _ -> error("Restoring a source must not execute its scripts") }
        var sources = ImportedRuleSources(context, registry, authority, accounts, runner)
        try {
            val preview = sources.importer.preview(catalog.definitions(catalog.entries.map { it.key }.toSet()), AUTO_PROFILE)
            assertTrue(preview.issues.toString(), preview.issues.isEmpty())
            val committed = sources.importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
            assertNull(committed.error)
            assertTrue(committed.items.all { it.error == null })
            // Registration needs a grant, but this test must never contact any bundled website.
            val grants = listOf(NetworkGrant("https://catalog-fixture.invalid"))
            val ids = sources.activateBatch(committed.items.associate { checkNotNull(it.reference) to grants }, enableNew = true)
            assertEquals(catalog.entries.size, ids.size)
            val installed = sources.installedSources().associate { it.definition.sourceId to it.definition.contentDigest }
            repeat(2) {
                assertFalse(sources.restorationFailed)
                assertEquals(installed, sources.installedSources().associate { it.definition.sourceId to it.definition.contentDigest })
                assertEquals(ids.toSet(), registry.sources.value.map { it.metadata.id }.toSet())
                for (entry in catalog.entries) {
                    val definition = sources.installedSources().single { it.definition.importKey == entry.key }.definition
                    val resolution = registry.resolve(ImportedRuleSources.id(definition))
                    assertTrue(entry.name, resolution is SourceResolution.Ready)
                    val runtime = (resolution as SourceResolution.Ready).runtime
                    assertEquals(entry.category, runtime.metadata.category)
                    assertTrue("${entry.name} must offer a discovery homepage", SourceCapability.Explore in runtime.metadata.capabilities)
                    assertEquals("https://catalog-fixture.invalid/book", runtime.canonicalBookId("https://catalog-fixture.invalid/book"))
                }
                if (it == 0) {
                    sources.stop()
                    sources = ImportedRuleSources(context, registry, authority, accounts, runner)
                    sources.restore()
                }
            }
        } finally { sources.stop() }
    }

    @Test fun bundledDefinitionsExcludeFixedAccountAndTrackingHeaders() {
        val entries = listOf(
            allEntries().single { it.key == "https://www.uaa.com/" } to setOf("cookie", "token"),
            allEntries().single { it.key == "https://m.shuhaige.net/" } to setOf("cookie", "token"),
        )
        for ((entry, removed) in entries) {
            val definition = raw(entry)
            val header = definition.getValue("header").jsonPrimitive.content
            if (header.startsWith("@js:")) {
                assertTrue(header.contains("java.getWebViewUA()"))
                assertFalse(header.contains("cookie", ignoreCase = true))
                continue
            }
            val headers = Json.parseToJsonElement(header).jsonObject
            assertTrue(headers.keys.none { it.lowercase() in removed })
            if (entry.key == "https://www.uaa.com/") {
                assertTrue(headers.keys.any { it.equals("User-Agent", ignoreCase = true) })
            }
        }
    }

}
