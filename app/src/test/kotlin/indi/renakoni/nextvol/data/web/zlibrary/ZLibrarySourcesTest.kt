package indi.renakoni.nextvol.data.web.zlibrary

import android.app.Application
import android.content.ContextWrapper
import hnovel.network.StorageCipher
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
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
class ZLibrarySourcesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun host() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val root = folder.newFolder()
        override fun getFilesDir() = root
    }

    @Test fun builtinRegistrationIsMetadataOnlyAndMirrorDisableRestartKeepItsIdentity() = runBlocking {
        val context = host()
        var registry = WebSourceRegistry()
        var sources = ZLibrarySources(context, registry, StorageCipher.Plain)
        try {
            sources.restore()
            val id = ZLibrarySources.ID
            assertEquals(SourceStatus.Registered, registry.sources.value.single().status)
            assertFalse(File(context.filesDir, "native-sources/zlibrary/runtime").exists())
            val old = (registry.resolve(id) as SourceResolution.Ready).runtime
            assertEquals(setOf(SourceCapability.Search, SourceCapability.BookInformation, SourceCapability.Images), old.metadata.capabilities)
            assertNull(old.discovery)
            val book = SourceBookId(id, "123/abcdef")
            sources.update(sources.state.value.settings.copy(origin = "https://z-lib.fo"))
            assertFalse(old.isAvailable)
            assertEquals("https://z-lib.fo:443", sources.state.value.settings.origin)
            sources.update(sources.state.value.settings.copy(enabled = false))
            assertTrue(registry.resolve(id) is SourceResolution.Missing)
            sources.stop()
            registry = WebSourceRegistry()
            sources = ZLibrarySources(context, registry, StorageCipher.Plain)
            sources.restore()
            assertFalse(sources.state.value.settings.enabled)
            assertEquals("https://z-lib.fo:443", sources.state.value.settings.origin)
            assertTrue(registry.sources.value.isEmpty())
            sources.update(sources.state.value.settings.copy(enabled = true))
            val restored = (registry.resolve(id) as SourceResolution.Ready).runtime
            assertEquals(book.storageKey, SourceBookId(restored.id, book.remoteId).storageKey)
        } finally { sources.stop() }
    }

    @Test fun mirrorChangeRetiresOnlyItsOwnPendingWorkAndRevokedOriginsAreNotReapproved() = runBlocking {
        val registry = WebSourceRegistry()
        val sources = ZLibrarySources(host(), registry, StorageCipher.Plain)
        val other = Identifier("fixture", "other")
        registry.register(object : WebBookDataSource by EmptyWebDataSource { override val id = other },
            SourceMetadata(WebDataSourceItem(other, "Other", "fixture"), setOf(SourceCapability.Search)))
        try {
            sources.restore()
            val old = (registry.resolve(ZLibrarySources.ID) as SourceResolution.Ready).runtime
            val independent = (registry.resolve(other) as SourceResolution.Ready).runtime
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val pending = async { runCatching { old.execute {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            } } }
            entered.await()
            val settings = sources.state.value.settings
            sources.update(settings.copy(origins = settings.origins.filterNot { it == settings.origin }))
            withTimeout(5000) { cancelled.await(); pending.await() }
            assertTrue(sources.state.value.settings.enabled)
            assertFalse(sources.state.value.settings.available)
            assertTrue(registry.resolve(ZLibrarySources.ID) is SourceResolution.Missing)
            sources.update(sources.state.value.settings.copy(enabled = false))
            sources.update(sources.state.value.settings.copy(enabled = true))
            assertTrue(registry.resolve(ZLibrarySources.ID) is SourceResolution.Missing)
            assertTrue(independent.isAvailable)
            assertSame(independent, (registry.resolve(other) as SourceResolution.Ready).runtime)
        } finally { sources.stop(); registry.unregister(other) }
    }

    @Test fun validationAndSnapshotFailuresLeaveTheCurrentRuntimeUsable() = runBlocking {
        val context = host()
        val registry = WebSourceRegistry()
        val sources = ZLibrarySources(context, registry, StorageCipher.Plain)
        try {
            sources.restore()
            sources.update(sources.state.value.settings.copy(origin = "https://z-lib.fo/"))
            val before = sources.state.value.settings
            val runtime = (registry.resolve(ZLibrarySources.ID) as SourceResolution.Ready).runtime
            for (invalid in listOf("https://user:password@evil.invalid/", "https://evil.invalid/path", "https://evil.invalid/?token=value")) {
                assertTrue(runCatching { sources.update(before.copy(origin = invalid)) }.isFailure)
            }
            val base = File(context.filesDir, "native-sources/zlibrary/settings.json")
            val saved = base.readBytes()
            val backup = File(base.path + ".bak").apply { writeBytes(saved) }
            check(base.delete()); check(base.mkdir()); File(base, "block").writeText("fixture")
            try { assertTrue(runCatching { sources.update(before.copy(enabled = false)) }.isFailure) }
            finally { base.deleteRecursively(); base.writeBytes(saved); backup.delete() }
            assertEquals(before, sources.state.value.settings)
            assertSame(runtime, (registry.resolve(ZLibrarySources.ID) as SourceResolution.Ready).runtime)
            assertTrue(runtime.isAvailable)
        } finally { sources.stop() }
    }

    @Test fun corruptSettingsStartDisabledAndAnExplicitSaveCanRecover() = runBlocking {
        val context = host()
        val file = File(context.filesDir, "native-sources/zlibrary/settings.json")
        file.parentFile!!.mkdirs(); file.writeText("{broken")
        val registry = WebSourceRegistry()
        val sources = ZLibrarySources(context, registry, StorageCipher.Plain)
        try {
            sources.restore()
            assertTrue(sources.state.value.restorationFailed)
            assertFalse(sources.state.value.settings.enabled)
            assertTrue(registry.sources.value.isEmpty())
            assertEquals("{broken", file.readText())
            sources.update(ZLibrarySettings())
            assertFalse(sources.state.value.restorationFailed)
            assertTrue(registry.resolve(ZLibrarySources.ID) is SourceResolution.Ready)
        } finally { sources.stop() }
    }
}
