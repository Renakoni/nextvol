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
    @Test fun pixivConfigurationHelpMatchesNativeSections() {
        val pixiv = RuntimeEnvironment.getApplication().assets.open("source-catalog/Adult.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonArray.map { row -> row.jsonObject }
                .single { row -> row["bookSourceUrl"]?.jsonPrimitive?.content == "https://www.pixiv.net/novel" }
        }
        val help = pixiv.getValue("variableComment").jsonPrimitive.content
        assertTrue(help.contains("书源配置"))
        assertTrue(help.contains("上方账户区"))
        assertTrue(help.contains("扩展分类"))
        listOf("variableComment", "bookSourceComment").forEach { key ->
            val text = pixiv.getValue(key).jsonPrimitive.content
            assertFalse(text.contains("点击【👀 书源设置】"))
            assertFalse(text.contains("点击【👀 发现设置】"))
        }
    }

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
        assertEquals(listOf(18, 15, 9, 15, 9, 8), SourceCategory.entries.map { category -> catalog.entries.count { it.category == category } })
        assertEquals(74, catalog.entries.map { it.key }.toSet().size)
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

    @Test fun pixivNovelUsesItsOriginalIdentityInTheAdultCategoryWithoutBundledCredentials() {
        val pixiv = catalog.entries.single { it.key == "https://www.pixiv.net/novel" }
        assertEquals(SourceCategory.Adult, pixiv.category)
        assertEquals("Pixiv 小说", pixiv.name)
        val definition = raw(pixiv)
        assertEquals(pixiv.key, definition.getValue("bookSourceUrl").jsonPrimitive.content)
        assertEquals(0, definition.getValue("bookSourceType").jsonPrimitive.int)
        assertTrue(definition.getValue("bookSourceComment").jsonPrimitive.content.contains("https://github.com/DowneyRem/PixivSource"))
        for (field in listOf("searchUrl", "exploreUrl", "loginUrl", "loginUi", "loginCheckJs")) {
            assertTrue(field, definition.getValue(field).jsonPrimitive.content.isNotBlank())
        }
        assertEquals(buildJsonObject { put("Referer", "https://www.pixiv.net") },
            Json.parseToJsonElement(definition.getValue("header").jsonPrimitive.content))
        assertTrue(definition.keys.none { it in setOf("loginInfo", "loginHeader", "cookies", "token") })
        assertTrue(allEntries().none { it.key in setOf("https://www.pixiv.net", "https://www.pixiv.net/manga") })
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

    @Test fun withdrawnSourcesRetainInstalledClassificationButCannotBeAddedFromTheCatalog() {
        val entries = allEntries()
        assertEquals(92, entries.size)
        val importer = SourceDefinitionImporter(SourceDefinitionStore(folder.newFolder().toPath()))
        val preview = importer.preview(JsonArray(entries.map(::raw)).toString(), AUTO_PROFILE)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        for (entry in entries.filterNot { it.available }) {
            assertTrue(runCatching { catalog.definitions(setOf(entry.key)) }.isFailure)
            val definition = SourceDefinition("installed", "legado", LEGADO_PROFILE, entry.key, "Renamed",
                true, true, ImportOrigin(ImportOrigin.Kind.Paste), "custom", 1, "{}")
            assertEquals(entry.category, catalog.entry(definition)?.category)
        }
        assertTrue(entries.filter { it.name in setOf("轻小说机翻", "连城读书", "ESJ Zone", "鲸云轻说", "疯情书库", "八一中文", "全本小说（quanben5）", "趣书网（qubook）", "掌阅") }
            .all { !it.available })
        assertTrue(entries.filter { raw(it)["exploreUrl"]?.jsonPrimitive?.content.isNullOrBlank() }.all { !it.available })
    }

    @Test fun formerOfficialSitesSitInTheirOriginalCategoriesAndDeadSitesAreGone() {
        fun category(name: String) = allEntries().single { it.name == name }.category
        assertEquals(SourceCategory.Platforms, category("QQ阅读"))
        assertEquals(SourceCategory.Platforms, category("起点中文网"))
        assertEquals(SourceCategory.Female, category("晋江文学城"))
        assertEquals(SourceCategory.Anime, category("SF轻小说／菠萝包"))
        assertEquals(SourceCategory.Literature, category("豆瓣阅读"))
        assertEquals(SourceCategory.Adult, category("PO18"))
        assertTrue(allEntries().none { it.name == "涩涩俱乐部" })
        assertFalse(assets.list("source-catalog")!!.contains("Official.json"))
    }

    @Test fun bundledRepairsMatchOnlyTheirKnownDigests() {
        for (entry in allEntries().filter { it.replaces.isNotEmpty() }) for (digest in entry.replaces) {
            val original = SourceDefinition("installed", "legado", LEGADO_PROFILE, entry.key, "Renamed",
                true, true, ImportOrigin(ImportOrigin.Kind.Paste), digest, 1, "{}")
            assertEquals(JsonArray(listOf(raw(entry))).toString(), catalog.replacement(original))
            assertNull(catalog.replacement(original.copy(contentDigest = "user-edited")))
            assertNull(catalog.replacement(original.copy(importKey = "https://unrelated.invalid/")))
        }
        val ciweimao = raw(allEntries().single { it.key == "https://www.ciweimao.com/" })
        assertTrue(ciweimao.getValue("browserRead").jsonPrimitive.boolean)
        assertTrue(ciweimao.getValue("enabledCookieJar").jsonPrimitive.boolean)
        val liancheng = raw(allEntries().single { it.name == "连城读书" })
        assertEquals("[]", liancheng.getValue("homepageModules").jsonPrimitive.content)
    }

    @Test fun metadataRepairsRecognizeThePreviouslyBundledDefinitions() {
        val oldKinds = mapOf(
            "https://www.biqusa.com/#" to "id.info@tag.p.2@text##最后更新：",
            "https://m.shuhaige.net/" to "tag.p.2@tag.a@text&&\ntag.p.2@tag.span.0@text&&\ntag.p.4@text##最后更新：",
            "https://m.x33yq.org/" to ".layui-btn-radius@text&&\n.new + p@text##最后更新："
        )
        for ((key, oldKind) in oldKinds) {
            val entry = catalog.entries.single { it.key == key }
            val current = raw(entry)
            val oldFields = buildJsonObject {
                for ((name, value) in current.getValue("ruleBookInfo").jsonObject) when {
                    name == "kind" || name == "updateTime" && key.contains("biqusa") -> put("kind", oldKind)
                    name != "updateTime" -> put(name, value)
                }
            }
            val old = JsonObject(current + ("ruleBookInfo" to oldFields))
            val store = SourceDefinitionStore(folder.newFolder().toPath())
            val importer = SourceDefinitionImporter(store)
            val preview = importer.preview(old.toString(), AUTO_PROFILE)
            assertTrue(preview.issues.toString(), preview.issues.isEmpty())
            assertNull(importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).error)
            val installed = store.list().single()
            assertTrue(key, installed.contentDigest in entry.replaces)
            assertEquals(catalog.definitions(setOf(key)), catalog.replacement(installed))
            assertNull(catalog.replacement(installed.copy(contentDigest = "user-edited")))
        }
    }

    @Test fun bundledDefinitionsExcludeFixedAccountAndTrackingHeaders() {
        val entries = listOf(
            allEntries().single { it.key.startsWith("https://api.uaa.com") } to setOf("cookie", "token"),
            allEntries().single { it.key == "http://api.doufu.me/" } to setOf("cookie"),
            allEntries().single { it.key == "https://m.shuhaige.net/" } to setOf("cookie", "token"),
        )
        entries.forEach { (entry, removed) ->
            val definition = raw(entry)
            val headers = Json.parseToJsonElement(definition.getValue("header").jsonPrimitive.content).jsonObject
            assertTrue(headers.keys.none { it.lowercase() in removed })
            if (entry.key.startsWith("https://api.uaa.com")) {
                assertTrue(headers.keys.any { it.equals("User-Agent", ignoreCase = true) })
            }
        }
    }

    @Test fun crossCategorySelectionPreviewsOnlyChosenSourcesAndNamesNeverDetermineClassification() {
        val chosen = listOf(catalog.entries.first { it.category == SourceCategory.Anime }, catalog.entries.last())
        val importer = SourceDefinitionImporter(SourceDefinitionStore(folder.newFolder().toPath()))
        val preview = importer.preview(catalog.definitions(chosen.map { it.key }.toSet()), AUTO_PROFILE)
        assertEquals(chosen.map { it.key }, preview.candidates.map { it.importKey })
        val results = importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
        assertTrue(results.items.all { it.error == null })
        val sameName = SourceDefinition("unrelated", "legado", LEGADO_PROFILE, "https://unrelated.invalid/", chosen.first().name,
            true, false, ImportOrigin(ImportOrigin.Kind.Paste), "digest", 1, "{}")
        assertNull(catalog.entry(sameName))
        assertEquals(chosen.first().category, catalog.entry(sameName.copy(importKey = chosen.first().key, displayName = "Renamed"))?.category)
    }
}
