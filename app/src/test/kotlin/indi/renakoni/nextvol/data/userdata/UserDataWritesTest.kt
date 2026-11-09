package indi.renakoni.nextvol.data.userdata

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import indi.renakoni.nextvol.data.local.ForwardingUserDataDao
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.ui.book.reader.ColorPickerDialogViewModel
import indi.renakoni.nextvol.ui.dialog.SliderValueDialogViewModel
import io.nightfish.lightnovelreader.api.userdata.StringUserData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    @Test fun blockedFirstWriteDemonstratesTheOldRaceAndQueuedWrappersPersistInSubmissionOrder() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        for (ordered in listOf(false, true)) {
            val writes = UserDataWrites(dispatcher)
            val firstEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val secondCommitted = CompletableDeferred<Unit>()
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
                    if (value == "B") secondCommitted.complete(Unit)
                }
            }
            val first = UserDataRepository(dao).stringUserData("reader.paperId")
            val second = UserDataRepository(dao).stringUserData(first.path)
            val legacyJobs = mutableListOf<Job>()
            fun submit(data: StringUserData, value: String) {
                if (ordered) data.asynchronousSet(value)
                // Original asynchronousSet algorithm, with its IO dispatcher controlled by the test.
                else legacyJobs += CoroutineScope(dispatcher).launch { data.set(value) }
            }
            try {
                submit(first, "A")
                firstEntered.await()
                submit(second, "B")
                if (ordered) {
                    runCurrent()
                    assertEquals(listOf("A"), entered)
                    assertFalse(secondCommitted.isCompleted)
                } else {
                    secondCommitted.await()
                    assertEquals(listOf("B"), committed)
                }
                release.complete(Unit)
                legacyJobs.joinAll()
                writes.close()
                assertEquals(listOf("A", "B"), entered)
                assertEquals(if (ordered) listOf("A", "B") else listOf("B", "A"), committed)
                assertEquals(if (ordered) "B" else "A", second.get())
            } finally {
                release.complete(Unit)
                legacyJobs.joinAll()
                writes.close()
            }
        }
    }

    @Test fun unrelatedPathsProceedAndFailureDoesNotPreventDrainingLaterSubmissions() = runTest {
        val failures = mutableListOf<Pair<String, Exception>>()
        val writes = UserDataWrites(StandardTestDispatcher(testScheduler)) { path, error -> failures += path to error }
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val error = IllegalStateException("write failed")
        writes.submit("reader.paperId") { release.await(); events += "A" }
        val failed = writes.submit("reader.paperId") { events += "failed"; throw error }
        writes.submit("reader.paperId") { events += "B" }
        val unrelated = writes.submit("reader.fontSize") { events += "font" }
        try {
            runCurrent()
            assertTrue(unrelated.isCompleted)
            assertEquals(listOf("font"), events)
            val closing = launch(start = CoroutineStart.UNDISPATCHED) { writes.close() }
            assertFalse(closing.isCompleted)
            assertThrows(IllegalStateException::class.java) { writes.submit("reader.paperId") {} }
            release.complete(Unit)
            closing.join()
            assertEquals(listOf("font", "A", "failed", "B"), events)
            assertEquals(listOf("reader.paperId" to error), failures)
            try {
                failed.await()
                fail("Awaiting a failed write must report its error")
            } catch (actual: IllegalStateException) {
                assertSame(error, actual)
            }
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

    @Test fun databaseCloseWaitsForAcceptedWritesAndRejectsFurtherSubmissions() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        val dao = object : ForwardingUserDataDao(db.userDataDao()) {
            override suspend fun insert(path: String, group: String, type: String, value: String) {
                if (value == "A") {
                    entered.complete(Unit)
                    release.await()
                }
                super.insert(path, group, type, value)
                committed += value
            }
        }
        val first = UserDataRepository(dao).stringUserData("reader.paperId")
        val second = UserDataRepository(db.userDataDao()).stringUserData(first.path)
        first.asynchronousSet("A")
        entered.await()
        second.asynchronousSet("B")
        // Read B inside the owner before Room closes, rather than querying a closed database.
        val persisted = dao.submit(first.path) { committed += second.get()!! }
        val closing = async(Dispatchers.IO) { db.close() }
        try {
            assertFalse(persisted.isCompleted)
            release.complete(Unit)
            closing.await()
            assertEquals(listOf("A", "B"), committed)
            assertFalse(db.isOpen)
            assertThrows(IllegalStateException::class.java) { first.asynchronousSet("C") }
        } finally {
            release.complete(Unit)
            withContext(Dispatchers.IO) { closing.await() }
        }
    }
}
