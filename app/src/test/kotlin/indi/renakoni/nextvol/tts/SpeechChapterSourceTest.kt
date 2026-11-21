package indi.renakoni.nextvol.tts

import android.app.Application
import com.github.michaelbull.result.Ok
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.ChapterSource
import indi.renakoni.nextvol.data.content.ComponentDataRegistry
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SpeechChapterSourceTest {
    private val registry = mockk<ComponentDataRegistry> {
        every { serializeMap } returns mapOf(
            "lightnovelreader:simple_text" to SimpleTextComponentData.jsonSerializer,
            "lightnovelreader:image" to ImageComponentData.jsonSerializer,
        )
    }
    private fun content(body: String) = Json.parseToJsonElement("""{"components":[$body]}""").jsonObject

    @Test fun usesTheProcessedFirstEmissionWithoutGrantingBackgroundInteraction() = runBlocking {
        val book = BookIdentity.book("book")
        val chapter = BookIdentity.chapter("chapter", book)
        var refreshed = false
        val chapters = mockk<ChapterSource> {
            every { getChapterContentFlow(chapter.storageKey, book.storageKey, any()) } returns flow {
                assertEquals(false, currentCoroutineContext()[ForegroundSourceRequest]?.allowsInteraction)
                emit(Ok(ChapterContent(chapter.storageKey, "Title", content("""{"id":"simple_text","data":{"text":"Processed text."}}"""), nextChapter = "next")))
                refreshed = true
                error("The cached chapter must not refresh during speech")
            }
        }
        val local = mockk<LocalBookDataSource> { coEvery { getBookInformation(book.storageKey) } returns null }
        val spoken = RepositorySpeechChapterSource(chapters, registry, local).load(book.storageKey, chapter.storageKey)
        assertEquals("Processed text.", spoken.text)
        assertEquals(BookIdentity.chapter("next", book).storageKey, spoken.nextId)
        assertFalse(refreshed)
    }
}
