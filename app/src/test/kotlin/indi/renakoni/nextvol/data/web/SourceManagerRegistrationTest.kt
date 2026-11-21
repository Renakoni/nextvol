package indi.renakoni.nextvol.data.web

import android.app.Application
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceManagerRegistrationTest {
    @Test(timeout = 10000)
    fun registrationAndInventoryDoNotInitializeSourcesWhileExplicitResolutionReusesOneRuntime() = runBlocking {
        val manager = WebBookDataSourceManager(WebSourceRegistry())
        val source = BuiltInFixture()
        val other = BuiltInFixture(Identifier("fixture", "other"))
        manager.loadBuiltInSource(source, SourceCategory.Anime)
        manager.registerWebDataSource(other, WebDataSourceItem(other.id, "Other", "fixture"))
        try {
            assertEquals(2, manager.webDataSourceItems.size)
            assertEquals(0, source.loads.get())
            assertEquals(0, other.loads.get())
            assertTrue(manager.registry.sources.value.first().metadata.builtIn)
            assertEquals(SourceCategory.Anime, manager.registry.sources.value.first().metadata.category)
            assertNull(manager.registry.sources.value.last().metadata.category)
            val runtime = (manager.registry.resolve(source.id) as SourceResolution.Ready).runtime
            assertSame(runtime, (manager.registry.resolve(source.id) as SourceResolution.Ready).runtime)
            assertEquals(1, source.loads.get())
            manager.unregisterWebDataSource(other.id)
            assertTrue(runtime.isAvailable)
            assertEquals(0, other.loads.get())
            withTimeout(3000) { other.closed.await() }
            assertEquals(1, other.closes.get())
        } finally {
            manager.unregisterWebDataSource(source.id)
            manager.unregisterWebDataSource(other.id)
        }
    }

    @WebDataSource("Fixture", "Built-in fixture")
    class BuiltInFixture(override val id: Identifier = Identifier("fixture", "builtin")) :
        WebBookDataSource by EmptyWebDataSource, AutoCloseable {
        override val permits = 2
        val loads = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        override fun onLoad() { loads.incrementAndGet() }
        override fun close() { closes.incrementAndGet(); closed.complete(Unit) }
    }
}
