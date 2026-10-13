package indi.renakoni.nextvol.ui.book.reader.content

import android.app.Application
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.tts.SpeechPosition
import indi.renakoni.nextvol.tts.SpeechTextIndex
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.lang.management.ManagementFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ChapterSpeechIndexTest {
    private fun chapter(text: () -> String): ChapterContentUiState {
        val component = mockk<SimpleTextComponent>()
        every { component.data } answers { SimpleTextComponentData(text()) }
        return ChapterContentUiState("chapter", "Title", listOf(component), null, null)
    }

    @Test fun concurrentConsumersBuildOnceOffTheCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val builds = AtomicInteger()
        val chapter = chapter {
            assertNotSame(caller, Thread.currentThread())
            builds.incrementAndGet()
            " 甲𠀀\r\n乙。"
        }
        val position = SpeechPosition("book", chapter.id, "not-ready", 0, 1)
        val follow = ReaderSpeechFollow(position, following = true)
        assertTrue(follow.ranges(chapter).isEmpty())
        assertNull(follow.anchor(chapter))
        assertTrue(follow.awaitingIndex(chapter))
        assertEquals(0, builds.get())
        val indexes = List(8) { async { chapter.prepareSpeechTextIndex() } }.awaitAll()
        assertEquals(1, builds.get())
        indexes.forEach { assertSame(indexes.first(), it) }
        assertSame(indexes.first(), chapter.prepareSpeechTextIndex())
        val expected = SpeechTextIndex(listOf(0 to " 甲𠀀\r\n乙。"))
        assertEquals(expected.fingerprint, indexes.first().fingerprint)
        val ready = follow.copy(position = position.copy(fingerprint = expected.fingerprint, start = 2, end = 4, anchor = 2))
        assertEquals(listOf(SpeechTextIndex.Range(0, 2, 4)), ready.ranges(chapter))
        assertFalse(ready.awaitingIndex(chapter))
        assertEquals(2, ready.anchor(chapter)?.offset)
    }

    @Test fun cancelledPreparationDoesNotPublishAndCanBeRetried() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builds = AtomicInteger()
        val chapter = chapter {
            if (builds.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            "Only the current consumer may publish."
        }
        val job = launch(Dispatchers.Default) { chapter.prepareSpeechTextIndex() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertNull(chapter.speechTextIndex)
        assertSame(chapter.prepareSpeechTextIndex(), chapter.speechTextIndex)
        assertEquals(2, builds.get())
    }

    @Test fun refreshedTextAndOtherChaptersCannotUseTheOldIndex() = runBlocking {
        val old = chapter { "Original text." }
        val refreshed = chapter { "Revised text." }
        val index = old.prepareSpeechTextIndex()
        val follow = ReaderSpeechFollow(SpeechPosition("book", old.id, index.fingerprint, 0, 8), true)
        assertFalse(follow.ranges(old).isEmpty())
        assertNull(refreshed.speechTextIndex)
        refreshed.prepareSpeechTextIndex()
        assertNotEquals(index.fingerprint, refreshed.speechTextIndex!!.fingerprint)
        assertTrue(follow.ranges(refreshed).isEmpty())
        assertNull(follow.anchor(refreshed))
        assertTrue(follow.copy(position = follow.position!!.copy(chapterId = "another")).ranges(old).isEmpty())
        assertNull(follow.copy(following = false).anchor(old))
    }

    @Test fun longTextIsBuiltOnlyOnDemandAndRepeatedConsumptionRetainsOneIndex() = runBlocking(Dispatchers.Default) {
        val text = "确定性长章，包含𠀀和换行。\n".repeat(40_000)
        val allocations = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        repeat(6) { sample ->
            val builds = AtomicInteger()
            val chapter = chapter { builds.incrementAndGet(); text }
            assertNull(chapter.speechTextIndex)
            assertEquals(0, builds.get())
            val thread = Thread.currentThread().id
            val before = allocations?.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            val index = chapter.prepareSpeechTextIndex()
            val elapsed = System.nanoTime() - start
            val bytes = before?.let { allocations.getThreadAllocatedBytes(thread) - it }
            val reuseStart = System.nanoTime()
            repeat(20) { assertSame(index, chapter.prepareSpeechTextIndex()) }
            val reuseElapsed = System.nanoTime() - reuseStart
            assertEquals(1, builds.get())
            assertEquals(text, index.text)
            println("speech-index sample=$sample utf16=${text.length} builds=${builds.get()} prepareNs=$elapsed allocatedBytes=$bytes reuse20Ns=$reuseElapsed")
        }
    }
}
