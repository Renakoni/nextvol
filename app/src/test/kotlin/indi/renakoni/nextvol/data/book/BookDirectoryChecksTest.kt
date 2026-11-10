package indi.renakoni.nextvol.data.book

import android.app.Application
import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.web.*
import io.mockk.coEvery
import io.mockk.every
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookDirectoryChecksTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun missingDirectoryPreparesCachedInformationWithItsForegroundVerificationOwner() = runTest {
        val book = SourceBookId(Identifier("fixture", "directory"), "book")
        val fixture = BookRepositoryFixture()
        val registry = WebSourceRegistry(StandardTestDispatcher(testScheduler), hnovel.execution.ExecutionAuthority())
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("directory-verification-test", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val info = book.bind(BookInformation("book", "Book", author = "", description = "",
            publishingHouse = "", wordCount = WordCount(1), lastUpdated = java.time.LocalDateTime.MIN, isComplete = false))
        var volumes: BookVolumes? = null
        coEvery { fixture.local.getBookInformation(any()) } returns info
        coEvery { fixture.local.updateBookInformation(any()) } returns Unit
        coEvery { fixture.local.getBookVolumes(any()) } answers { volumes }
        coEvery { fixture.local.updateBookVolumes(any()) } answers { volumes = firstArg() }
        val foreground = ForegroundSourceRequest()
        val accepted = CompletableDeferred<Unit>()
        var verificationRequests = 0
        var directoryRequests = 0
        val source = object : WebBookDataSource by EmptyWebDataSource {
            override val id = book.sourceId
            override suspend fun getBookInformation(id: String): com.github.michaelbull.result.Result<BookInformation, WebRequestError> {
                val owner = currentCoroutineContext()[ForegroundSourceRequest]
                if (owner?.allowsInteraction != true) return Err(WebRequestError("verification", "required",
                    kind = WebRequestErrorKind.VerificationRequired))
                assertSame(foreground, owner)
                return owner.ownVerification {
                    verificationRequests++
                    accepted.await()
                    Ok(info.copy(id = "book"))
                }
            }
            override suspend fun getBookVolumes(id: String): com.github.michaelbull.result.Result<BookVolumes, WebRequestError> {
                directoryRequests++
                return Ok(BookVolumes("book", listOf(Volume("volume", "Volume", listOf(ChapterInformation("one", "One"))))))
            }
        }
        registry.register(source, SourceMetadata(WebDataSourceItem(book.sourceId, "Fixture", ""), emptySet()))
        val checks = BookDirectoryChecks(registry, fixture.local, fixture.downloads, prefs) { 1_000L }
        try {
            // Cached metadata refresh is non-interactive; its failed timestamp must not block the missing directory.
            assertTrue(withContext(foreground) { checks.information(book, WebDataSourcePriority.High) }.isErr)
            val directory = async(foreground) { checks.volumes(book, WebDataSourcePriority.High) }
            runCurrent()
            assertEquals(1, verificationRequests)
            assertEquals(0, directoryRequests)
            accepted.complete(Unit); runCurrent()
            assertTrue(directory.await().isOk)
            assertEquals(1, directoryRequests)
            assertNotNull(volumes)
        } finally { accepted.complete(Unit); registry.unregister(book.sourceId); runCurrent() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cachedChecksSharePreparationAndThrottleFailuresAcrossRecreationWithoutATimer() = runTest {
        val book = SourceBookId(Identifier("fixture", "directory"), "book")
        val fixture = BookRepositoryFixture()
        val registry = WebSourceRegistry(StandardTestDispatcher(testScheduler), hnovel.execution.ExecutionAuthority())
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("directory-check-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var now = 1_000L
        var info: BookInformation? = book.bind(BookInformation("book", "Book", author = "", description = "",
            publishingHouse = "", wordCount = WordCount(1), lastUpdated = java.time.LocalDateTime.MIN, isComplete = false))
        fun catalog(id: String) = BookVolumes("book", listOf(Volume("volume", "Volume", listOf(ChapterInformation(id, id)))))
        var volumes: BookVolumes? = book.bind(catalog("old"))
        coEvery { fixture.local.getBookInformation(any()) } answers { info }
        coEvery { fixture.local.updateBookInformation(any()) } answers { info = firstArg() }
        coEvery { fixture.local.getBookVolumes(any()) } answers { volumes }
        coEvery { fixture.local.updateBookVolumes(any()) } answers { volumes = firstArg() }
        every { fixture.text.processBookVolumes(any()) } answers { firstArg<() -> BookVolumes>()() }
        var details = 0
        var directories = 0
        var failed = false
        val gate = CompletableDeferred<Unit>()
        val source = object : WebBookDataSource by EmptyWebDataSource {
            override val id = book.sourceId
            override suspend fun getBookInformation(id: String): com.github.michaelbull.result.Result<BookInformation, WebRequestError> {
                details++
                assertNotEquals(true, currentCoroutineContext()[ForegroundSourceRequest]?.allowsInteraction)
                return Ok(checkNotNull(info).copy(id = "book"))
            }
            override suspend fun getBookVolumes(id: String): com.github.michaelbull.result.Result<BookVolumes, WebRequestError> {
                assertEquals(details, ++directories)
                gate.await()
                return if (failed) Err(WebRequestError("offline", "failed")) else Ok(catalog("new"))
            }
        }
        fun register(revision: String) = registry.register(source,
            SourceMetadata(WebDataSourceItem(book.sourceId, "Fixture", ""), emptySet(), revision = revision))
        fun checks() = BookDirectoryChecks(registry, fixture.local, fixture.downloads, prefs) { now }
        register("1")
        var checks = checks()
        fun repository() = ChapterRepository(registry, fixture.local, fixture.text, fixture.localBooks, fixture.downloads, checks)
        try {
            val displayed = mutableListOf<BookVolumes>()
            val first = async { repository().getBookVolumesFlow(book.storageKey).collect { it.get()?.let(displayed::add) } }
            runCurrent()
            assertEquals("old", BookIdentity.chapter(displayed.single().volumes.single().chapters.single().id, book).remoteId)
            val second = async { checks.volumes(book, WebDataSourcePriority.High) }
            runCurrent()
            first.cancel(); runCurrent()
            gate.complete(Unit); runCurrent()
            assertTrue(second.await().isOk)
            checks.information(book, WebDataSourcePriority.High)
            assertEquals(1, details); assertEquals(1, directories)

            now += 600_001
            runCurrent()
            assertEquals(1, directories) // Advancing time alone does not trigger work.
            failed = true
            assertTrue(repository().getBookVolumesFlow(book.storageKey).toList().all { it.isOk })
            assertEquals(2, directories)
            checks = checks()
            assertTrue(repository().getBookVolumesFlow(book.storageKey).toList().all { it.isOk })
            assertEquals(2, directories)

            registry.unregister(book.sourceId)
            register("2")
            failed = false
            assertTrue(checks.volumes(book, WebDataSourcePriority.High).isOk)
            assertEquals(3, directories)
        } finally { gate.complete(Unit); registry.unregister(book.sourceId); runCurrent() }
    }
}
