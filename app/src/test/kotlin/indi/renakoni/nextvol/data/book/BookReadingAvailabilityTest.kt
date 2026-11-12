package indi.renakoni.nextvol.data.book

import android.app.Application
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import io.mockk.coEvery
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookReadingAvailabilityTest {
    @Test fun metadataSourceHasNoReadingWhileDisabledCachedNovelsRemainReadable() = runBlocking {
        val registry = WebSourceRegistry()
        val local = mockk<LocalBookDataSource>()
        val repository = BookRepository(local, mockk(), mockk(), mockk(), mockk(), mockk(), registry, mockk(), mockk(), mockk())
        val book = SourceBookId(ZLibrarySources.ID, "1/abcdef")
        coEvery { local.getBookVolumes(book.storageKey) } returns null
        val provider = object : WebBookDataSource by EmptyWebDataSource { override val id = book.sourceId }
        registry.register(provider, ZLibrarySources.METADATA)
        try {
            val metadata = repository.readingAvailability(book.storageKey).first()
            assertTrue(metadata.metadataOnly)
            assertFalse(metadata.available)
            assertFalse(metadata.online)
            registry.unregister(book.sourceId)
            assertFalse(repository.readingAvailability(book.storageKey).first().available)
            registry.register(provider, ZLibrarySources.METADATA.copy(capabilities = setOf(SourceCapability.Directory)))
            val directory = repository.readingAvailability(book.storageKey).first()
            assertTrue(directory.directoryAvailable)
            assertFalse(directory.available)
            registry.unregister(book.sourceId)
            registry.register(provider, ZLibrarySources.METADATA.copy(capabilities = setOf(SourceCapability.Directory, SourceCapability.ChapterContent)))
            assertTrue(repository.readingAvailability(book.storageKey).first().online)
            registry.unregister(book.sourceId)
            coEvery { local.getBookVolumes(book.storageKey) } returns BookVolumes(book.storageKey,
                listOf(Volume("volume", "Volume", listOf(ChapterInformation("chapter", "Saved chapter")))))
            val offline = repository.readingAvailability(book.storageKey).first()
            assertTrue(offline.available)
            assertTrue(offline.local)
            assertFalse(offline.online)
        } finally { registry.unregister(book.sourceId) }
    }
}
