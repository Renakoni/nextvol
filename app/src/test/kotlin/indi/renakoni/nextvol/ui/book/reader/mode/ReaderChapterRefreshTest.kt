package indi.renakoni.nextvol.ui.book.reader.mode

import android.app.Application
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepositoryFixture
import indi.renakoni.nextvol.ui.book.reader.content.ReaderChapterLoader
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ContinuousScrollSettings
import indi.renakoni.nextvol.ui.book.reader.content.scroll.ScrollReaderController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real fallback/revalidation and display processing; only storage/network timing is controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderChapterRefreshTest {
    private val env = ModeTestEnvironment()
    private val fixture = BookRepositoryFixture()
    private val key = BookIdentity.chapter("chapter", BookIdentity.book("book")).storageKey
    private val remote = env.chapter("chapter", title = "Original")
    private val refresh = CompletableDeferred<Unit>()
    private var processed = 0

    @After fun close() = env.close()

    private fun controller(changeProcessedBody: Boolean = false): ScrollReaderController {
        coEvery { fixture.local.getChapterContent(key) } returns remote.copy(id = key)
        coEvery { fixture.local.updateChapterContent(any()) } returns Unit
        coEvery { fixture.remote.getChapterContent("chapter", "book", any()) } coAnswers {
            refresh.await()
            Ok(remote)
        }
        every { fixture.text.processChapterContent(any(), any()) } answers {
            processed++
            val chapter = secondArg<() -> ChapterContent>()()
            if (changeProcessedBody && processed > 1)
                chapter.copy(content = env.chapter("chapter", title = "Processed").content)
            else chapter
        }
        return ScrollReaderController(
            ReaderChapterLoader(fixture.chapterRepository(), env.renderer, io.mockk.mockk(relaxed = true)), env.records, env.scope,
            object : ContinuousScrollSettings {
                override fun getFlow() = flowOf(false)
                override suspend fun isEnabled() = false
            }, { _, _ -> }, env.dispatcher, env.dispatcher,
        ).also { env.runCurrent(); it.changeBookId("book"); it.changeChapter(key); env.runCurrent() }
    }

    @Test fun identicalRevalidationDoesNotRepublishOrRewriteReadingHistory() {
        val mode = controller()
        val initial = mode.uiState.readingChapterContent!!.get()!!
        mode.uiState.readingProgress = 0.4f
        refresh.complete(Unit); env.runCurrent()
        assertEquals(2, processed)
        coVerify(exactly = 1) { fixture.remote.getChapterContent("chapter", "book", any()) }
        assertEquals(1, env.events.count { it.startsWith("render/") })
        assertEquals(1, env.records.writes.size)
        assertSame(initial, mode.uiState.readingChapterContent!!.get())
        assertEquals(0.4f, mode.uiState.readingProgress)
    }

    @Test fun changedDisplayProcessingIsNotHiddenByEqualRawContent() {
        val mode = controller(changeProcessedBody = true)
        val initial = mode.uiState.readingChapterContent!!.get()!!
        refresh.complete(Unit); env.runCurrent()
        assertEquals(2, processed)
        assertEquals(listOf("render/Original", "render/Processed"),
            env.events.filter { it.startsWith("render/") })
        assertEquals(2, env.records.writes.size)
        assertNotSame(initial, mode.uiState.readingChapterContent!!.get())
    }

    @Test fun trustedCacheStillReturnsWithoutRevalidation() {
        coEvery { fixture.local.getReusableChapterContent(key, any()) } returns remote.copy(id = key)
        val mode = controller()
        refresh.complete(Unit); env.runCurrent()
        assertEquals(1, processed)
        assertEquals(1, env.records.writes.size)
        assertNotNull(mode.uiState.readingChapterContent!!.get())
        coVerify(exactly = 0) { fixture.remote.getChapterContent(any(), any(), any()) }
    }
}
