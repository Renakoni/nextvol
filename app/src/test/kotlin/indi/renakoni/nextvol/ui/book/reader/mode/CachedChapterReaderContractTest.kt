package indi.renakoni.nextvol.ui.book.reader.mode

import indi.renakoni.nextvol.data.book.BookIdentity
import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.BookRepositoryFixture
import indi.renakoni.nextvol.data.book.ChapterNavigation
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real repository and chapter loader; each concrete test loads only its own reading mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
abstract class CachedChapterReaderContractTest {
    internal val env = ModeTestEnvironment(indi.renakoni.nextvol.data.web.ForegroundSourceRequest())
    private val fixture = BookRepositoryFixture()
    private val error = WebRequestError("offline", "request failed")

    protected abstract fun controller(loader: ReaderChapterLoader): ReaderModeController

    @After fun tearDown() = env.close()

    @Test fun enteringTheUnfinishedNextChapterKeepsItsOnlyRemoteRequest() = assertHandoff(verification = false)

    @Test fun aBackgroundVerificationFailureResumesWithTheCurrentForegroundOwner() = assertHandoff(verification = true)

    private fun assertHandoff(verification: Boolean) {
        val book = BookIdentity.book("book")
        val current = BookIdentity.chapter("current", book).storageKey
        val next = BookIdentity.chapter("next", book).storageKey
        coEvery { fixture.local.getReusableChapterContent(current, any()) } returns env.chapter(current, next = next)
        coEvery { fixture.local.getChapterContent(next) } returns null
        coEvery { fixture.local.updateChapterContent(any()) } returns Unit
        val gate = CompletableDeferred<Unit>()
        val interactions = mutableListOf<Boolean?>()
        coEvery { fixture.remote.getChapterContent("next", "book", any()) } coAnswers {
            interactions += kotlinx.coroutines.currentCoroutineContext()[indi.renakoni.nextvol.data.web.ForegroundSourceRequest]?.allowsInteraction
            gate.await()
            if (verification && interactions.size == 1) Err(WebRequestError("verification", "required",
                kind = io.nightfish.lightnovelreader.api.error.WebRequestErrorKind.VerificationRequired))
            else Ok(env.chapter("next"))
        }
        every { fixture.text.processChapterContent(any(), any()) } answers { secondArg<() -> ChapterContent>()() }
        val mode = controller(ReaderChapterLoader(fixture.chapterRepository(), env.renderer))
        env.runCurrent(); mode.changeBookId("book"); mode.changeChapter(current); env.runCurrent()
        mode.changeChapter(next); env.runCurrent()
        assertEquals(current, env.records.data.lastReadChapterId)
        coVerify(exactly = 1) { fixture.remote.getChapterContent("next", "book", any()) }
        gate.complete(Unit); env.runCurrent()
        assertEquals(next, mode.uiState.readingChapterContent!!.get()!!.id)
        assertEquals(next, env.records.data.lastReadChapterId)
        coVerify(exactly = if (verification) 2 else 1) { fixture.remote.getChapterContent("next", "book", any()) }
        assertEquals(if (verification) listOf(false, true) else listOf(false), interactions)
    }

    @Test
    fun appendedAndReorderedDirectoryUpdatesNavigationWithoutReplacingTheCachedBody() {
        val book = BookIdentity.book("book")
        fun key(id: String) = BookIdentity.chapter(id, book).storageKey
        fun directory(vararg ids: String) = ChapterNavigation(BookVolumes("book", ids.mapIndexed { index, id ->
            Volume("volume-$index", "Volume", listOf(ChapterInformation(key(id), id)))
        }))
        coEvery { fixture.local.getReusableChapterContent(key("cached"), any()) } returns
            env.chapter(key("cached"), title = "cached")
        coEvery { fixture.local.getReusableChapterContent(key("new"), any()) } returns
            env.chapter(key("new"), title = "new")
        coEvery { fixture.local.getReusableChapterContent(key("before"), any()) } returns
            env.chapter(key("before"), title = "before")
        every { fixture.text.processChapterContent(any(), any()) } answers { secondArg<() -> ChapterContent>()() }
        val mode = controller(ReaderChapterLoader(fixture.chapterRepository(), env.renderer))
        env.runCurrent()
        mode.changeBookId("book")
        mode.updateNavigation(directory("cached"))
        mode.changeChapter(key("cached")); env.runCurrent()
        val content = mode.uiState.readingChapterContent!!.get()!!
        val progress = mode.uiState.readingProgress
        val writes = env.records.writes.size

        mode.updateNavigation(directory("cached", "new")); env.runCurrent()
        assertEquals(key("new"), content.nextChapter)
        mode.updateNavigation(directory("before", "cached", "new", "cached")); env.runCurrent()
        assertEquals(key("before"), content.prevChapter)
        assertEquals(key("new"), content.nextChapter)
        assertSame(content, mode.uiState.readingChapterContent!!.get())
        assertEquals(progress, mode.uiState.readingProgress)
        assertEquals(writes, env.records.writes.size)
        verify(exactly = 1) { env.renderer.getContentDataFromJson(any()) }
        coVerify(atLeast = 1) { fixture.local.getReusableChapterContent(key("new"), any()) }
        coVerify(exactly = 0) { fixture.remote.getChapterContent(any(), any(), any()) }

        mode.updateNavigation(directory("new")); env.runCurrent()
        assertEquals(null, content.prevChapter)
        assertEquals(null, content.nextChapter)
        assertSame(content, mode.uiState.readingChapterContent!!.get())

        mode.updateNavigation(directory("cached", "new"))
        mode.changeChapter(key("cached")); env.runCurrent()
        assertEquals(key("new"), mode.uiState.readingChapterContent!!.get()!!.nextChapter)
        mode.loadNextChapter(); env.runCurrent()
        // Flip mode stages the new chapter until its pages commit; scroll opens it directly.
        coVerify(atLeast = 2) { fixture.local.getReusableChapterContent(key("new"), any()) }
        coVerify(exactly = 0) { fixture.remote.getChapterContent(any(), any(), any()) }
    }

    @Test
    fun delayedRefreshFailurePreservesDisplayedCacheWithoutRenderingOrWritingAgain() {
        val remote = CompletableDeferred<Unit>()
        val mode = openCachedChapter(remote)
        val content = mode.uiState.readingChapterContent!!.get()!!
        assertEquals("processed:cached", content.title)
        val progress = mode.uiState.readingProgress

        remote.complete(Unit)
        env.runCurrent()

        assertSame(content, mode.uiState.readingChapterContent!!.get())
        assertEquals(progress, mode.uiState.readingProgress)
        assertEquals(1, env.records.writes.size)
        coVerify(exactly = 1) { fixture.remote.getChapterContent("cached", "book", any()) }
        coVerify(exactly = 0) { fixture.local.updateChapterContent(any()) }
        verify(exactly = 1) { env.renderer.getContentDataFromJson(any()) }
    }

    @Test
    fun anUncachedChapterStillReplacesThePreviousChapterWithItsOwnError() {
        val remote = CompletableDeferred<Unit>()
        val mode = openCachedChapter(remote)
        remote.complete(Unit)
        env.runCurrent()
        coEvery { fixture.local.getChapterContent(BookIdentity.chapter("uncached", BookIdentity.book("book")).storageKey) } returns null
        coEvery { fixture.remote.getChapterContent("uncached", "book", any()) } returns Err(error)

        mode.changeChapter("uncached")
        env.runCurrent()

        assertEquals("uncached", mode.uiState.readingChapterId)
        assertEquals(Err(error), mode.uiState.readingChapterContent)
        assertEquals(1, env.records.writes.size)
        verify(exactly = 1) { env.renderer.getContentDataFromJson(any()) }
    }

    private fun openCachedChapter(remote: CompletableDeferred<Unit>): ReaderModeController {
        coEvery { fixture.local.getChapterContent(BookIdentity.chapter("cached", BookIdentity.book("book")).storageKey) } returns env.chapter("cached")
        coEvery { fixture.remote.getChapterContent("cached", "book", any()) } coAnswers {
            remote.await()
            Err(error)
        }
        every { fixture.text.processChapterContent(BookIdentity.bookKey("book"), any()) } answers {
            val chapter = secondArg<() -> ChapterContent>()()
            chapter.copy(title = "processed:${chapter.title}")
        }
        val mode = controller(ReaderChapterLoader(fixture.chapterRepository(), env.renderer))
        env.runCurrent()
        mode.changeBookId("book")
        mode.changeChapter("cached")
        env.runCurrent()
        assertEquals("processed:cached", mode.uiState.readingChapterContent!!.get()!!.title)
        return mode
    }
}
