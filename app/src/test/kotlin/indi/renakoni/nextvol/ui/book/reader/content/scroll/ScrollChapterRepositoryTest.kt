package indi.renakoni.nextvol.ui.book.reader.content.scroll

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepositoryFixture
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.mode.ModeTestEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real finite ChapterRepository flows, controlling only network/storage I/O timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ScrollChapterRepositoryTest {
    private val env = ModeTestEnvironment()
    private val fixture = BookRepositoryFixture()
    private val cache = mutableMapOf<String, ChapterContent>()
    private val settingsGates = ArrayDeque<CompletableDeferred<Unit>>()

    @After fun close() = env.close()

    private fun controller(): ScrollReaderController {
        coEvery { fixture.local.getChapterContent(any()) } answers { cache[firstArg()] }
        coEvery { fixture.local.updateChapterContent(any()) } answers {
            val chapter = firstArg<ChapterContent>()
            cache[chapter.id] = chapter
        }
        every { fixture.text.processChapterContent(any(), any()) } answers { secondArg<() -> ChapterContent>()() }
        return ScrollReaderController(
            ReaderChapterLoader(fixture.chapterRepository(), env.renderer, io.mockk.mockk(relaxed = true)), env.records, env.scope,
            object : ContinuousScrollSettings {
                override fun getFlow() = flowOf(true)
                override suspend fun isEnabled(): Boolean { settingsGates.removeFirstOrNull()?.await(); return true }
            }, { _, _ -> }, env.dispatcher, env.dispatcher,
        ).also { env.runCurrent(); it.changeBookId("book") }
    }

    private fun key(id: String) = BookIdentity.chapter(id, BookIdentity.book("book")).storageKey
    private fun remote(id: String) = env.chapter(id, next = if (id == "3") "4" else null)
    private fun occupied(mode: ScrollReaderController) = mode.uiState.contentList.mapNotNull {
        it?.first?.let { id -> SourceChapterId.fromStorageKey(id).remoteId }
    }

    @Test fun trustedAdjacentContentDoesNotRequestTheRemote() {
        cache[key("3")] = remote("3").copy(id = key("3"), nextChapter = key("4"))
        cache[key("4")] = remote("4").copy(id = key("4"))
        coEvery { fixture.local.getReusableChapterContent(any(), any()) } answers { cache[firstArg()] }
        val remoteGate = CompletableDeferred<Unit>()
        coEvery { fixture.remote.getChapterContent(any(), any(), any()) } coAnswers {
            remoteGate.await()
            error("Trusted content must not request the remote")
        }
        val mode = controller()
        mode.changeChapter(key("3")); env.runCurrent()
        assertEquals(listOf("3", "4"), occupied(mode))
        assertTrue(mode.uiState.contentList[2]!!.second.isOk)
        mode.changeChapter(key("4")); env.runCurrent()
        assertTrue(mode.uiState.readingChapterContent!!.isOk)
        assertEquals(key("4"), env.records.data.lastReadChapterId)
        coVerify(exactly = 0) { fixture.remote.getChapterContent(any(), any(), any()) }
    }

    @Test fun fallbackAdjacentContentIsReadableBeforeRevalidationCompletes() {
        cache[key("3")] = remote("3").copy(id = key("3"), nextChapter = key("4"))
        cache[key("4")] = remote("4").copy(id = key("4"))
        coEvery { fixture.local.getReusableChapterContent(key("3"), any()) } answers { cache[key("3")] }
        val remoteGate = CompletableDeferred<Unit>()
        var requests = 0
        coEvery { fixture.remote.getChapterContent("4", "book", any()) } coAnswers {
            requests++
            remoteGate.await()
            Err(WebRequestError("offline", "revalidation failed"))
        }
        val mode = controller()
        mode.changeChapter(key("3")); env.runCurrent()
        assertEquals(listOf("3", "4"), occupied(mode))
        assertTrue(mode.uiState.contentList[2]!!.second.isOk)
        assertTrue(mode.uiState.readingChapterContent!!.isOk)
        assertEquals(key("3"), env.records.data.lastReadChapterId)
        assertEquals(1, requests)
        remoteGate.complete(Unit); env.runCurrent()
        assertTrue(mode.uiState.contentList[2]!!.second.isOk)
        assertEquals(1, requests)
    }

    @Test fun inFlightAdjacentFailureCannotCreateDuplicateKeysWithRealRepositoryFlow() {
        val adjacentGate = CompletableDeferred<Unit>()
        var fourthRequests = 0
        coEvery { fixture.remote.getChapterContent("3", "book", any()) } returns Ok(remote("3"))
        coEvery { fixture.remote.getChapterContent("4", "book", any()) } coAnswers {
            fourthRequests++
            adjacentGate.await()
            Err(WebRequestError("offline", "network failed"))
        }
        val mode = controller()
        mode.changeChapter(key("3")); env.runCurrent()
        assertEquals(1, fourthRequests)
        mode.changeChapter(key("4")); env.runCurrent()
        assertEquals(emptyList<String>(), occupied(mode))
        adjacentGate.complete(Unit); env.runCurrent()
        assertEquals(1, fourthRequests)
        assertEquals(listOf("4"), occupied(mode))
    }

    @Test fun changingBookCancelsUncachedAdjacentWithoutLatePublicationOrPersistence() {
        cache[key("3")] = remote("3").copy(id = key("3"), nextChapter = key("4"))
        coEvery { fixture.local.getReusableChapterContent(key("3"), any()) } answers { cache[key("3")] }
        val gate = CompletableDeferred<Unit>()
        var cancelled = 0
        coEvery { fixture.remote.getChapterContent("4", "book", any()) } coAnswers {
            assertEquals(false, currentCoroutineContext()[ForegroundSourceRequest]?.allowsInteraction)
            try {
                gate.await()
                Ok(remote("4"))
            } finally {
                cancelled++
            }
        }
        val mode = controller()
        mode.changeChapter(key("3")); env.runCurrent()
        assertTrue(mode.uiState.readingChapterContent!!.isOk)
        assertNull(mode.uiState.contentList[2])
        assertEquals(key("3"), env.records.data.lastReadChapterId)
        mode.changeBookId("other-book"); env.runCurrent()
        assertEquals(1, cancelled)
        gate.complete(Unit); env.runCurrent()
        assertEquals(listOf(null, null, null), mode.uiState.contentList.toList())
        coVerify(exactly = 1) { fixture.remote.getChapterContent("4", "book", any()) }
        coVerify(exactly = 0) { fixture.local.updateChapterContent(any(), any()) }
    }

    @Test fun alreadyCompletedAdjacentFailureDoesNotReappearAfterExplicitOpen() {
        coEvery { fixture.remote.getChapterContent("3", "book", any()) } returns Ok(remote("3"))
        coEvery { fixture.remote.getChapterContent("4", "book", any()) } returns Err(WebRequestError("offline", "network failed"))
        val mode = controller()
        mode.changeChapter(key("3")); env.runCurrent()
        assertEquals(listOf("3", "4"), occupied(mode))
        mode.changeChapter(key("4")); env.runCurrent()
        assertEquals(listOf("4"), occupied(mode))
    }

    @Test fun realCachedThenRemoteRefreshCannotPublishDuringSettingsRead() {
        val refreshGate = CompletableDeferred<Unit>()
        val settingsGate = CompletableDeferred<Unit>()
        cache[key("3")] = remote("3").copy(id = key("3"), nextChapter = key("4"))
        coEvery { fixture.remote.getChapterContent("3", "book", any()) } coAnswers { refreshGate.await(); Ok(remote("3")) }
        coEvery { fixture.remote.getChapterContent("4", "book", any()) } returns Err(WebRequestError("offline", "network failed"))
        val mode = controller()
        mode.changeChapter(key("3")); env.runCurrent()
        settingsGates += settingsGate
        mode.changeChapter(key("4")); env.runCurrent()
        refreshGate.complete(Unit); env.runCurrent()
        assertEquals(key("4"), mode.requestedChapterId)
        assertNull(mode.uiState.contentList[1])
        assertEquals(key("3"), env.records.data.lastReadChapterId)
        settingsGate.complete(Unit); env.runCurrent()
    }
}
