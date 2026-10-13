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
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private var installationThread: String? = null
    private lateinit var repository: UpdateCheckRepository

    @Before fun prepare() {
        Dispatchers.setMain(main)
        server.start()
        coEvery { dao.get(any()) } returns null
        coEvery { dao.get(UserDataPath.Settings.App.AutoCheckUpdate.path) } returns "false"
        mockkObject(APIParser.BetaParser)
        every { system.createNotificationChannel() } answers { events += "channel"; started.complete(Unit); Unit }
        every { system.showDownloadNotification(any(), any()) } answers { events += "progress:${firstArg<Int>()}"; Unit }
        every { system.showDownloadCompleteNotification(any()) } answers { events += "complete"; Unit }
        every { system.showDownloadFailedNotification(any()) } answers { events += "failed:${firstArg<String>()}"; Unit }
        every { system.installApk(any()) } answers {
            events += "install"
            installationThread = Thread.currentThread().name
        }
    }

    @After fun close() {
        unmockkObject(APIParser.BetaParser)
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private suspend fun checked(transform: ((File, File) -> Unit)? = null) {
        val release = APIParser.APIRelease(BuildConfig.VERSION_CODE + 1, "fixture", "", server.url("/apk").toString(), transform)
        every { APIParser.BetaParser.parser(any()) } returns release
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

    @Test fun realDownloadKeepsBusyProgressCompletionAndInstallationOrder() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val response = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested.complete(Unit)
                check(response.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody("apk")
            }
        }
        checked()
        try {
            repository.downloadUpdate()
            withTimeout(5000) { requested.await() }
            assertTrue(repository.isDownloading.value)
            assertEquals(R.string.update_phase_downloading, repository.updatePhase.first().messageId)
            assertEquals(0f, repository.downloadProgress.value)
            repository.downloadUpdate()
            verify(exactly = 1) { downloader.prepare() }
        } finally { response.countDown() }
        finished()
        assertEquals(1, server.requestCount)
        assertEquals(listOf("channel", "progress:0", "progress:100", "complete", "install"), events)
        assertEquals("update-install-test", installationThread)
        assertEquals(1f, repository.downloadProgress.value)
        assertEquals(R.string.update_phase_download_complete, repository.updatePhase.first().messageId)
        assertEquals("apk", directory.root.resolve("updates/LightNovelReader-update.apk").readText())
    }

    @Test fun unknownLengthCompletesWithoutIntermediateProgressNotifications() = runBlocking {
        checked()
        server.enqueue(MockResponse().setChunkedBody("apk", 1))
        repository.downloadUpdate()
        finished()
        assertEquals(listOf("channel", "progress:0", "complete", "install"), events)
        assertEquals(1f, repository.downloadProgress.value)
    }

    @Test fun conversionKeepsTheProcessingPhaseUntilTheArtifactIsReady() = runBlocking {
        val processing = CompletableDeferred<Unit>()
        val finishConversion = CountDownLatch(1)
        checked { source, target ->
            processing.complete(Unit)
            check(finishConversion.await(5, TimeUnit.SECONDS))
            target.writeText("converted ${source.readText()}")
        }
        server.enqueue(MockResponse().setBody("archive"))
        try {
            repository.downloadUpdate()
            withTimeout(5000) { processing.await() }
            assertTrue(repository.isDownloading.value)
            assertEquals(R.string.update_phase_processing, repository.updatePhase.first().messageId)
            verify(exactly = 0) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
        } finally { finishConversion.countDown() }
        finished()
        assertEquals("converted archive", directory.root.resolve("updates/LightNovelReader-update.apk").readText())
        assertEquals(listOf("complete", "install"), events.takeLast(2))
        verify(exactly = 1) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
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

    @Test fun missingApkUsesTheExistingFailurePhaseAndDoesNotInstall() = runBlocking {
        checked { source, _ -> throw MissingUpdateApkException(source.name) }
        server.enqueue(MockResponse().setBody("archive"))
        repository.downloadUpdate()
        finished()
        val phase = repository.updatePhase.first()
        assertEquals(R.string.update_phase_missing_apk, phase.messageId)
        assertEquals(listOf("LightNovelReader-update.tmp"), phase.arguments)
        assertEquals(1, events.count { it.startsWith("failed:") })
        verify(exactly = 0) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
    }

    // Preserve the known failure overwrite until the separate HTTP bug fix lands.
    @Test fun httpFailureRetainsTheExistingFailureThenEmptyFileSequence() = runBlocking {
        checked()
        server.enqueue(MockResponse().setResponseCode(503))
        repository.downloadUpdate()
        finished()
        assertEquals(listOf("channel", "progress:0", "failed:HTTP 503",
            "failed:${context.getString(R.string.update_download_empty_file)}"), events)
        assertEquals(R.string.update_phase_empty_file, repository.updatePhase.first().messageId)
        verify(exactly = 0) { system.installApk(any()); system.showDownloadCompleteNotification(any()) }
    }

    // Characterization of the existing pre-launch busy-check window, not a new concurrency guarantee.
    @Test fun callsThatPassTheBusyCheckBeforeLaunchCanStillOverlap() = runBlocking {
        checked()
        val files = downloader.prepare()
        val firstPreparing = CompletableDeferred<Unit>()
        val releasePreparation = CountDownLatch(1)
        val preparations = AtomicInteger()
        val jobs = CopyOnWriteArrayList<Job>()
        val bothStarted = CompletableDeferred<Unit>()
        val releaseDownloads = CompletableDeferred<Unit>()
        every { downloader.prepare() } answers {
            if (preparations.getAndIncrement() == 0) {
                firstPreparing.complete(Unit)
                check(releasePreparation.await(5, TimeUnit.SECONDS))
            }
            files
        }
        coEvery { downloader.download(any(), any(), any(), any(), any()) } coAnswers {
            jobs += currentCoroutineContext()[Job]!!
            if (jobs.size == 2) bothStarted.complete(Unit)
            releaseDownloads.await()
            null
        }
        val firstCall = CompletableFuture.runAsync { repository.downloadUpdate() }
        try {
            withTimeout(5000) { firstPreparing.await() }
            repository.downloadUpdate()
            withTimeout(5000) { started.await() }
            releasePreparation.countDown()
            firstCall.get(5, TimeUnit.SECONDS)
            withTimeout(5000) { bothStarted.await() }
            assertEquals(2, events.count { it == "channel" })
        } finally {
            releasePreparation.countDown()
            releaseDownloads.complete(Unit)
            firstCall.get(5, TimeUnit.SECONDS)
            withTimeout(5000) { jobs.toList().joinAll() }
        }
        assertFalse(repository.isDownloading.value)
    }
}
