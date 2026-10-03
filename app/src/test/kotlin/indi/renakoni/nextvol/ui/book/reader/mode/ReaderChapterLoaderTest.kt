package indi.renakoni.nextvol.ui.book.reader.mode

import android.app.Application
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import indi.renakoni.nextvol.ui.book.reader.content.ChapterContentUiState
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.content.ReaderSpeechFollow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.nightfish.lightnovelreader.api.content.ContentData
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
    fun visualLoadingAndSynchronousFollowReadsNeverBuildTheSpeechIndex() {
        val component = mockk<SimpleTextComponent>()
        every { env.renderer.getContentDataFromJson(any()) } returns ContentData(listOf(component))
        val results = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        env.scope.launch { env.loader.load("request", "book").collect(results::add) }
        env.runCurrent()
        env.emit("request", Ok(env.chapter("request")))
        val chapter = results.single().get()!!
        assertNull(chapter.speechTextIndex)
        assertTrue(ReaderSpeechFollow().ranges(chapter).isEmpty())
        assertNull(ReaderSpeechFollow().anchor(chapter))
        verify(exactly = 0) { component.data }
    }

    @Test
    fun eachCollectorMapsIndependentlyAndPreservesComponentsAndOrderedErrors() {
        val component = mockk<AbstractContentComponent<*>>()
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
    fun rendererFailuresTerminateTheSubscriptionWithoutInventingAFallback() {
        val failure = IllegalArgumentException("invalid component JSON")
        every { env.renderer.getContentDataFromJson(any()) } throws failure
        var caught: Throwable? = null
        val results = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        env.scope.launch {
            env.loader.load("request", "book").catch { caught = it }.collect(results::add)
        }
        env.runCurrent()
        env.emit("request", Ok(env.chapter("request")))
        assertSame(failure, caught)
        assertTrue(results.isEmpty())
        assertTrue(env.chapters.active.isEmpty())
    }

    @Test
    fun equalProcessedResultsDecodeAndPublishOnlyOncePerSubscription() {
        val results = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        val flow = env.loader.load("request", "book")
        val first = env.scope.launch { flow.collect(results::add) }
        env.runCurrent()
        val chapter = env.chapter("request", "prev", "next", "Same body")
        repeat(3) { env.emit("request", Ok(chapter.copy())) }
        assertEquals(1, results.size)
        assertEquals(1, env.events.count { it.startsWith("render/") })
        first.cancel(); env.runCurrent()
        env.scope.launch { flow.collect(results::add) }
        env.runCurrent()
        env.emit("request", Ok(chapter.copy()))
        assertEquals(2, results.size)
        assertEquals(2, env.events.count { it.startsWith("render/") })
        assertNotSame(results[0].get(), results[1].get())
    }

    @Test
    fun sameIdBodyTitleAndAdjacentChangesAreNotSuppressed() {
        val results = mutableListOf<Result<ChapterContentUiState, WebRequestError>>()
        env.scope.launch { env.loader.load("request", "book").collect(results::add) }
        env.runCurrent()
        val original = env.chapter("request", "prev", "next", "First")
        val revised = original.copy(content = env.chapter("request", title = "Other").content)
        val renamed = revised.copy(title = "Renamed")
        val redirected = renamed.copy(prevChapter = "new-prev", nextChapter = "new-next")
        listOf(original, revised, renamed, redirected).forEach { env.emit("request", Ok(it)) }
        assertEquals(4, results.size)
        assertEquals(listOf("render/First", "render/Other", "render/Other", "render/Other"),
            env.events.filter { it.startsWith("render/") })
        assertEquals("Renamed", results.last().get()!!.title)
        assertEquals("new-prev", results.last().get()!!.prevChapter)
        assertEquals("new-next", results.last().get()!!.nextChapter)
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
