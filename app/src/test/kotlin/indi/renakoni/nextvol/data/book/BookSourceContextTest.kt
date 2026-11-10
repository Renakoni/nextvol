package indi.renakoni.nextvol.data.book

import android.app.Application
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.web.*
import io.mockk.coEvery
import io.mockk.every
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.book.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class BookSourceContextTest {
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")

    @Test(timeout = 10000) fun delayedChapterAndPreloadKeepTheirSourceAndMissingSourceUsesLocalData() = runBlocking {
        val registry = WebSourceRegistry()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        for (book in listOf(a, b)) {
            val source = object : WebBookDataSource by EmptyWebDataSource {
                override val id = book.sourceId
                override val permits = 2
                override suspend fun getChapterContent(chapterId: String, bookId: String): com.github.michaelbull.result.Result<ChapterContent, io.nightfish.lightnovelreader.api.error.WebRequestError> {
                    assertEquals("same", bookId)
                    assertEquals("chapter", chapterId)
                    if (book == a) { started.complete(Unit); release.await() }
                    return Ok(ChapterContent(chapterId, id.id, JsonObject(emptyMap()), nextChapter = "next"))
                }
            }
            registry.register(source, SourceMetadata(WebDataSourceItem(book.sourceId, "Same name", "fixture"), emptySet()))
        }
        val fixture = BookRepositoryFixture()
        val saved = ConcurrentHashMap<String, ChapterContent>()
        coEvery { fixture.local.getChapterContent(any()) } answers { saved[firstArg()] }
        coEvery { fixture.local.updateChapterContent(any()) } answers {
            val content = firstArg<ChapterContent>(); saved[content.id] = content
        }
        every { fixture.text.processChapterContent(any(), any()) } answers { secondArg<() -> ChapterContent>()() }
        val repository = ChapterRepository(registry, fixture.local, fixture.text, fixture.localBooks, fixture.downloads, fixture.directoryChecks)
        try {
            val pendingA = async { repository.getChapterContentFlow("chapter", a.storageKey).last().get()!! }
            started.await()
            repository.preloadChapterContent("chapter", b.storageKey)
            val bResult = repository.getChapterContentFlow("chapter", b.storageKey).last().get()!!
            assertEquals("b", bResult.title)
            release.complete(Unit)
            val aResult = pendingA.await()
            assertEquals("a", aResult.title)
            assertEquals(SourceChapterId(a, "next").storageKey, aResult.nextChapter)
            assertEquals(SourceChapterId(b, "next").storageKey, bResult.nextChapter)
            assertEquals(setOf(SourceChapterId(a, "chapter").storageKey, SourceChapterId(b, "chapter").storageKey), saved.keys)
            registry.unregister(a.sourceId)
            val offline = repository.getChapterContentFlow("chapter", a.storageKey).toList()
            assertEquals(listOf(Ok(aResult)), offline)
            assertTrue(repository.getChapterContentFlow("uncached", a.storageKey).last().isErr)
            assertEquals("b", repository.getChapterContentFlow("chapter", b.storageKey).last().get()!!.title)
        } finally {
            release.complete(Unit)
            registry.unregister(a.sourceId); registry.unregister(b.sourceId)
        }
    }

    @Test fun readerAndImageRoutesRoundTripBothSourceIdentities() {
        for (book in listOf(a, b)) {
            val reader = Route.Book.Reader(book.storageKey, SourceChapterId(book, "chapter").storageKey)
            assertEquals(reader, Json.decodeFromString<Route.Book.Reader>(Json.encodeToString(reader)))
            val image = Route.Book.ImageViewerDialog("https://fixture.invalid/shared.png", book.storageKey)
            assertEquals(image, Json.decodeFromString<Route.Book.ImageViewerDialog>(Json.encodeToString(image)))
            assertEquals(book, SourceChapterId.fromStorageKey(reader.chapterId).book)
        }
    }
}
