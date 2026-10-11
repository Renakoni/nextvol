package indi.renakoni.nextvol.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.SettableFuture
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.ExternalFiles
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ExternalFileViewModelTest {
    private val context = RuntimeEnvironment.getApplication()
    private val work = mockk<WorkManager>(relaxed = true)
    private val models = ViewModelStore()
    private var count = 0

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() {
        models.clear()
        context.filesDir.resolve("external-backups").listFiles()?.forEach { it.delete() }
        Dispatchers.resetMain()
    }

    private fun model(state: SavedStateHandle = SavedStateHandle()) = ExternalFileViewModel(context, work, state)
        .also { models.put((count++).toString(), it) }

    private fun backup() = Intent(Intent.ACTION_VIEW, Uri.parse("content://qq-files/NextVolData.nvbackup")).also {
        shadowOf(context.contentResolver).registerInputStream(it.data!!, "backup bytes".byteInputStream())
    }

    private suspend fun awaitIdle(model: ExternalFileViewModel) = withTimeout(5_000) { while (model.busy) delay(10) }

    @Test fun launchIsConsumedOnceAcrossRecreationAndWarmIntentsRemainIndependent() = runBlocking {
        val state = SavedStateHandle()
        val model = model(state)
        val first = backup()
        model.accept(first, initial = true)
        assertSame(first, model.intentFlow.first())
        model.accept(first, initial = true)
        assertNull(withTimeoutOrNull(50) { model.intentFlow.first() })
        val second = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://qq-files/book.txt"))
        model.accept(second)
        assertSame(second, model.intentFlow.first())
        val recreated = model(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        recreated.accept(Intent(first).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), initial = true)
        assertNull(withTimeoutOrNull(50) { recreated.intentFlow.first() })
        val freshLaunch = model(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        freshLaunch.accept(first, initial = true)
        assertSame(first, freshLaunch.intentFlow.first())
    }

    @Test fun openingABackupOnlyStagesItAndCancelRemovesThePrivateCopy() = runBlocking {
        val model = model()
        model.open(backup(), bookVisible = false)
        awaitIdle(model)
        val staged = ExternalFiles.backupFile(context, model.backupName!!)
        assertEquals("backup bytes", staged.readText())
        verify(exactly = 0) { work.enqueue(any<OneTimeWorkRequest>()) }
        model.dismissBackup()
        assertFalse(staged.exists())
        assertNull(model.backupName)
    }

    @Test fun activePreviewIsNotReplacedAndRestoreOnlyEnqueuesAfterConfirmation() = runBlocking {
        val model = model()
        val incoming = backup()
        model.open(incoming, bookVisible = true)
        assertFalse(model.busy)
        assertNull(model.backupName)
        model.open(incoming, bookVisible = false)
        awaitIdle(model)
        val name = model.backupName!!
        model.open(backup(), bookVisible = false)
        assertEquals(name, model.backupName)
        val request = slot<OneTimeWorkRequest>()
        every { work.getWorkInfoByIdFlow(any()) } returns MutableStateFlow(mockk {
            every { state } returns WorkInfo.State.ENQUEUED
        })
        every { work.enqueue(capture(request)) } returns operation(SettableFuture.create<Operation.State.SUCCESS>().apply { set(Operation.SUCCESS) })
        model.restore(overwrite = false)
        model.restore(overwrite = true)
        verify(exactly = 1) { work.enqueue(any<OneTimeWorkRequest>()) }
        assertEquals(name, request.captured.workSpec.input.getString(ExternalFiles.STAGED_BACKUP))
        assertFalse(request.captured.workSpec.input.getBoolean("overwrite", true))
        model.dismissBackup()
        assertEquals(name, model.backupName)
    }

    private fun operation(future: SettableFuture<Operation.State.SUCCESS>): Operation = mockk {
        every { result } returns future
    }

    @Test fun failedEnqueueLeavesTheBackupAvailableForRetryOrCancel() = runBlocking {
        val state = SavedStateHandle()
        val model = model(state)
        model.open(backup(), bookVisible = false)
        awaitIdle(model)
        val staged = ExternalFiles.backupFile(context, model.backupName!!)
        val enqueued = SettableFuture.create<Operation.State.SUCCESS>()
        every { work.enqueue(any<OneTimeWorkRequest>()) } returns operation(enqueued)
        every { work.getWorkInfoByIdFlow(any()) } returns MutableStateFlow(null)
        model.restore(false)
        enqueued.setException(IOException("Work database is full"))
        assertFalse(model.restoring)
        assertNull(state.get<String>("restoreWork"))
        assertTrue(staged.exists())
        assertEquals(R.string.data_import_failed, withTimeout(1_000) { model.messageFlow.first() })
        model.dismissBackup()
        assertFalse(staged.exists())
    }

    @Test fun missingRestoredWorkDoesNotTrapTheUserInAnImportingDialog() = runBlocking {
        val staged = ExternalFiles.stageBackup(context, backup().data!!)
        val state = SavedStateHandle(mapOf("backupName" to staged.name, "restoreWork" to UUID.randomUUID().toString()))
        every { work.getWorkInfoByIdFlow(any()) } returns MutableStateFlow(null)
        val model = model(state)
        assertFalse(model.restoring)
        assertNull(state.get<String>("restoreWork"))
        assertEquals(staged.name, model.backupName)
        assertTrue(staged.exists())
        assertEquals(R.string.data_import_failed, withTimeout(1_000) { model.messageFlow.first() })
    }

    @Test fun restoreWaitsForEnqueueBeforeObservingAndCleansUpOnlyAfterCompletion() = runBlocking {
        val model = model()
        model.open(backup(), bookVisible = false)
        awaitIdle(model)
        val staged = ExternalFiles.backupFile(context, model.backupName!!)
        val enqueued = SettableFuture.create<Operation.State.SUCCESS>()
        every { work.enqueue(any<OneTimeWorkRequest>()) } returns operation(enqueued)
        val info = MutableStateFlow<WorkInfo?>(null)
        every { work.getWorkInfoByIdFlow(any()) } returns info
        model.restore(false)
        verify(exactly = 0) { work.getWorkInfoByIdFlow(any()) }
        assertTrue(model.restoring)
        info.value = mockk { every { state } returns WorkInfo.State.RUNNING }
        enqueued.set(Operation.SUCCESS)
        assertTrue(staged.exists())
        info.value = mockk { every { state } returns WorkInfo.State.SUCCEEDED }
        assertFalse(model.restoring)
        assertNull(model.backupName)
        assertFalse(staged.exists())
        assertEquals(R.string.data_import_success, withTimeout(1_000) { model.messageFlow.first() })
    }
}
