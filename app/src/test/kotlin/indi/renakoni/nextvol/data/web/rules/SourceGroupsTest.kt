package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import com.github.michaelbull.result.get
import hnovel.content.RuleSourceFixture
import hnovel.imports.*
import hnovel.network.*
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
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
class SourceGroupsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun host() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val root = folder.newFolder()
        override fun getFilesDir() = root
    }
    private fun commit(sources: ImportedRuleSources, raw: JsonObject): DefinitionReference {
        val preview = sources.importer.preview(raw.toString())
        return sources.importer.commit(preview, listOf(ImportSelection(0,
            preview.candidates.single().existing?.let(ImportDecision::Replace) ?: ImportDecision.Add))).items.single().reference!!
    }

    @Test fun nativeAndRuleSourcesShareGroupsAndDisabledPreferencesSurviveRestart() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val native = indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
            var registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            var zLibrary = indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources(context, registry, StorageCipher.Plain)
            var activations = 0
            suspend fun registerNative() {
                zLibrary.restore()
                sources.registerNative(native.METADATA, { "z-lib.gd" }, zLibrary.state.value.settings.enabled) { enabled ->
                    activations++
                    zLibrary.update(zLibrary.state.value.settings.copy(enabled = enabled))
                }
            }
            try {
                registerNative()
                val catalogGroup = sources.sourceGroups().single()
                sources.renameGroup(catalogGroup.id, "My books")
                val rule = sources.activate(commit(sources, fixture.raw()), emptyList())
                sources.updateGroups(setOf(rule), setOf(catalogGroup.id), emptySet())
                sources.createGroup("Favorites", setOf(rule, native.ID), removed = setOf(catalogGroup.id))
                val group = sources.sourceGroups().last()
                assertEquals(setOf(group.id), sources.nativeSources().single().preferences.groupIds)
                assertEquals(setOf(group.id), sources.installedSources().single().preferences.groupIds)
                assertEquals(mapOf(rule to setOf(group.id), native.ID to setOf(group.id)), sources.grouping.value.memberships)
                assertEquals(1, activations) // Membership edits must not rebuild source runtimes.
                sources.setPreferences(native.ID, enabled = false)
                assertTrue(registry.resolve(native.ID) is SourceResolution.Missing)
                sources.stop(); zLibrary.stop()
                registry = WebSourceRegistry(fixture.authority)
                sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
                zLibrary = indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources(context, registry, StorageCipher.Plain)
                registerNative()
                assertFalse(sources.nativeSources().single().preferences.enabled)
                assertEquals(setOf(group.id), sources.nativeSources().single().preferences.groupIds)
                assertEquals("My books", sources.sourceGroups().first().name)
                assertEquals(sources.sourceGroups(), sources.grouping.value.groups)
                assertEquals(setOf(group.id), sources.grouping.value.memberships[native.ID])
                assertTrue(registry.resolve(native.ID) is SourceResolution.Missing)
                sources.deleteGroup(group.id)
                assertTrue(sources.nativeSources().single().preferences.groupIds.isEmpty())
                assertTrue(sources.installedSources().single().preferences.groupIds.isEmpty())
                sources.setPreferences(native.ID, enabled = true)
                assertTrue(registry.sources.value.any { it.metadata.id == native.ID })
                assertTrue(sources.nativeSources().single().preferences.enabled)
            } finally { sources.stop(); zLibrary.stop() }
        }
    }

    @Test fun catalogGroupsAreCreatedOnDemandAndReusedAfterRenameAndRestart() = runBlocking {
        val context = host()
        val catalog = SourceCatalog(context)
        val literature = catalog.entries.filter { it.category == SourceCategory.Literature }.take(3)
        val female = catalog.entries.first { it.category == SourceCategory.Female }
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
            suspend fun add(entries: List<CatalogSource>, grouped: Boolean = true) {
                val preview = sources.importer.preview(catalog.definitions(entries.map { it.key }.toSet()), AUTO_PROFILE)
                val committed = sources.importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
                sources.activateBatch(committed.items.associate { checkNotNull(it.reference) to emptyList<NetworkGrant>() },
                    enableNew = true, groupByCatalog = grouped)
            }
            try {
                add(literature.take(2) + female)
                assertEquals(setOf(context.getString(SourceCategory.Literature.title), context.getString(SourceCategory.Female.title)),
                    sources.sourceGroups().map { it.name }.toSet())
                val group = sources.sourceGroups().single { it.name == context.getString(SourceCategory.Literature.title) }
                assertEquals(2, sources.installedSources().count { group.id in it.preferences.groupIds })
                sources.renameGroup(group.id, "My classics")
                sources.stop()
                sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                add(literature.takeLast(1))
                assertEquals(2, sources.sourceGroups().size)
                assertEquals("My classics", sources.sourceGroups().single { it.id == group.id }.name)
                assertEquals(3, sources.installedSources().count { group.id in it.preferences.groupIds })
                val manual = catalog.entries.first { it.category == SourceCategory.Anime }
                add(listOf(manual), grouped = false)
                assertTrue(sources.installedSources().single { it.definition.importKey == manual.key }.preferences.groupIds.isEmpty())
                assertEquals(2, sources.sourceGroups().size)
            } finally { sources.stop() }
        }
    }

    @Test fun catalogImportReusesAnExistingSameNameGroupWithoutMovingOtherSources() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority,
                SourceSessionManager(fixture.authority), fixture.runner)
            try {
                val existing = sources.activate(commit(sources, fixture.raw()), emptyList())
                sources.createGroup(context.getString(SourceCategory.Literature.title))
                val group = sources.sourceGroups().single()
                val catalog = SourceCatalog(context)
                val entry = catalog.entries.first { it.category == SourceCategory.Literature }
                val preview = sources.importer.preview(catalog.definitions(setOf(entry.key)), AUTO_PROFILE)
                val reference = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).items.single().reference!!
                sources.activateBatch(mapOf(reference to emptyList()), groupByCatalog = true)
                assertEquals(listOf(group), sources.sourceGroups())
                assertEquals(setOf(group.id), sources.installedSources().single { it.definition.importKey == entry.key }.preferences.groupIds)
                assertTrue(sources.installedSources().single { ImportedRuleSources.id(it.definition) == existing }.preferences.groupIds.isEmpty())
            } finally { sources.stop() }
        }
    }

    @Test fun failedCatalogBatchDoesNotLeaveEmptyGroupsOrPartialMemberships() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority,
                SourceSessionManager(fixture.authority), fixture.runner)
            try {
                sources.createGroup("Existing")
                val before = File(context.filesDir, "rule-sources/active.json").readText()
                val catalog = SourceCatalog(context)
                val entries = catalog.entries.filter { it.category == SourceCategory.Literature }.take(2)
                val preview = sources.importer.preview(catalog.definitions(entries.map { it.key }.toSet()), AUTO_PROFILE)
                val committed = sources.importer.commit(preview, preview.candidates.map { ImportSelection(it.index, ImportDecision.Add) })
                val references = committed.items.mapIndexed { index, item ->
                    checkNotNull(item.reference) to if (index == 0) emptyList() else List(33) { NetworkGrant("https://fixture.invalid") }
                }.toMap()
                assertTrue(runCatching { sources.activateBatch(references, groupByCatalog = true) }.isFailure)
                assertEquals(listOf("Existing"), sources.sourceGroups().map { it.name })
                assertTrue(sources.installedSources().isEmpty())
                assertEquals(before, File(context.filesDir, "rule-sources/active.json").readText())
            } finally { sources.stop() }
        }
    }

    @Test fun pasteFileAndUrlImportsIgnoreAuthorGroupsAndStartUngrouped() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority,
                SourceSessionManager(fixture.authority), fixture.runner)
            try {
                sources.createGroup("My novels")
                for (kind in listOf("paste", "file", "url")) {
                    val raw = JsonObject(fixture.raw(kind) + ("bookSourceGroup" to JsonPrimitive("Author group,Official")))
                    fixture.server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) = MockResponse().setBody(raw.toString())
                    }
                    val preview = when (kind) {
                        "file" -> sources.importer.previewStream(raw.toString().byteInputStream(), "sources.json")
                        "url" -> {
                            val session = fixture.broker.open(SourceScope("imports", "groups", LEGADO_PROFILE),
                                listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                            sources.importer.previewUrl(fixture.server.url("/sources.json").toString(), session)
                        }
                        else -> sources.importer.preview(raw.toString())
                    }
                    val ref = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add))).items.single().reference!!
                    sources.activate(ref, emptyList())
                }
                assertEquals(3, sources.installedSources().size)
                assertTrue(sources.installedSources().all { it.preferences.groupIds.isEmpty() })
                assertEquals(1, sources.sourceGroups().size)
            } finally { sources.stop() }
        }
    }

    @Test fun batchMembershipsRenameDeleteAndEmptyGroupsSurviveRestartWithoutRebindingSources() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
            try {
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val ids = listOf("one", "two").map { sources.activate(commit(sources, fixture.raw(it)), grants) }.toSet()
                val before = sources.installedSources()
                val runtime = (registry.resolve(ids.first()) as SourceResolution.Ready).runtime
                sources.createGroup(" Favorites ", ids)
                sources.createGroup("Empty")
                val group = sources.sourceGroups().first()
                assertEquals("Favorites", group.name)
                assertTrue(sources.installedSources().all { it.preferences.groupIds == setOf(group.id) })
                assertSame(runtime, (registry.resolve(ids.first()) as SourceResolution.Ready).runtime)
                assertEquals(before.map { it.definition }, sources.installedSources().map { it.definition })
                assertEquals(before.map { it.origins }, sources.installedSources().map { it.origins })
                sources.renameGroup(group.id, "Reading")
                sources.stop()
                registry = WebSourceRegistry(fixture.authority)
                sources = ImportedRuleSources(context, registry, fixture.authority, accounts, fixture.runner)
                assertEquals(listOf("Reading", "Empty"), sources.sourceGroups().map { it.name })
                assertTrue(sources.installedSources().all { it.preferences.groupIds == setOf(group.id) })
                sources.updateGroups(setOf(ids.first()), emptySet(), setOf(group.id))
                assertEquals(1, sources.installedSources().count { group.id in it.preferences.groupIds })
                sources.deleteGroup(group.id)
                assertEquals(2, sources.installedSources().size)
                assertTrue(sources.installedSources().all { it.preferences.groupIds.isEmpty() })
                sources.stop()
                sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                assertEquals(listOf("Empty"), sources.sourceGroups().map { it.name })
                assertTrue(sources.installedSources().all { it.preferences.groupIds.isEmpty() })
            } finally { sources.stop() }
        }
    }

    @Test fun invalidNamesAndStaleBatchMembershipLeaveSnapshotUnchanged() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority,
                SourceSessionManager(fixture.authority), fixture.runner)
            try {
                val id = sources.activate(commit(sources, fixture.raw()), emptyList())
                sources.createGroup("Reading")
                val group = sources.sourceGroups().single()
                val file = File(context.filesDir, "rule-sources/active.json")
                val before = file.readText()
                for (name in listOf("", "  ", "reading", "a\nb", "x".repeat(61)))
                    assertTrue(runCatching { sources.createGroup(name) }.isFailure)
                assertTrue(runCatching { sources.updateGroups(setOf(id, Identifier("rules", "removed")), setOf(group.id), emptySet()) }.isFailure)
                assertTrue(runCatching { sources.updateGroups(setOf(id), setOf("removed"), emptySet()) }.isFailure)
                assertTrue(runCatching { sources.updateGroups(setOf(id), setOf(group.id), setOf(group.id)) }.isFailure)
                assertTrue(runCatching { sources.createGroup("New", setOf(id), setOf("removed")) }.isFailure)
                assertTrue(runCatching { sources.createGroup("New", setOf(id), setOf(group.id), setOf(group.id)) }.isFailure)
                assertEquals(before, file.readText())
                assertTrue(sources.installedSources().single().preferences.groupIds.isEmpty())
            } finally { sources.stop() }
        }
    }

    @Test fun replacementPreservesGroupWhileRemovalDoesNotRestoreMembership() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
            try {
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(commit(sources, fixture.raw()), grants)
                sources.createGroup("Reading", setOf(id))
                sources.createGroup("Favorites", setOf(id))
                val groups = sources.sourceGroups()
                val memberships = groups.map { it.id }.toSet()
                val update = commit(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("Renamed"))))
                val service = SourceRevisionUpdates(context, sources, accounts, fixture.runner, fixture.authority)
                service.apply(id, update, grants)
                assertEquals(memberships, sources.installedSources().single().preferences.groupIds)
                sources.rotateAccount(id)
                assertEquals(memberships, sources.installedSources().single().preferences.groupIds)
                sources.remove(id)
                sources.activate(sources.definitions.list().single().reference(), grants)
                assertTrue(sources.installedSources().single().preferences.groupIds.isEmpty())
                assertEquals(groups, sources.sourceGroups())
            } finally { sources.stop() }
        }
    }

    @Test fun batchUpdatesPreserveUntouchedMembershipsAndSourcePreferences() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority,
                SourceSessionManager(fixture.authority), fixture.runner)
            try {
                val ids = listOf("one", "two").map { sources.activate(commit(sources, fixture.raw(it)), emptyList()) }
                sources.createGroup("Reading", setOf(ids.first()))
                val first = sources.sourceGroups().single().id
                sources.createGroup("Favorites")
                val second = sources.sourceGroups().last().id
                sources.setPreferences(ids.first(), enabled = false, discoveryVisible = true)
                val before = sources.installedSources().map { it.preferences }
                sources.updateGroups(ids.toSet(), setOf(second), emptySet())
                assertEquals(listOf(setOf(first, second), setOf(second)), sources.installedSources().map { it.preferences.groupIds })
                sources.updateGroups(ids.toSet(), emptySet(), setOf(second))
                assertEquals(before, sources.installedSources().map { it.preferences })
            } finally { sources.stop() }
        }
    }

    @Test fun creatingGroupCommitsPendingMembershipChangesAndPreservesUntouchedSources() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
            try {
                val ids = listOf("one", "two", "three").map { sources.activate(commit(sources, fixture.raw(it)), emptyList()) }
                val members = ids.take(2).toSet()
                sources.createGroup("Reading", members)
                val removed = sources.sourceGroups().last().id
                sources.createGroup("Favorites", setOf(ids.first()))
                val untouched = sources.sourceGroups().last().id
                sources.createGroup("Shared")
                val added = sources.sourceGroups().last().id
                sources.setPreferences(ids.first(), enabled = false, discoveryVisible = true)
                val before = sources.installedSources()
                sources.createGroup("Weekend", members, setOf(added), setOf(removed))
                val created = sources.sourceGroups().last().id
                val expected = listOf(setOf(untouched, added, created), setOf(added, created), emptySet())
                val after = sources.installedSources()
                assertEquals(before.zip(expected).map { (source, memberships) ->
                    source.copy(preferences = source.preferences.copy(groupIds = memberships))
                }, after)
                val groups = sources.sourceGroups()
                assertEquals(listOf("Reading", "Favorites", "Shared", "Weekend"), groups.map { it.name })
                sources.stop()
                sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                assertEquals(after, sources.installedSources())
                assertEquals(groups, sources.sourceGroups())
            } finally { sources.stop() }
        }
    }

    @Test fun failedGroupWriteLeavesMembershipRuntimeAndSavedGroupsIntact() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(context, registry, fixture.authority,
                SourceSessionManager(fixture.authority), fixture.runner)
            try {
                val id = sources.activate(commit(sources, fixture.raw()), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                sources.createGroup("Reading", setOf(id))
                val before = sources.installedSources()
                val groups = sources.sourceGroups()
                val grouping = sources.grouping.value
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val base = File(context.filesDir, "rule-sources/active.json")
                val saved = base.readBytes()
                val backup = File(context.filesDir, "rule-sources/active.json.bak").apply { writeBytes(saved) }
                check(base.delete()); check(base.mkdir()); File(base, "block").writeText("fixture")
                try {
                    assertTrue(runCatching { sources.deleteGroup(groups.single().id) }.isFailure)
                    assertTrue(runCatching { sources.createGroup("New", setOf(id), removed = setOf(groups.single().id)) }.isFailure)
                } finally { base.deleteRecursively(); base.writeBytes(saved); backup.delete() }
                assertEquals(before, sources.installedSources())
                assertEquals(groups, sources.sourceGroups())
                assertEquals(grouping, sources.grouping.value)
                assertSame(runtime, (registry.resolve(id) as SourceResolution.Ready).runtime)
                assertEquals("Same title", runtime.getBookInformation(fixture.server.url("/book/one").toString()).get()!!.title)
            } finally { sources.stop() }
        }
    }

    @Test fun invalidSourceSnapshotDoesNotPartiallyRestoreGroupsOrOverwriteSavedData() = runBlocking {
        val context = host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
            try {
                sources.activate(commit(sources, fixture.raw()), emptyList())
                sources.createGroup("Reading")
                val file = File(context.filesDir, "rule-sources/active.json")
                val snapshot = Json.parseToJsonElement(file.readText()).jsonObject
                val row = snapshot.getValue("sources").jsonArray.single()
                val damaged = JsonObject(snapshot + ("sources" to JsonArray(listOf(row, row)))).toString()
                sources.stop()
                file.writeText(damaged)
                sources = ImportedRuleSources(context, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                assertTrue(sources.installedSources().isEmpty())
                assertTrue(sources.sourceGroups().isEmpty())
                assertTrue(sources.restorationFailed)
                assertTrue(runCatching { sources.createGroup("New") }.isFailure)
                assertEquals(damaged, file.readText())
            } finally { sources.stop() }
        }
    }
}
