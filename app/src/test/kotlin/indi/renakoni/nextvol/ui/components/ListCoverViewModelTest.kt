package indi.renakoni.nextvol.ui.components

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ListCoverViewModelTest {
    @Test fun coverlessBooksShareReadsRunFourAtATimeAndRetryOnlyFailures() = runTest {
        val replies = mutableMapOf<String, CompletableDeferred<Uri?>>()
        val reads = mutableListOf<String>()
        var running = 0
        var peak = 0
        val model = ListCoverViewModel { id ->
            reads += id
            peak = maxOf(peak, ++running)
            try { replies.getOrPut(id) { CompletableDeferred() }.await() } finally { running-- }
        }
        val first = (1..6).map { async { model.cover("book-$it") } }
        val sameBook = async { model.cover("book-1") }
        runCurrent()
        assertEquals((1..4).map { "book-$it" }, reads)
        val cover = Uri.parse("https://covers.invalid/1.webp")
        replies.getValue("book-1").complete(cover)
        replies.getValue("book-2").complete(Uri.EMPTY) // Details exist but have no cover.
        replies.getValue("book-3").complete(null) // The read failed.
        replies.getValue("book-4").complete(cover)
        runCurrent()
        replies.getValue("book-5").complete(cover)
        replies.getValue("book-6").complete(cover)
        runCurrent()
        assertEquals(cover, first[0].await())
        assertEquals(cover, sameBook.await())
        assertNull(first[1].await())
        assertNull(first[2].await())
        assertEquals(4, peak)
        assertEquals((1..6).map { "book-$it" }, reads)
        // A known "no cover" is kept; a failure is read again on the next visit.
        assertNull(model.cover("book-2"))
        assertNull(model.cover("book-3"))
        assertEquals(listOf("book-3"), reads.drop(6))
    }

    @Test fun failingOrHangingReadsKeepTheGeneratedCoverAndAreRetried() = runTest {
        val reads = mutableListOf<String>()
        val model = ListCoverViewModel { id ->
            reads += id
            if (id == "broken") throw IllegalStateException("Source failed")
            awaitCancellation()
        }
        assertNull(model.cover("broken"))
        assertNull(model.cover("hanging")) // Times out instead of holding a read slot.
        assertNull(model.cover("broken"))
        assertEquals(listOf("broken", "hanging", "broken"), reads)
    }
}
