package indi.renakoni.nextvol.data.userdata

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import indi.renakoni.nextvol.data.local.ForwardingUserDataDao
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.ui.book.reader.ColorPickerDialogViewModel
import indi.renakoni.nextvol.ui.book.reader.UserDataReaderFontFamilySettings
import indi.renakoni.nextvol.ui.dialog.SliderValueDialogViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UserDataWritesTest {
    private lateinit var db: NextVolDatabase

    @Before fun openStore() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NextVolDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun closeStore() { db.close() }

    @Test fun queuedWrappersPersistInSubmissionOrderWhenTheFirstWriteIsBlocked() = runTest {
        val writes = UserDataWrites(StandardTestDispatcher(testScheduler))
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val dao = object : ForwardingUserDataDao(db.userDataDao()) {
            override fun submit(path: String, write: suspend () -> Unit) = writes.submit(path, write)
            override suspend fun insert(path: String, group: String, type: String, value: String) {
                entered += value
                if (value == "A") {
                    firstEntered.complete(Unit)
                    release.await()
                }
                super.insert(path, group, type, value)
                committed += value
            }
        }
        val first = UserDataRepository(dao).stringUserData("reader.paperId")
        val second = UserDataRepository(dao).stringUserData(first.path)
        try {
            first.asynchronousSet("A")
            firstEntered.await()
            second.asynchronousSet("B")
            runCurrent()
            assertEquals(listOf("A"), entered)
            release.complete(Unit)
            writes.close()
            assertEquals(listOf("A", "B"), committed)
            assertEquals("B", second.get())
        } finally {
            release.complete(Unit)
            writes.close()
        }
    }

    @Test fun unrelatedPathsProceedAndFailureDoesNotPreventDrainingLaterSubmissions() = runTest {
        val writes = UserDataWrites(StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        writes.submit("reader.paperId") { release.await(); events += "A" }
        writes.submit("reader.paperId") { events += "failed"; throw IllegalStateException("write failed") }
        writes.submit("reader.paperId") { events += "B" }
        writes.submit("reader.fontSize") { events += "font" }
        try {
            runCurrent()
            assertEquals(listOf("font"), events)
            val closing = launch(start = CoroutineStart.UNDISPATCHED) { writes.close() }
            assertFalse(closing.isCompleted)
            assertThrows(IllegalStateException::class.java) { writes.submit("reader.paperId") {} }
            release.complete(Unit)
            closing.join()
            assertEquals(listOf("font", "A", "failed", "B"), events)
        } finally {
            release.complete(Unit)
            writes.close()
        }
    }

    @Test fun dialogsCaptureTargetsBeforeDispatchAndAcceptedWritesSurvivePageExit() = runTest {
        val writes = UserDataWrites(StandardTestDispatcher(testScheduler))
        val submitted = mutableListOf<String>()
        val committed = mutableListOf<Pair<String, String>>()
        val dao = object : ForwardingUserDataDao(db.userDataDao()) {
            override fun submit(path: String, write: suspend () -> Unit) = writes.submit(path, write).also { submitted += path }
            override suspend fun insert(path: String, group: String, type: String, value: String) {
                super.insert(path, group, type, value)
                committed += path to value
            }
        }
        val repository = UserDataRepository(dao)
        val slider = SliderValueDialogViewModel(repository)
        val color = ColorPickerDialogViewModel(repository)
        val pages = ViewModelStore().apply { put("slider", slider); put("color", color) }
        try {
            slider.init("reader.fontSize")
            slider.setValue(16f)
            slider.setValue(18f)
            slider.init("reader.fontLineHeight")
            color.init("reader.backgroundColor")
            color.changeBackgroundColor(Color.Red)
            color.init("reader.textColor")
            pages.clear()
            assertEquals(listOf("reader.fontSize", "reader.fontSize", "reader.backgroundColor"), submitted)
            assertTrue(committed.isEmpty())
            // A synchronous caller for the same setting must follow the already accepted dialog writes.
            repository.floatUserData("reader.fontSize").setAndAwait(20f)
            writes.close()
            assertEquals(listOf("16.0", "18.0", "20.0"), committed.filter { it.first == "reader.fontSize" }.map { it.second })
            assertEquals(20f, repository.floatUserData("reader.fontSize").get())
            assertEquals(Color.Red, repository.colorUserData("reader.backgroundColor").get())
            assertNull(repository.floatUserData("reader.fontLineHeight").get())
            assertNull(repository.colorUserData("reader.textColor").get())
        } finally {
            pages.clear()
            writes.close()
        }
    }

    @Test fun staleFontCleanupDoesNotClearANewerQueuedSelection() = runTest {
        val writes = UserDataWrites(StandardTestDispatcher(testScheduler))
        val dao = object : ForwardingUserDataDao(db.userDataDao()) {
            override fun submit(path: String, write: suspend () -> Unit) = writes.submit(path, write)
        }
        val font = UserDataRepository(dao).uriUserData("reader.fontFamilyUri")
        val missing = Uri.parse("file:///missing.ttf")
        val selected = Uri.parse("file:///selected.ttf")
        val settings = UserDataReaderFontFamilySettings(font)
        try {
            font.set(missing)
            font.asynchronousSet(selected)
            settings.clear(missing)
            assertEquals(selected, font.get())
            settings.clear(selected)
            assertEquals(Uri.EMPTY, font.get())
        } finally { writes.close() }
    }
}
