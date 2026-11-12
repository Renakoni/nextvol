package indi.renakoni.nextvol.data.book

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import indi.renakoni.nextvol.data.web.SourceResolution
import indi.renakoni.nextvol.data.web.SourceRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookInformationFlowTest {
    private val fixture = BookRepositoryFixture()
    private val events = mutableListOf<String>()
    private val local = BookInformation(
        id = BookIdentity.bookKey("book"), title = "local", author = "author", description = "description",
        publishingHouse = "publisher", wordCount = WordCount(100),
        lastUpdated = LocalDateTime.of(2026, 9, 1, 0, 0), isComplete = false,
    )
    private val remote = local.copy(id = "book", title = "remote", lastUpdated = local.lastUpdated.plusDays(1))
    private val error = WebRequestError("offline", "request failed")

    @Before
    fun setUp() {
        coEvery { fixture.local.getBookInformation(BookIdentity.bookKey("book")) } answers { events += "local"; local }
        coEvery { fixture.remote.getBookInformation("book", any()) } answers { events += "remote"; Ok(remote) }
        coEvery { fixture.local.updateBookInformation(any()) } answers { events += "store" }
        coEvery { fixture.bookshelves.getBookshelfBookMetadata(BookIdentity.bookKey("book")) } returns mockk {
            every { lastUpdate } returns local.lastUpdated
            every { bookShelfIds } returns listOf(1)
        }
        coEvery { fixture.bookshelves.updateBookshelfBookMetadataLastUpdateTime(BookIdentity.bookKey("book"), remote.lastUpdated) } answers {
            events += "metadata"
        }
        coEvery { fixture.bookshelves.addUpdatedBooksIntoBookShelf(1, BookIdentity.bookKey("book")) } answers { events += "mark-updated" }
        every { fixture.text.processBookInformation(any()) } answers {
            val book = firstArg<() -> BookInformation>()()
            events += "process:${book.title}"
            book.copy(title = "processed:${book.title}")
        }
    }

    @Test
    fun nativeDisplayMetadataDoesNotResolveOrModifyItsSource() = runTest {
        assertEquals(local, fixture.repository().bookInformationForDisplay(local))
        coVerify(exactly = 0) { fixture.registry.resolve(any()) }
        coVerify(exactly = 0) { fixture.local.updateBookInformation(any()) }
    }

    @Test
    fun ruleDisplayMetadataDoesNotOverwriteTheStoredObservationMarker() = runTest {
        val book = SourceBookId(Identifier("rules", "metadata"), "book")
        val information = local.copy(id = book.storageKey, description = "<p>&lt;b&gt;literal&lt;/b&gt; &amp;amp;</p>")
        val normalized = information.copy(description = "<b>literal</b> &amp;")
        val display = normalized.copy(lastUpdated = LocalDateTime.of(2020, 1, 1, 0, 0), wordCount = WordCount(12300))
        val runtime = mockk<SourceRuntime> {
            coEvery { bookInformationForDisplay(book.remoteId, normalized) } returns display
        }
        coEvery { fixture.registry.resolve(book.sourceId) } returns SourceResolution.Ready(runtime)
        assertEquals(display, fixture.repository().bookInformationForDisplay(information))
        coVerify(exactly = 0) { fixture.local.updateBookInformation(any()) }
        coVerify(exactly = 0) { fixture.bookshelves.updateBookshelfBookMetadataLastUpdateTime(any(), any()) }
        coVerify(exactly = 0) { runtime.getBookInformation(any(), any(), any()) }
    }

    @Test
    fun missingRuleSourceHidesTheObservationDateButRetainsTheCachedBook() = runTest {
        val book = SourceBookId(Identifier("rules", "missing"), "book")
        val information = local.copy(id = book.storageKey, description = "<br>简介")
        coEvery { fixture.registry.resolve(book.sourceId) } returns SourceResolution.Missing(book.sourceId)
        assertEquals(information.copy(description = "简介", lastUpdated = UNKNOWN_BOOK_UPDATE_TIME),
            fixture.repository().bookInformationForDisplay(information))
        coVerify(exactly = 0) { fixture.local.updateBookInformation(any()) }
    }

    @Test
    fun unreadableOptionalMetadataRetainsTheBookWithoutExposingObservationTime() = runTest {
        for (failure in listOf(java.io.IOException("unreadable"),
            kotlinx.serialization.SerializationException("invalid snapshot"),
            hnovel.content.SourceContentException(hnovel.content.ContentError.Storage, "bookState"))) {
            val book = SourceBookId(Identifier("rules", "metadata"), "book")
            val information = local.copy(id = book.storageKey)
            val runtime = mockk<SourceRuntime> {
                every { isAvailable } returns true
                coEvery { bookInformationForDisplay(book.remoteId, information) } throws failure
            }
            coEvery { fixture.registry.resolve(book.sourceId) } returns SourceResolution.Ready(runtime)
            assertEquals(information.copy(lastUpdated = UNKNOWN_BOOK_UPDATE_TIME),
                fixture.repository().bookInformationForDisplay(information))
            coVerify(exactly = 0) { fixture.local.updateBookInformation(any()) }
            coVerify(exactly = 0) { runtime.getBookInformation(any(), any(), any()) }
        }
    }

    @Test
    fun optionalMetadataDoesNotSwallowCancellation() = runTest {
        val book = SourceBookId(Identifier("rules", "metadata"), "book")
        val information = local.copy(id = book.storageKey)
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        val runtime = mockk<SourceRuntime> {
            every { isAvailable } returns true
            coEvery { bookInformationForDisplay(book.remoteId, information) } throws cancelled
        }
        coEvery { fixture.registry.resolve(book.sourceId) } returns SourceResolution.Ready(runtime)
        assertEquals(cancelled, runCatching { fixture.repository().bookInformationForDisplay(information) }.exceptionOrNull())
    }

    @Test
    fun cachedThenRemoteSuccessKeepsProcessingPersistenceAndBookshelfUpdateOrder() = runTest {
        val actual = mutableListOf<Result<BookInformation, WebRequestError>>()
        fixture.repository().getBookInformationFlow("book", WebDataSourcePriority.High).collect {
            actual += it
            events += "emit"
        }
        assertEquals(listOf(Ok(local.copy(title = "processed:local")), Ok(remote.copy(id = BookIdentity.bookKey("book"), title = "processed:remote"))), actual)
        assertEquals(
            listOf("local", "process:local", "emit", "remote", "store", "metadata", "mark-updated", "process:remote", "emit"),
            events,
        )
        coVerify(exactly = 1) { fixture.remote.getBookInformation("book", WebDataSourcePriority.High) }
        coVerify(exactly = 1) { fixture.local.updateBookInformation(remote.copy(id = BookIdentity.bookKey("book"))) }
    }

    @Test
    fun remoteFailureRetainsProcessedCacheWithoutPersistenceOrBookshelfUpdates() = runTest {
        coEvery { fixture.remote.getBookInformation(any(), any()) } returns Err(error)
        assertEquals(
            listOf(Ok(local.copy(title = "processed:local"))),
            fixture.repository().getBookInformationFlow("book").toList(),
        )
        coVerify(exactly = 1) { fixture.remote.getBookInformation("book", WebDataSourcePriority.Default) }
        coVerify(exactly = 0) { fixture.local.updateBookInformation(any()) }
        coVerify(exactly = 0) { fixture.bookshelves.getBookshelfBookMetadata(any()) }
        coVerify(exactly = 0) { fixture.bookshelves.updateBookshelfBookMetadataLastUpdateTime(any(), any()) }
        coVerify(exactly = 0) { fixture.bookshelves.addUpdatedBooksIntoBookShelf(any(), any()) }
    }

    @Test
    fun missingCacheEmitsTheRemoteSuccessOrError() = runTest {
        coEvery { fixture.local.getBookInformation(BookIdentity.bookKey("book")) } returns null
        val flow = fixture.repository().getBookInformationFlow("book")
        assertEquals(listOf(Ok(remote.copy(id = BookIdentity.bookKey("book"), title = "processed:remote"))), flow.toList())
        coEvery { fixture.remote.getBookInformation(any(), any()) } returns Err(error)
        assertEquals(listOf(Err(error)), flow.toList())
        coVerify(exactly = 1) { fixture.local.updateBookInformation(remote.copy(id = BookIdentity.bookKey("book"))) }
    }

    @Test
    fun flowIsColdAndTakingCacheDoesNotRequestRemoteData() = runTest {
        val flow = fixture.repository().getBookInformationFlow("book")
        assertTrue(events.isEmpty())
        assertEquals(Ok(local.copy(title = "processed:local")), flow.first())
        coVerify(exactly = 0) { fixture.remote.getBookInformation(any(), any()) }
        coVerify(exactly = 0) { fixture.local.updateBookInformation(any()) }
    }

    @Test
    fun recollectionResolvesTheSameSourceAgainAndDoesNotRetainAPreviousCacheHit() = runTest {
        val flow = fixture.repository().getBookInformationFlow("book")
        assertEquals(2, flow.toList().size)
        val replacement = mockk<indi.renakoni.nextvol.data.web.proxy.ProxyWebBookDataSource>()
        coEvery { replacement.getBookInformation("book", WebDataSourcePriority.Default) } returns Err(error)
        fixture.activeRemote = replacement
        assertEquals(listOf(Ok(local.copy(title = "processed:local"))), flow.toList())
        coEvery { fixture.local.getBookInformation(BookIdentity.bookKey("book")) } returns null
        assertEquals(listOf(Err(error)), flow.toList())
        coVerify(exactly = 3) { fixture.local.getBookInformation(BookIdentity.bookKey("book")) }
        coVerify(exactly = 2) { replacement.getBookInformation("book", WebDataSourcePriority.Default) }
    }
}
