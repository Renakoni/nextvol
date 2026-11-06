package indi.renakoni.nextvol.tts

import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in acceptance with an installed voice, separate from CI's empty emulator catalogs. */
@RunWith(AndroidJUnit4::class)
class SpeechSchemesInstrumentedTest {
    @Test fun twoBooksSwitchSchemesWithoutInterruptingUnrelatedPlaybackAndRestoreAfterPreview() = runBlocking {
        val voiceName = InstrumentationRegistry.getArguments().getString("speechSchemeVoice")
        assumeTrue("Supply speechSchemeVoice on a device with that voice installed", !voiceName.isNullOrEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = NextVolDatabase.getInstance(context)
        val data = UserDataRepository(database.userDataDao())
        val settings = SpeechSettingsRepository(data)
        val stored = data.stringUserData("tts.schemes")
        val original = withContext(Dispatchers.IO) { stored.get() }
        val books = LocalBookStore(context, database)
        val shelves = withContext(Dispatchers.IO) { database.bookshelfDao().getAllBookshelfIds() }
        var createdShelf: Int? = null
        val imported = mutableListOf<SourceBookId>()
        val entry = EntryPointAccessors.fromApplication(context, SpeechDebugEntryPoint::class.java)
        val controller = entry.controller()
        val voice = entry.voices().roleEngines(SpeechSettings()).flatMap { it.voices }.first { it.label == voiceName }
        val activity = ActivityScenario.launch(ReaderLayoutTestActivity::class.java)
        suspend fun await(label: String, condition: (ReadAloudState) -> Boolean) = withTimeout(60_000) {
            while (!condition(controller.state.value)) {
                check(controller.state.value.phase != SpeechPhase.Failed) { "$label: ${controller.state.value}" }
                delay(50)
            }
        }
        suspend fun start(book: SourceBookId) = withContext(Dispatchers.Main) {
            controller.start(book.storageKey, SourceChapterId(book, "0").storageKey)
        }
        try {
            repeat(2) { index ->
                val file = File(context.cacheDir, "scheme-${UUID.randomUUID()}.txt")
                try {
                    file.writeText("第一章\n" + "原文里的小女孩正在阅读一本有趣的小说，原文。".repeat(100))
                    val draft = books.stage(file.toUri())
                    try {
                        val published = books.publish(draft, books.preview(draft), "Scheme book $index",
                            shelves.firstOrNull() ?: createdShelf)
                        imported += published.first
                        if (shelves.isEmpty()) createdShelf = published.second
                    } finally { books.discard(draft) }
                } finally { file.delete() }
            }
            val a = settings.create("Scheme A", bookId = imported[0].storageKey)
            val b = settings.create("Scheme B", bookId = imported[1].storageKey)
            fun content(word: String) = SpeechSettings(voiceRoutingConfigured = true, narrationVoices = listOf(voice),
                replacements = listOf(SpeechReplacementRule(id = "marker", name = "Marker", source = "原文", target = word)))
            settings.update(a) { content("甲方案") }
            settings.update(b) { content("乙方案") }
            start(imported[0])
            await("A uses A") { it.phase == SpeechPhase.Playing && "甲方案" in it.currentText }
            start(imported[1])
            await("B uses B") { it.phase == SpeechPhase.Playing && "乙方案" in it.currentText }

            val preparations = mutableListOf<ReadAloudState>()
            val observer = launch { controller.state.collect { if (it.phase == SpeechPhase.Preparing) preparations += it } }
            settings.update(a) { content("新甲方案") }
            delay(1500)
            observer.cancel()
            assertTrue("Editing A must not prepare B again", preparations.isEmpty())
            assertTrue("乙方案" in controller.state.value.currentText)
            withContext(Dispatchers.Main) { controller.setSleepTimer(15); controller.command(SpeechAction.Pause) }
            await("B paused") { it.phase == SpeechPhase.Paused && it.sleepTimerRemainingMillis != null }
            val timer = controller.state.value.sleepTimerRemainingMillis
            val anchor = controller.state.value.position!!.anchor
            settings.bind(imported[1].storageKey, a)
            await("Paused B switches to A") { it.phase == SpeechPhase.Paused && it.position != null && "新甲方案" in it.currentText }
            assertEquals(timer, controller.state.value.sleepTimerRemainingMillis)
            assertEquals(anchor, controller.state.value.position!!.anchor)

            withContext(Dispatchers.Main) { controller.preview("这是一次试听。", settings.get(b)) }
            await("Sample starts") { it.request?.isPreview == true }
            await("Sample restores paused B with A") { it.request?.bookId == imported[1].storageKey &&
                it.phase == SpeechPhase.Paused && "新甲方案" in it.currentText }
            assertEquals(timer, controller.state.value.sleepTimerRemainingMillis)
            settings.delete(a, b)
            await("Deleting A rebinds B") { it.phase == SpeechPhase.Paused && "乙方案" in it.currentText }
            assertEquals(b, SpeechSettingsRepository(data).forBook(imported[0].storageKey).schemeId)
            withContext(Dispatchers.Main) { controller.command(SpeechAction.Stop) }
            await("Stop") { it.phase == SpeechPhase.Stopped }
            start(imported[1])
            await("Restart uses persisted B") { it.phase == SpeechPhase.Playing && "乙方案" in it.currentText }
        } finally {
            withContext(Dispatchers.Main) { controller.command(SpeechAction.Stop) }
            delay(500)
            imported.forEach { book ->
                books.delete(book)
                withContext(Dispatchers.IO) { data.remove("tts.progress.${book.fileKey}") }
            }
            withContext(Dispatchers.IO) {
                createdShelf?.let { database.bookshelfDao().deleteBookshelf(it) }
                if (original == null) data.remove("tts.schemes") else stored.set(original)
            }
            activity.close()
        }
    }
}
