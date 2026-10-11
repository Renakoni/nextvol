package indi.renakoni.nextvol.data.web.rules

import android.app.Application
import android.content.ContextWrapper
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import hnovel.content.RuleSourceFixture
import hnovel.content.RuleTaskRunner
import hnovel.imports.*
import hnovel.network.NetworkGrant
import indi.renakoni.nextvol.data.web.*
import kotlinx.coroutines.*
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
class SourceRevisionUpdatesTest {
    private class Host : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val root = Files.createTempDirectory("source-update").toFile()
        override fun getFilesDir() = root
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    private fun candidate(sources: ImportedRuleSources, raw: JsonObject): SourceDefinition {
        val preview = sources.importer.preview(raw.toString())
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        val row = preview.candidates.single()
        val commit = sources.importer.commit(preview, listOf(ImportSelection(0,
            row.existing?.let(ImportDecision::Replace) ?: ImportDecision.Add)))
        assertNull(commit.error)
        assertNull(commit.items.single().error)
        return sources.definitions.list().single()
    }

    @Test fun missingHomepagePermissionReportsTheSiteAndAdditionalGrantsSurviveRestart() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                val extra = NetworkGrant("https://new.example:443")
                val snapshot = File(host.filesDir, "rule-sources/active.json").readBytes()
                val failure = runCatching { updates.updatePermissions(id, listOf(extra)) }.exceptionOrNull() as RevisionException
                assertEquals(RevisionError.PermissionRequired, failure.code)
                assertEquals(hnovel.network.sourcePermissionOrigin(first.importKey), failure.requiredOrigin)
                assertArrayEquals(snapshot, File(host.filesDir, "rule-sources/active.json").readBytes())
                updates.updatePermissions(id, grants + extra)
                sources.stop()
                sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                assertEquals(grants + extra, sources.installedSources().single().origins)
                assertEquals(first, sources.installedSources().single().definition)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun domainEditPreservesIdentityAndReadsDiscoveryDetailsDirectoryAndContentFromNewSite() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val oldOrigin = "http://old.example:${fixture.server.port}"
                val raw = JsonObject(fixture.raw() + mapOf(
                    "bookSourceUrl" to JsonPrimitive("$oldOrigin/source"),
                    "searchUrl" to JsonPrimitive("$oldOrigin/search?q={{key}}"),
                    "exploreUrl" to JsonPrimitive("Latest::$oldOrigin/search"),
                    "header" to JsonPrimitive("""{"Referer":"$oldOrigin/","X-Source":"A"}""")))
                val first = candidate(sources, raw)
                val originalGrants = listOf(NetworkGrant(oldOrigin))
                val id = sources.activate(first.reference(), originalGrants)
                sources.createGroup("Keep group", setOf(id))
                sources.saveVariable(id, "keep configuration")
                val before = sources.installedSources().single()
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val preview = SourceDefinitionEdits.previewDomain(first.rawJson, "old.example", "127.0.0.1")
                assertEquals(first, sources.installedSources().single().definition)
                assertEquals(0, fixture.server.requestCount)
                val grants = listOf(NetworkGrant("http://127.0.0.1:${fixture.server.port}", true))
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                updates.edit(id, before, preview.rawJson, grants)
                assertFalse(runtime.isAvailable)
                val edited = sources.installedSources().single()
                assertEquals(first.sourceId, edited.definition.sourceId)
                assertEquals(first.importKey, edited.definition.importKey)
                assertEquals(before.preferences, edited.preferences)
                assertEquals("keep configuration", sources.storedSettings(id).variable)
                assertEquals(first, sources.definitions.list().single())
                assertEquals(grants, edited.origins)
                val rules = sources.loginTarget(id).rules
                val base = "http://127.0.0.1:${fixture.server.port}"
                val book = rules.discovery("$base/search").single()
                assertEquals("$base/book/one", book.id)
                assertEquals(book.id, rules.search("same").single().id)
                assertEquals("Same title", rules.information(book.id).title)
                val chapter = rules.directory(book.id).first { !it.isVolume }
                assertEquals("$base/c/1", chapter.id)
                assertTrue(rules.content(book.id, chapter.id).parts.any { it.text == "A first" })
                repeat(fixture.server.requestCount) {
                    val request = fixture.server.takeRequest()
                    assertEquals("127.0.0.1:${fixture.server.port}", request.getHeader("Host"))
                    assertEquals("$base/", request.getHeader("Referer"))
                }
                sources.stop()
                sources = ImportedRuleSources(host, WebSourceRegistry(fixture.authority), fixture.authority, accounts, fixture.runner)
                assertEquals(edited, sources.installedSources().single())
                val restoredUpdates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                restoredUpdates.rollback(id, originalGrants)
                assertEquals(first, sources.installedSources().single().definition)
                restoredUpdates.edit(id, sources.installedSources().single(), preview.rawJson, grants)
                assertTrue(sources.installedSources().single().definition.revision > edited.definition.revision)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun rejectedEditsLeaveTheDefinitionGrantsAndRuntimeIntactAndStaleDraftsCannotOverwrite() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val before = sources.installedSources().single()
                val oldRuntime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val snapshot = File(host.filesDir, "rule-sources/active.json").readBytes()
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                val brokenScript = JsonObject(fixture.raw() + ("jsLib" to JsonPrimitive("throw new Error('private-secret')"))).toString()
                val renamed = JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("Edited"))).toString()
                for ((text, approved) in listOf("{" to grants, JsonObject(fixture.raw() - "ruleContent").toString() to grants,
                        brokenScript to grants, renamed to listOf(NetworkGrant("https://unapproved.example")))) {
                    assertTrue(runCatching { updates.edit(id, before, text, approved) }.isFailure)
                    assertArrayEquals(snapshot, File(host.filesDir, "rule-sources/active.json").readBytes())
                    assertEquals(first, sources.definitions.list().single())
                    assertSame(oldRuntime, (registry.resolve(id) as SourceResolution.Ready).runtime)
                }
                updates.updatePermissions(id, grants + NetworkGrant("https://extra.example"))
                assertEquals(RevisionError.Stale, (runCatching { updates.edit(id, before, renamed, grants) }
                    .exceptionOrNull() as RevisionException).code)
                val current = sources.installedSources().single()
                updates.edit(id, current, renamed, current.origins)
                assertEquals(RevisionError.Stale, (runCatching { updates.edit(id, current, first.rawJson, grants) }
                    .exceptionOrNull() as RevisionException).code)
                assertEquals("Edited", sources.installedSources().single().definition.displayName)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun cancellingAnEditDuringInitializationKeepsTheInstalledSnapshot() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            val entered = CompletableDeferred<Unit>()
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val before = sources.installedSources().single()
                val snapshot = File(host.filesDir, "rule-sources/active.json").readBytes()
                fixture.afterRun = { entered.complete(Unit); awaitCancellation() }
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                val changed = JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("Cancelled"))).toString()
                val pending = launch { updates.edit(id, before, changed, grants) }
                withTimeout(5000) { entered.await() }
                pending.cancelAndJoin()
                assertEquals(before, sources.installedSources().single())
                assertArrayEquals(snapshot, File(host.filesDir, "rule-sources/active.json").readBytes())
                assertEquals(first, sources.definitions.list().single())
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun approvingACoverOriginRetiresOnlyItsOwnerAndClearsOldRequests() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture -> okhttp3.mockwebserver.MockWebServer().use { cdn ->
            cdn.start()
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val a = sources.activate(candidate(sources, fixture.raw()).reference(), grants)
                val rawB = fixture.raw("B")
                val preview = sources.importer.preview(rawB.toString())
                val committed = sources.importer.commit(preview, listOf(ImportSelection(0, ImportDecision.Add)))
                val b = sources.activate(committed.items.single().reference!!, grants)
                val oldA = (registry.resolve(a) as SourceResolution.Ready).runtime
                val runtimeB = (registry.resolve(b) as SourceResolution.Ready).runtime
                val cover = cdn.url("/cover.png?signature=secret").toString()
                val failure = oldA.imageBytes("book", cover, true).getError()!!.throwable as hnovel.content.SourceContentException
                assertEquals(hnovel.network.ResourceKind.Image, failure.denial!!.kind)
                assertEquals(hnovel.network.sourceOrigin(cover), failure.denial!!.origin)
                assertEquals(0, cdn.requestCount)
                assertEquals(1, sources.installedSources().single { ImportedRuleSources.id(it.definition) == a }.deniedOrigins.size)
                assertTrue(sources.installedSources().single { ImportedRuleSources.id(it.definition) == b }.deniedOrigins.isEmpty())
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                updates.updatePermissions(a, grants + NetworkGrant(cdn.url("/").toString(), true))
                assertFalse(oldA.isAvailable); assertTrue(runtimeB.isAvailable)
                assertTrue(sources.installedSources().single { ImportedRuleSources.id(it.definition) == a }.deniedOrigins.isEmpty())
                cdn.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
                val next = (registry.resolve(a) as SourceResolution.Ready).runtime
                assertArrayEquals(byteArrayOf(3, 2, 1), next.imageBytes("book", cover, true).get()!!)
                assertTrue(runtimeB.imageBytes("book", cover, true).isErr)
                assertEquals(1, cdn.requestCount)
                sources.rotateAccount(b)
                assertTrue(sources.installedSources().single { ImportedRuleSources.id(it.definition) == b }.deniedOrigins.isEmpty())
            } finally { sources.stop(); host.root.deleteRecursively() }
        } }
    }

    @Test fun updateAndRollbackPreserveIdentityRetireOldRuntimeAndRestoreFromOneSnapshot() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            var registry = WebSourceRegistry(fixture.authority)
            var sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val runtime = (registry.resolve(id) as SourceResolution.Ready).runtime
                val book = fixture.server.url("/book/one").toString()
                assertEquals("Same title", runtime.getBookInformation(book).get()!!.title)
                val next = candidate(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("Updated"))))
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                updates.apply(id, next.reference(), grants)
                assertFalse(runtime.isAvailable)
                val changed = (registry.resolve(id) as SourceResolution.Ready).runtime
                assertEquals(first.sourceId, next.sourceId)
                assertEquals(next.contentDigest, changed.metadata.revision)
                assertEquals(book, changed.getBookInformation(book).get()!!.id)
                updates.rollback(id, grants)
                assertFalse(changed.isAvailable)
                assertEquals(first.contentDigest, sources.installedSources().single().definition.contentDigest)
                sources.stop()
                registry = WebSourceRegistry(fixture.authority)
                sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
                sources.restore()
                assertEquals(first.contentDigest, (registry.resolve(id) as SourceResolution.Ready).runtime.metadata.revision)
                assertEquals(next.contentDigest, sources.installedSources().single().previous!!.contentDigest)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun updatesAllowBookInfoToReuseTheNameFromSearchOrDiscovery() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val raw = fixture.raw()
                val next = candidate(sources, JsonObject(raw + ("ruleBookInfo" to JsonObject(raw.getValue("ruleBookInfo").jsonObject - "name"))))
                SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority).apply(id, next.reference(), grants)
                val rules = sources.loginTarget(id).rules
                val book = rules.search("same").first()
                assertEquals("Same title", rules.information(book.id).title)
                assertEquals(next.contentDigest, sources.installedSources().single().definition.contentDigest)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun initializationPermissionAndDurableCommitFailuresLeaveOldRuntimeUsable() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val old = (registry.resolve(id) as SourceResolution.Ready).runtime
                val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                val invalid = candidate(sources, JsonObject(fixture.raw() + ("jsLib" to JsonPrimitive("throw new Error('private-secret');"))))
                val failure = runCatching { updates.apply(id, invalid.reference(), grants) }.exceptionOrNull() as RevisionException
                assertEquals(RevisionError.InitializationFailed, failure.code)
                assertFalse(failure.toString().contains("private-secret"))
                val next = candidate(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("New"))))
                assertTrue(runCatching { updates.apply(id, next.reference(), listOf(NetworkGrant("https://unapproved.test/"))) }.isFailure)
                // API 27 AtomicFile writes the base after keeping a .bak, not a .new file.
                val base = File(host.filesDir, "rule-sources/active.json")
                val saved = base.readBytes()
                val backup = File(host.filesDir, "rule-sources/active.json.bak").apply { writeBytes(saved) }
                check(base.delete()); check(base.mkdir()); File(base, "block").writeText("test")
                assertTrue(runCatching { updates.apply(id, next.reference(), grants) }.isFailure)
                base.deleteRecursively(); base.writeBytes(saved); backup.delete()
                assertSame(old, (registry.resolve(id) as SourceResolution.Ready).runtime)
                assertEquals(first.contentDigest, sources.installedSources().single().definition.contentDigest)
                assertEquals("Same title", old.getBookInformation(fixture.server.url("/book/one").toString()).get()!!.title)
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun sourceRemovalDuringValidationCannotBeUndoneByLateUpdate() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runner = RuleTaskRunner { identity, task, limits, broker ->
                fixture.runner.execute(identity, task, limits, broker).also { entered.complete(Unit); release.await() }
            }
            try {
                val first = candidate(sources, fixture.raw())
                val grants = listOf(NetworkGrant(fixture.server.url("/").toString(), true))
                val id = sources.activate(first.reference(), grants)
                val next = candidate(sources, JsonObject(fixture.raw() + ("bookSourceName" to JsonPrimitive("New"))))
                val updates = SourceRevisionUpdates(host, sources, accounts, runner, fixture.authority)
                val pending = async { runCatching { updates.apply(id, next.reference(), grants) } }
                withTimeout(5000) { entered.await() }
                sources.remove(id)
                release.complete(Unit)
                assertTrue(pending.await().isFailure)
                assertTrue(registry.resolve(id) is SourceResolution.Missing)
                assertTrue(sources.installedSources().isEmpty())
            } finally { release.complete(Unit); sources.stop(); host.root.deleteRecursively() }
        }
    }

    @Test fun downloadCheckOnlyPreviewsAndDownloadFailureKeepsTheInstalledRevision() = runBlocking {
        val host = Host()
        RuleSourceFixture().use { fixture ->
            val accounts = SourceSessionManager(fixture.authority)
            val registry = WebSourceRegistry(fixture.authority)
            val sources = ImportedRuleSources(host, registry, fixture.authority, accounts, fixture.runner)
            try {
                okhttp3.mockwebserver.MockWebServer().use { server ->
                    server.start()
                    val grant = NetworkGrant(server.url("/").toString(), true)
                    hnovel.network.SourceBroker(File(host.cacheDir, "import").toPath()).use { broker ->
                        val session = broker.open(hnovel.network.SourceScope("download", "fixture", LEGADO_PROFILE), listOf(grant))
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(fixture.raw().toString()))
                        val initial = sources.importer.previewUrl(server.url("/sources.json").toString(), session)
                        assertNull(sources.importer.commit(initial, listOf(ImportSelection(0, ImportDecision.Add))).error)
                        val first = sources.definitions.list().single()
                        val id = sources.activate(first.reference(), listOf(NetworkGrant(fixture.server.url("/").toString(), true)))
                        val updates = SourceRevisionUpdates(host, sources, accounts, fixture.runner, fixture.authority)
                        val collection = JsonArray(listOf(fixture.raw(), buildJsonObject {
                            put("bookSourceUrl", "https://audio.invalid/"); put("bookSourceType", 1)
                            put("bookSourceComment", "x".repeat(4 * 1024 * 1024))
                        }))
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(collection.toString()))
                        val unchanged = updates.check(id, grant)
                        assertTrue(unchanged.unchanged)
                        assertEquals(ImportCode.UnsupportedType, unchanged.preview.issues.single().code)
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(JsonObject(fixture.raw() +
                            ("jsLib" to JsonPrimitive("throw new Error('must not execute at check');"))).toString()))
                        val check = updates.check(id, grant)
                        assertFalse(check.unchanged)
                        assertTrue(check.preview.issues.isEmpty())
                        assertEquals(first, sources.installedSources().single().definition)
                        assertEquals(first, sources.definitions.list().single())
                        assertEquals(0, fixture.documents.get())
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
                        assertEquals(ImportCode.DownloadFailed, updates.check(id, grant).preview.issues.single().code)
                        assertEquals(first, sources.installedSources().single().definition)
                    }
                }
            } finally { sources.stop(); host.root.deleteRecursively() }
        }
    }
}
