package indi.renakoni.nextvol.tts

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.SourceChapterId
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.localbook.LocalBookDraft
import indi.renakoni.nextvol.data.localbook.LocalBookStore
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in acceptance with an installed, initialized engine; never substitutes generated test tones. */
@RunWith(AndroidJUnit4::class)
class ReadAloudBackgroundInstrumentedTest {
    @Test fun aNewPlaybackDoesNotReuseTheStoppedServicesNotification() = runBlocking {
        var previousId: Int? = null
        repeat(3) {
            withBook { fixture ->
                fixture.await("new service playing") { fixture.player.isPlaying && fixture.notificationRecord() != null }
                val id = fixture.notificationRecord()!!.id
                assertNotEquals("System UI must not apply old notification removal to this service", previousId, id)
                previousId = id
                shell("input keyevent KEYCODE_HOME")
                shell("input keyevent KEYCODE_SLEEP")
                // Give the previous entry's asynchronous System UI removal time to arrive.
                repeat(15) {
                    fixture.assertPlaybackAdvances("new service remains playing")
                    assertEquals(id, fixture.notificationRecord()?.id)
                }
            }
        }
    }

    @Test fun timerKeepsItsDeadlineAcrossChaptersAndVoiceChangesInTheBackground() = runBlocking {
        withBook(chapterCount = 10) { fixture ->
            fixture.await("timer book playing") { fixture.player.isPlaying }
            val deadline = fixture.setTimer(1)!!
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            fixture.await("timer chapter transition", 50_000) {
                fixture.player.isPlaying && fixture.player.mediaMetadata.title.toString() == "Chapter 2"
            }
            assertEquals(deadline, fixture.commands.state.value.sleepTimerDeadline)
            val data = UserDataRepository(NextVolDatabase.getInstance(fixture.context).userDataDao())
            withContext(Dispatchers.IO) {
                val stored = data.stringUserData("tts.settings")
                val current = Json.decodeFromString<SpeechSettings>(stored.get()!!)
                stored.set(Json.encodeToString(current.copy(pitch = 1.2f)))
            }
            fixture.assertPlaybackAdvances("after voice adjustment with timer")
            assertEquals(deadline, fixture.commands.state.value.sleepTimerDeadline)
            fixture.assertTimerStopped(deadline)
            assertFalse("Timer must not mark the remaining book finished", fixture.progress.load(fixture.book.storageKey)!!.completed)
        }
    }

    @Test fun timerCanBeReplacedOrCancelledAndStillExpiresWhilePaused() = runBlocking {
        withBook(chapterCount = 10) { fixture ->
            fixture.await("playing before pause timer") { fixture.player.isPlaying }
            val original = fixture.setTimer(1)!!
            assertTrue(fixture.setTimer(15)!! > original + 13 * 60_000)
            assertNull(fixture.setTimer(null))
            val deadline = fixture.setTimer(1)!!
            withContext(Dispatchers.Main) { fixture.commands.command(SpeechAction.Pause) }
            fixture.await("paused timer") { fixture.commands.state.value.phase == SpeechPhase.Paused }
            val pausedAt = withContext(Dispatchers.Main) { fixture.player.currentPosition }
            shell("input keyevent KEYCODE_HOME")
            shell("input keyevent KEYCODE_SLEEP")
            delay(2_000)
            assertEquals(deadline, fixture.commands.state.value.sleepTimerDeadline)
            withContext(Dispatchers.Main) { assertEquals(pausedAt, fixture.player.currentPosition) }
            fixture.assertTimerStopped(deadline)
        }
    }

    @Test fun previewsRestoreTheBookAndKeepItsDeadlineAcrossPauseFailureAndExpiry() = runBlocking {
        withBook(chapterCount = 10) { fixture ->
            fixture.await("book before previews") { fixture.player.isPlaying }
            val deadline = fixture.setTimer(1)!!
            val data = UserDataRepository(NextVolDatabase.getInstance(fixture.context).userDataDao())
            val stored = data.stringUserData("tts.settings")
            val original = withContext(Dispatchers.IO) { stored.get()!! }
            withContext(Dispatchers.Main) {
                fixture.commands.preview("This is a short preview of another voice.",
                    Json.decodeFromString<SpeechSettings>(original).copy(pitch = 1.2f))
            }
            fixture.await("sample playing with original timer") {
                fixture.player.isPlaying && fixture.commands.state.value.request?.isPreview == true
            }
            assertEquals(deadline, fixture.commands.state.value.sleepTimerDeadline)
            fixture.await("book restored after sample") {
                fixture.player.isPlaying && fixture.commands.state.value.request?.bookId == fixture.book.storageKey
            }
            assertEquals(deadline, fixture.commands.state.value.sleepTimerDeadline)
            assertEquals(original, withContext(Dispatchers.IO) { stored.get() })
            withContext(Dispatchers.Main) {
                fixture.commands.command(SpeechAction.Pause)
                fixture.commands.preview("This voice is unavailable.", SpeechSettings(engine = "invalid.nextvol.preview.engine"))
            }
            fixture.await("failed sample restores paused book") {
                fixture.commands.state.value.request?.bookId == fixture.book.storageKey &&
                    fixture.commands.state.value.phase == SpeechPhase.Paused
            }
            assertEquals(deadline, fixture.commands.state.value.sleepTimerDeadline)
            withContext(Dispatchers.Main) { fixture.commands.preview("A quiet morning begins our next chapter. ".repeat(100)) }
            fixture.await("sample before expiry") {
                fixture.player.isPlaying && fixture.commands.state.value.request?.isPreview == true
            }
            fixture.assertTimerStopped(deadline)
            assertFalse(fixture.progress.load(fixture.book.storageKey)!!.completed)
            assertEquals(original, withContext(Dispatchers.IO) { stored.get() })
        }
    }

    @Test fun explicitBookStartFailureAndStopClearTheTimer() = runBlocking {
        withBook(chapterCount = 10) { fixture ->
            fixture.await("playing before timer lifecycle checks") { fixture.player.isPlaying }
            fixture.setTimer(15)
            withContext(Dispatchers.Main) {
                androidx.core.content.ContextCompat.startForegroundService(fixture.context,
                    ReadAloudController.intent(fixture.context, SpeechAction.Start, fixture.commands.state.value.request!!))
            }
            fixture.await("new Start clears the old timer") {
                fixture.player.isPlaying && fixture.commands.state.value.request?.isPreview == false &&
                    fixture.commands.state.value.sleepTimerDeadline == null
            }
            fixture.setTimer(15)
            val data = UserDataRepository(NextVolDatabase.getInstance(fixture.context).userDataDao())
            val stored = data.stringUserData("tts.settings")
            val valid = withContext(Dispatchers.IO) { stored.get()!! }
            withContext(Dispatchers.IO) {
                stored.set(Json.encodeToString(SpeechSettings(engine = "invalid.nextvol.timer.engine")))
            }
            fixture.await("failure clears timer") {
                fixture.commands.state.value.phase == SpeechPhase.Failed && fixture.commands.state.value.sleepTimerDeadline == null
            }
            withContext(Dispatchers.IO) { stored.set(valid) }
            fixture.await("valid voice restored") { fixture.player.isPlaying }
            fixture.setTimer(15)
            withContext(Dispatchers.Main) { fixture.commands.command(SpeechAction.Stop) }
            fixture.await("Stop clears timer") {
                fixture.commands.state.value.phase == SpeechPhase.Stopped && fixture.commands.state.value.sleepTimerDeadline == null
            }
            fixture.releasePlayer()
            fixture.await("Stop releases timer service") { fixture.service() == null }
        }
    }

    @Test fun realBookContinuesAcrossChaptersAfterHomeLockAndTaskRemoval() = runBlocking {
        withBook { fixture ->
            fixture.await("first chapter playing") {
                fixture.player.isPlaying && fixture.player.mediaMetadata.title.toString() == "Chapter 1"
            }
            shell("input keyevent KEYCODE_HOME")
            fixture.assertPlaybackAdvances("after Home")
            shell("input keyevent KEYCODE_SLEEP")
            fixture.await("screen off") { !fixture.context.getSystemService(PowerManager::class.java).isInteractive }
            fixture.assertPlaybackAdvances("while screen is off")
            var taskId = -1
            fixture.activity.onActivity { taskId = it.taskId; it.finishAndRemoveTask() }
            fixture.await("reader task removed") {
                fixture.context.getSystemService(ActivityManager::class.java).appTasks.none { it.taskInfo?.id == taskId }
            }
            fixture.assertPlaybackAdvances("after removing the task")
            fixture.await("natural transition to Chapter 2", 90_000) {
                fixture.player.isPlaying && fixture.player.mediaMetadata.title.toString() == "Chapter 2"
            }
            val boundary = fixture.progress.load(fixture.book.storageKey)!!
            assertEquals(SourceChapterId(fixture.book, "1").storageKey, boundary.chapterId)
            assertFalse("Prefetch must not mark an unplayed chapter completed", boundary.completed)
            fixture.await("last chapter completed", 60_000) {
                fixture.progress.load(fixture.book.storageKey)?.completed == true
            }
            fixture.await("completion releases foreground and temporary audio") {
                fixture.service()?.foreground == false &&
                    File(fixture.context.cacheDir, "read-aloud").listFiles().orEmpty().isEmpty()
            }
        }
    }

    @Test fun notificationCanPauseResumeAndStopWhileTheBookIsInBackground() = runBlocking {
        withBook { fixture ->
            fixture.await("playing with a media notification") {
                fixture.player.isPlaying && fixture.notification() != null
            }
            shell("input keyevent KEYCODE_HOME")
            fixture.notification()!!.actions[0].actionIntent.send()
            fixture.await("notification pause") { !fixture.player.playWhenReady && !fixture.player.isPlaying }
            val pausedAt = withContext(Dispatchers.Main) { fixture.player.currentPosition }
            delay(700)
            withContext(Dispatchers.Main) {
                assertEquals("Paused audio must not advance", pausedAt, fixture.player.currentPosition)
            }
            fixture.await("pause updates the notification and exits foreground") {
                fixture.service()?.foreground == false && fixture.notification()?.actions?.firstOrNull()?.title ==
                    fixture.context.getString(indi.renakoni.nextvol.R.string.tts_resume)
            }
            fixture.notification()!!.actions[0].actionIntent.send()
            fixture.await("notification resume") { fixture.player.isPlaying && fixture.service()?.foreground == true }
            fixture.assertPlaybackAdvances("after notification resume")
            fixture.notification()!!.actions[1].actionIntent.send()
            fixture.releasePlayer()
            fixture.await("notification Stop releases the service") { fixture.service() == null }
            fixture.await("Stop removes notification and temporary audio") {
                fixture.notification() == null && File(fixture.context.cacheDir, "read-aloud").listFiles().orEmpty().isEmpty()
            }
            assertFalse(fixture.progress.load(fixture.book.storageKey)!!.completed)
        }
    }

    /** Supply speechLongRunMinutes=120 for A02; a shorter diagnostic run is not long-run acceptance. */
    @Test fun continuousBackgroundPlaybackStaysOrderedAndBounded() = runBlocking {
        val minutes = InstrumentationRegistry.getArguments().getString("speechLongRunMinutes")?.toLong()
        assumeTrue("Supply speechLongRunMinutes for the separate continuous-playback run", minutes != null)
        require(minutes!! > 0)
        withBook(chapterCount = 600) { fixture ->
            var chapter = 0
            var orderError: String? = null
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) return
                    val next = fixture.player.mediaMetadata.title.toString().substringAfter("Chapter ").toInt()
                    if (next !in chapter..chapter + 1) orderError = "Unexpected chapter: $chapter -> $next"
                    chapter = next
                }
            }
            withContext(Dispatchers.Main) { fixture.player.addListener(listener) }
            try {
                fixture.await("continuous playback started") { fixture.player.isPlaying }
                shell("input keyevent KEYCODE_HOME")
                shell("input keyevent KEYCODE_SLEEP")
                val started = SystemClock.elapsedRealtime()
                var lastAdvance = started
                var previous: Pair<String?, Long>? = null
                var lastReport = 0L
                val report = File(fixture.context.getExternalFilesDir(null), "tts-background-long.jsonl")
                withContext(Dispatchers.IO) { report.writeText("") }
                while (SystemClock.elapsedRealtime() - started < minutes * 60_000) {
                    val now = SystemClock.elapsedRealtime()
                    val position = withContext(Dispatchers.Main) {
                        assertNull(orderError)
                        assertTrue("Active listening lost its foreground service", fixture.service()?.foreground == true)
                        fixture.player.currentMediaItem?.mediaId to fixture.player.currentPosition
                    }
                    if (previous != null && (position.first != previous.first || position.second > previous.second)) lastAdvance = now
                    assertTrue("Playback stalled for 60 seconds", now - lastAdvance < 60_000)
                    previous = position
                    val files = withContext(Dispatchers.IO) {
                        File(fixture.context.cacheDir, "read-aloud").walkTopDown().count { it.isFile && it.extension == "wav" }
                    }
                    assertTrue("Audio buffering grew without bound: $files", files <= 4)
                    if (now - lastReport >= 60_000) {
                        val bookmark = fixture.progress.load(fixture.book.storageKey)!!
                        assertFalse(bookmark.completed)
                        val line = Json.encodeToString(mapOf("elapsedMs" to (now - started).toString(),
                            "chapter" to chapter.toString(), "offset" to bookmark.offset.toString(), "audioFiles" to files.toString()))
                        withContext(Dispatchers.IO) { report.appendText(line + "\n") }
                        android.util.Log.i("ReadAloudAcceptance", line)
                        lastReport = now
                    }
                    delay(1_000)
                }
                assertTrue("Continuous playback must cross chapters", chapter > 1)
            } finally {
                withContext(Dispatchers.Main) { fixture.player.removeListener(listener) }
            }
        }
    }

    private suspend fun withBook(chapterCount: Int = 2, block: suspend (Fixture) -> Unit) {
        val engine = InstrumentationRegistry.getArguments().getString("speechEngine")
        assumeTrue("Supply speechEngine on a dedicated real-engine acceptance device", !engine.isNullOrEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = NextVolDatabase.getInstance(context)
        val data = UserDataRepository(database.userDataDao())
        val stored = data.stringUserData("tts.settings")
        val original = withContext(Dispatchers.IO) { stored.get() }
        val books = LocalBookStore(context, database)
        val originalShelves = withContext(Dispatchers.IO) { database.bookshelfDao().getAllBookshelfIds() }
        val file = File(context.cacheDir, "background-book-${UUID.randomUUID()}.txt")
        var book: SourceBookId? = null
        var draft: LocalBookDraft? = null
        var createdShelf: Int? = null
        var fixture: Fixture? = null
        var activity: ActivityScenario<ReaderLayoutTestActivity>? = null
        val commands = dagger.hilt.android.EntryPointAccessors.fromApplication(context, SpeechDebugEntryPoint::class.java).controller()
        try {
            withContext(Dispatchers.IO) {
                stored.set(Json.encodeToString(SpeechSettings(engine = engine!!, rate = 1.4f)))
                file.writeText((1..chapterCount).joinToString("\n") { chapter ->
                    "Chapter $chapter\n" + if (chapterCount == 2 && chapter == 2)
                        "The next chapter begins without another tap. The reader can rest while the story continues. This is the end of the book."
                    else (1..10).joinToString(" ") { "The quiet morning carries our story through passage $it." }
                })
            }
            val staged = books.stage(file.toUri()).also { draft = it }
            val parsed = books.preview(staged)
            assertEquals(chapterCount, parsed.chapters.size)
            val published = books.publish(staged, parsed, "Background acceptance", originalShelves.firstOrNull())
            book = published.first
            if (originalShelves.isEmpty()) createdShelf = published.second
            activity = ActivityScenario.launch(ReaderLayoutTestActivity::class.java)
            withContext(Dispatchers.Main) { commands.start(published.first.storageKey, SourceChapterId(published.first, "0").storageKey) }
            val pending = withContext(Dispatchers.Main) {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, ReadAloudService::class.java))).buildAsync()
            }
            val player = withContext(Dispatchers.IO) { pending.get(15, TimeUnit.SECONDS) }
            fixture = Fixture(context, published.first, RepositorySpeechProgressStore(data), activity, player, commands)
            block(fixture)
        } finally {
            val created = fixture
            withContext(Dispatchers.Main) {
                if (created == null || created.service() != null) commands.command(SpeechAction.Stop)
            }
            created?.releasePlayer()
            created?.await("service cleanup") { created.service() == null }
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            activity?.close()
            draft?.let { books.discard(it) }
            book?.let {
                books.delete(it)
                withContext(Dispatchers.IO) { data.remove("tts.progress.${it.fileKey}") }
            }
            withContext(Dispatchers.IO) {
                createdShelf?.let { database.bookshelfDao().deleteBookshelf(it) }
                if (original == null) data.remove("tts.settings") else stored.set(original)
                file.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private class Fixture(
        val context: Context,
        val book: SourceBookId,
        val progress: SpeechProgressStore,
        val activity: ActivityScenario<ReaderLayoutTestActivity>,
        val player: MediaController,
        val commands: ReadAloudController,
    ) {
        private var released = false
        fun service() = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .find { it.service.className == ReadAloudService::class.java.name }
        fun notificationRecord() = context.getSystemService(NotificationManager::class.java).activeNotifications
            .find { it.notification.category == Notification.CATEGORY_TRANSPORT }
        fun notification() = notificationRecord()?.notification

        suspend fun await(description: String, timeout: Long = 20_000, condition: suspend () -> Boolean) {
            val passed = withTimeoutOrNull(timeout) {
                while (!withContext(Dispatchers.Main) { condition() }) delay(50)
                true
            }
            assertEquals("Timed out: $description", true, passed)
            android.util.Log.i("ReadAloudAcceptance", description)
        }

        suspend fun assertPlaybackAdvances(description: String) {
            val before = withContext(Dispatchers.Main) { player.currentMediaItem?.mediaId to player.currentPosition }
            await(description) {
                player.isPlaying && service()?.foreground == true &&
                    (player.currentMediaItem?.mediaId != before.first || player.currentPosition > before.second + 700)
            }
        }

        suspend fun setTimer(minutes: Int?): Long? {
            val previous = commands.state.value.sleepTimerDeadline
            withContext(Dispatchers.Main) { commands.setSleepTimer(minutes) }
            await("timer set to $minutes") {
                val deadline = commands.state.value.sleepTimerDeadline
                if (minutes == null) deadline == null else deadline != null && deadline != previous
            }
            return commands.state.value.sleepTimerDeadline
        }

        suspend fun assertTimerStopped(deadline: Long) {
            await("timer stops playback", (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 10_000) {
                commands.state.value.phase == SpeechPhase.Stopped && !player.isPlaying
            }
            assertTrue("Timer must not expire early", SystemClock.elapsedRealtime() >= deadline)
            assertNull(commands.state.value.sleepTimerDeadline)
            releasePlayer()
            await("timer releases service, notification and audio") {
                service() == null && notification() == null &&
                    File(context.cacheDir, "read-aloud").listFiles().orEmpty().isEmpty()
            }
        }

        suspend fun releasePlayer() = withContext(Dispatchers.Main) {
            if (!released) { player.release(); released = true }
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
}
