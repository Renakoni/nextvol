package indi.renakoni.nextvol.ui.book.reader.mode

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.ContentData
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderChapterLoaderTest {
    private val env = ModeTestEnvironment()
    @After fun tearDown() = env.close()

    @Test
    fun eachCollectorMapsIndependentlyAndPreservesComponentsAndOrderedErrors() {
        val component = mockk<AbstractContentComponent<*>>()
        every { component.data } returns SimpleTextComponentData("Text")
        every { env.renderer.getContentDataFromJson(any()) } returns ContentData(listOf(component))
        val first = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        val second = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        val flow = env.loader.load("request", "book")
        assertTrue(env.chapters.active.isEmpty())
        val firstJob = env.scope.launch { flow.collect(first::add) }
        env.scope.launch { flow.collect(second::add) }
        env.runCurrent()
        assertEquals(2, env.chapters.active.size)
        env.emit("request", Ok(env.chapter("payload", "prev", "next", "Title")))
        val error = Err(WebRequestError("offline", "remote failed"))
        env.emit("request", error)
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertSame(component, first.first().get()!!.content.single())
        assertEquals(error, first.last())
        assertEquals(error, second.last())
        firstJob.cancel()
        env.runCurrent()
        assertEquals(1, env.chapters.active.size)
        env.emit("request", Ok(env.chapter("later")))
        assertEquals(2, first.size)
        assertEquals(3, second.size)
        assertTrue(env.records.writes.isEmpty())
        assertTrue(env.chapters.preloads.isEmpty())
    }

    @Test
    fun retainedWindowContentRequiresTheSameBookAndCompleteSourceAndStillPublishesRecovery() {
        val results = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        val first = env.scope.launch { env.loader.load("request", "book").collect(results::add) }
        env.runCurrent()
        val original = env.chapter("request", "prev", "next", "First")
        env.emit("request", Ok(original))
        val retained = results.single().get()!!
        first.cancel(); env.runCurrent()
        results.clear()
        env.scope.launch {
            env.loader.load("request", "book", retainedContent = retained).collect(results::add)
        }
        env.runCurrent()
        val error = Err(WebRequestError("offline", "failed"))
        listOf(Ok(original.copy()), error, Ok(original.copy())).forEach { env.emit("request", it) }
        assertEquals(3, results.size)
        assertSame(retained, results[0].get())
        assertEquals(error, results[1])
        assertSame(retained, results[2].get())
        assertEquals(1, env.events.count { it.startsWith("render/") })
        listOf(original.copy(content = env.chapter("request", title = "Revised").content),
            original.copy(title = "Renamed"), original.copy(prevChapter = "other-prev"),
            original.copy(nextChapter = "other-next")).forEach { changed ->
            env.emit("request", Ok(changed))
            assertNotSame(retained, results.last().get())
        }
        val otherBook = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        env.scope.launch {
            env.loader.load("request", "other-book", retainedContent = retained).collect(otherBook::add)
        }
        env.runCurrent()
        env.emit("request", Ok(original.copy()))
        assertNotSame(retained, otherBook.single().get())
    }

    @Test
    fun errorsAndRecoveryArePublishedEvenWhenTheRecoveredBodyIsUnchanged() {
        val results = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        env.scope.launch { env.loader.load("request", "book").collect(results::add) }
        env.runCurrent()
        val chapter = Ok(env.chapter("request"))
        val error = Err(WebRequestError("offline", "failed"))
        listOf(chapter, error, error, chapter).forEach { env.emit("request", it) }
        assertEquals(4, results.size)
        assertEquals(error, results[1])
        assertEquals(error, results[2])
        assertEquals(2, env.events.count { it.startsWith("render/") })
    }
}
