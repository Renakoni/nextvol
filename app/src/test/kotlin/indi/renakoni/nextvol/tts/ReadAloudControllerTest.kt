package indi.renakoni.nextvol.tts

import android.app.Application
import android.content.Context
import indi.renakoni.nextvol.data.book.BookIdentity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReadAloudControllerTest {
    private val context = mockk<Context>(relaxed = true) {
        every { packageName } returns "indi.renakoni.nextvol"
    }
    private val controller = ReadAloudController(context)
    private val book = BookIdentity.book("book")
    private val request = SpeechRequest(book.storageKey, BookIdentity.chapter("chapter", book).storageKey)

    @Test fun openingPausedOrFailedControlsDoesNotResumeUntilExplicitlyRequested() {
        for (phase in listOf(SpeechPhase.Paused, SpeechPhase.Failed)) {
            controller.publish(ReadAloudState(request, phase, sleepTimerDeadline = 60_000L))
            val before = controller.state.value
            controller.start("book", "another-chapter")
            assertEquals(before, controller.state.value)
        }
        verify(exactly = 0) { context.startService(any()); context.startForegroundService(any()) }
        controller.command(SpeechAction.Resume)
        verify(exactly = 1) {
            context.startForegroundService(match { it.getStringExtra(ReadAloudController.ACTION) == SpeechAction.Resume.name })
        }
    }

    @Test fun firstStartStoppedBookAndAnotherBookStillStartPlayback() {
        controller.start("book", "chapter")
        assertEquals(SpeechPhase.Preparing, controller.state.value.phase)
        controller.publish(ReadAloudState(request, SpeechPhase.Stopped))
        controller.start("book", "chapter")
        assertEquals(SpeechPhase.Preparing, controller.state.value.phase)
        controller.publish(ReadAloudState(request, SpeechPhase.Paused))
        controller.start("other-book", "chapter")
        assertEquals(BookIdentity.bookKey("other-book"), controller.state.value.request?.bookId)
        verify(exactly = 3) {
            context.startForegroundService(match { it.getStringExtra(ReadAloudController.ACTION) == SpeechAction.Start.name })
        }
    }
}
