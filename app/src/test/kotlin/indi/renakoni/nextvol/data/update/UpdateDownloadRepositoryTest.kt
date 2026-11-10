package indi.renakoni.nextvol.data.update

import android.app.Application
import android.content.ContextWrapper
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.mockk.*
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDownloadRepositoryTest {
    @get:Rule val directory = TemporaryFolder()
    private val server = MockWebServer()
    private val dao = mockk<UserDataDao>()
    private val system = mockk<AndroidUpdateInteraction>(relaxed = true)
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getCacheDir() = directory.root
    } }
    private val downloader by lazy { spyk(UpdateArtifactDownloader(context)) }
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val started = CompletableDeferred<Unit>()
    private val main = Executors.newSingleThreadExecutor { Thread(it, "update-install-test") }.asCoroutineDispatcher()
    private var installationThread: Thread? = null
    private lateinit var repository: UpdateCheckRepository

    @Before fun prepare() {
        Dispatchers.setMain(main)
        server.start()
        coEvery { dao.get(any()) } returns null
        coEvery { dao.get(UserDataPath.Settings.App.AutoCheckUpdate.path) } returns "false"
        mockkObject(NextvolApiParser)
        mockkStatic(::validateUpdateApk)
        every { validateUpdateApk(any(), any(), any()) } just Runs
        every { system.createNotificationChannel() } answers { events += "channel"; started.complete(Unit); Unit }
        every { system.showDownloadNotification(any(), any()) } answers { events += "progress:${firstArg<Int>()}"; Unit }
        every { system.showDownloadCompleteNotification(any()) } answers { events += "complete"; Unit }
        every { system.showDownloadFailedNotification(any()) } answers { events += "failed:${firstArg<String>()}"; Unit }
        every { system.installApk(any()) } answers {
            events += "install"
            installationThread = Thread.currentThread()
        }
    }

    @After fun close() {
        unmockkObject(NextvolApiParser)
        unmockkStatic(::validateUpdateApk)
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private suspend fun checked() {
        val release = Release(BuildConfig.VERSION_CODE + 1, "fixture", "", server.url("/apk").toString(),
            "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04")
        every { NextvolApiParser.parser(any()) } returns release
        repository = UpdateCheckRepository(context, UserDataRepository(dao), downloader, system)
        repository.check()
        withTimeout(5000) { repository.availableFlow.first { it } }
    }

    private suspend fun finished() {
        withTimeout(5000) {
            started.await()
            repository.isDownloading.first { !it }
        }
    }

    @Test fun concurrentCallsAreRejectedBeforePreparationAndUntilInstallationFinishes() = runBlocking {
        checked()
        val files = downloader.prepare()
        files.apk.writeText("previous apk")
        files.temporary.writeText("previous temporary file")
        clearMocks(downloader, answers = false)
        val preparing = CompletableDeferred<Unit>()
        val prepareFiles = CountDownLatch(1)
        val installing = CompletableDeferred<Unit>()
        val finishInstallation = CountDownLatch(1)
        every { downloader.prepare() } answers {
            preparing.complete(Unit)
            check(prepareFiles.await(5, TimeUnit.SECONDS))
            callOriginal()
        }
        every { system.installApk(any()) } answers {
            events += "install"
            installationThread = Thread.currentThread()
            installing.complete(Unit)
            check(finishInstallation.await(5, TimeUnit.SECONDS))
        }
        server.enqueue(MockResponse().setBody("apk"))
        val firstCall = async(Dispatchers.Default) { repository.downloadUpdate() }
        try {
            withTimeout(5000) { preparing.await() }
            val secondCall = async(Dispatchers.Default) { repository.downloadUpdate() }
            withTimeout(5000) { secondCall.await() }
            assertTrue(repository.isDownloading.value)
            repository.check()
            verify(exactly = 1) { NextvolApiParser.parser(any()); downloader.prepare() }
            assertNotNull(repository.release)
            assertEquals(0, server.requestCount)
            assertEquals("previous apk", files.apk.readText())
            assertEquals("previous temporary file", files.temporary.readText())

            prepareFiles.countDown()
            withTimeout(5000) { installing.await() }
            assertTrue(repository.isDownloading.value)
            repository.downloadUpdate()
            verify(exactly = 1) { downloader.prepare(); system.installApk(any()) }
            assertEquals("apk", files.apk.readText())
        } finally {
            prepareFiles.countDown()
            finishInstallation.countDown()
        }
        withTimeout(5000) { firstCall.await() }
        finished()
        assertEquals(1, server.requestCount)
        assertEquals(listOf("channel", "progress:0", "progress:100", "complete", "install"), events)
        assertSame(withContext(main) { Thread.currentThread() }, installationThread)
        assertEquals(1f, repository.downloadProgress.value)
        assertEquals(R.string.update_phase_download_complete, repository.updatePhase.first().messageId)
        verify(exactly = 1) { system.showDownloadCompleteNotification(any()) }
        verify(exactly = 0) { system.showDownloadFailedNotification(any()) }
    }

    @Test fun preparationFailureReportsAnErrorWithoutStartingARequest() = runBlocking {
        checked()
        every { downloader.prepare() } throws IOException("storage unavailable")
        repository.downloadUpdate()
        finished()
        assertEquals(0, server.requestCount)
        assertEquals(R.string.update_phase_download_failed, repository.updatePhase.first().messageId)
        assertEquals(listOf("storage unavailable"), repository.updatePhase.first().arguments)
        verify(exactly = 1) { system.showDownloadFailedNotification(any()) }
        verify(exactly = 0) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
        coVerify(exactly = 0) { downloader.download(any(), any(), any()) }
    }

    @Test fun cancellationAfterBlockingDownloadDoesNotExposeAnInstaller() = runBlocking {
        checked()
        val downloading = CompletableDeferred<Job>()
        val finishDownload = CountDownLatch(1)
        coEvery { downloader.download(any(), any(), any()) } coAnswers {
            downloading.complete(currentCoroutineContext().job)
            check(finishDownload.await(5, TimeUnit.SECONDS))
            File(directory.root, "cancelled.apk").apply { writeText("apk") }
        }
        repository.downloadUpdate()
        val job = withTimeout(5000) { downloading.await() }
        try {
            job.cancel()
            assertTrue(repository.isDownloading.value)
            repository.downloadUpdate()
            verify(exactly = 1) { downloader.prepare() }
        } finally { finishDownload.countDown() }
        finished()
        verify(exactly = 0) {
            system.showDownloadFailedNotification(any())
            system.showDownloadCompleteNotification(any())
            system.installApk(any())
        }
    }

    @Test fun rejectedApkIsDeletedBeforeAnyInstallerOrCompletionNotification() = runBlocking {
        checked()
        every { validateUpdateApk(any(), any(), any()) } throws IOException("wrong signer")
        server.enqueue(MockResponse().setBody("apk"))
        repository.downloadUpdate()
        finished()
        assertEquals(R.string.update_phase_download_failed, repository.updatePhase.first().messageId)
        assertFalse(directory.root.resolve("updates/NextVol-update.apk").exists())
        verify(exactly = 0) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
    }

    @Test fun emptyDownloadResetsBusyAndDoesNotInstall() = runBlocking {
        checked()
        server.enqueue(MockResponse().setBody(""))
        repository.downloadUpdate()
        finished()
        assertEquals(R.string.update_phase_empty_file, repository.updatePhase.first().messageId)
        assertEquals(listOf("channel", "progress:0", "failed:${context.getString(R.string.update_download_empty_file)}"), events)
        verify(exactly = 0) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
    }

    @Test fun httpFailureStopsProcessingAndAllowsASuccessfulRetry() = runBlocking {
        val code = 503
        checked()
        events.clear()
        val failed = CompletableDeferred<Unit>()
        every { system.showDownloadFailedNotification(any()) } answers {
            events += "failed:${firstArg<String>()}"
            failed.complete(Unit)
            Unit
        }
        server.enqueue(MockResponse().setResponseCode(code).setBody("error page"))
        repository.downloadUpdate()
        withTimeout(5000) { failed.await(); repository.isDownloading.first { !it } }
        val phase = repository.updatePhase.first()
        assertEquals(R.string.update_phase_download_failed, phase.messageId)
        assertEquals(listOf("HTTP $code"), phase.arguments)
        assertEquals(listOf("channel", "progress:0",
            "failed:${context.getString(R.string.update_phase_download_failed, "HTTP $code")}"), events)
        assertFalse(directory.root.resolve("updates/NextVol-update.tmp").exists())
        assertFalse(directory.root.resolve("updates/NextVol-update.apk").exists())
        verify(exactly = 0) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
        events.clear()
        val installed = CompletableDeferred<Unit>()
        every { system.installApk(any()) } answers { events += "install"; installed.complete(Unit); Unit }
        server.enqueue(MockResponse().setBody("apk"))
        repository.downloadUpdate()
        withTimeout(5000) { installed.await(); repository.isDownloading.first { !it } }
        assertEquals(R.string.update_phase_download_complete, repository.updatePhase.first().messageId)
        assertEquals(listOf("complete", "install"), events.takeLast(2))
        assertEquals("apk", directory.root.resolve("updates/NextVol-update.apk").readText())
    }
}
