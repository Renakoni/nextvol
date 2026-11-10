package indi.renakoni.nextvol.ui.book.detail

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.export.ExportType
import indi.renakoni.nextvol.data.work.CacheBookWork
import indi.renakoni.nextvol.data.work.ExportBookToEPUBWork
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DetailExportTest {
    @Test fun completionWaitsForUiConsumptionAndDoesNotReappearAfterSharing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = mockk<BookRepository> { every { downloadGeneration() } returns 7 }
        val infos = MutableStateFlow<List<WorkInfo>>(emptyList())
        val request = slot<OneTimeWorkRequest>()
        val operation = mockk<Operation> { every { result } returns Futures.immediateFuture(Operation.SUCCESS) }
        val work = mockk<WorkManager> {
            every { enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, capture(request)) } returns operation
            every { getWorkInfosForUniqueWorkFlow(any()) } returns infos
        }
        val model = DetailViewModel(repository, mockk(), mockk(), work, mockk(), mockk())
        val store = ViewModelStore().apply { put("detail", model) }
        try {
            (model.uiState as MutableDetailUiState).readingAvailable = true
            model.exportSettings = ExportSettings(setOf("v1", "v2"), includeImages = false, exportType = ExportType.VOLUMES)
            model.startEpubExport(BookIdentity.bookKey("1"), "Book")
            runCurrent()
            assertNull(request.captured.workSpec.input.getString("uri"))
            val input = request.captured.workSpec.input
            assertEquals(BookIdentity.bookKey("1"), input.getString("bookId"))
            assertEquals("Book", input.getString("title"))
            assertEquals(7L, input.getLong("downloadGeneration", 0))
            assertEquals("VOLUMES", input.getString("exportType"))
            assertEquals("v1,v2", input.getString("selectedVolume"))
            assertFalse(input.getBoolean("includeImages", true))
            assertEquals(ExportBookToEPUBWork::class.java.name, request.captured.workSpec.workerClassName)
            assertTrue(CacheBookWork.generationTag(7) in request.captured.tags)
            verify { work.enqueueUniqueWork(ExportBookToEPUBWork.ofId(BookIdentity.bookKey("1")), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
            verify { work.getWorkInfosForUniqueWorkFlow(ExportBookToEPUBWork.ofId(BookIdentity.bookKey("1"))) }
            val completed = mockk<WorkInfo> { every { state } returns WorkInfo.State.SUCCEEDED }
            infos.value = listOf(completed)
            runCurrent()
            assertSame(completed, model.exportResult)
            assertEquals(0, infos.subscriptionCount.value)
            model.clearExportResult()
            infos.value = emptyList()
            infos.value = listOf(completed)
            runCurrent()
            assertNull(model.exportResult)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
