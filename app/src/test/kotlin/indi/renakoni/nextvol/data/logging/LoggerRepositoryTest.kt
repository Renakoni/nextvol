package indi.renakoni.nextvol.data.logging

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import indi.renakoni.nextvol.ui.home.settings.logcat.LIVE_LOG_OPTION
import indi.renakoni.nextvol.ui.home.settings.logcat.LogcatViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LoggerRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var repository: LoggerRepository
    private lateinit var logsDir: File
    private val line = "09-20 12:00:00.000  123  456 E Reader: saved failure"

    @Before fun create() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns temporaryFolder.root
        val resources = ApplicationProvider.getApplicationContext<Application>().resources
        every { context.getString(any()) } answers { resources.getString(firstArg()) }
        logsDir = temporaryFolder.newFolder("logs")
        repository = LoggerRepository(context)
    }

    @After fun destroy() {
        repository.stopLogging()
        Dispatchers.resetMain()
    }

    private fun loadArchive(): File {
        val archive = File(logsDir, "lnr_panic_20260920_120000.log").apply { writeText(line) }
        repository.loadLogFile(archive.name)
        runBlocking {
            withTimeout(5_000) {
                while (repository.fileLogEntries.isEmpty()) delay(10)
            }
        }
        return archive
    }

    @Test fun speechLogsKeepProcessingDetailsAndClearIndependentlyOfApplicationLogs() {
        repository.recordSpeech(SpeechLogRecord(SpeechLogStage.Request, SpeechLogEvent.Started))
        assertTrue(repository.speechLogEntries.value.isEmpty())
        val record = SpeechLogRecord(SpeechLogStage.Request, SpeechLogEvent.Failed, SpeechError.HttpAuthentication,
            SpeechVoiceRef(httpSource = "https://example.com?token=private-secret", label = "中文旁白"),
            subject = "小明", details = listOf(R.string.tts_log_final_text to "这是实际处理后的文本。"))
        repeat(1001) { repository.recordSpeech(record.copy(timestamp = it.toLong())) }
        assertEquals(1000, repository.speechLogEntries.value.size)
        assertTrue(repository.realTimeLogEntries.isEmpty())
        val report = repository.speechLogReport()
        assertTrue(report.contains("中文旁白"))
        assertTrue(report.contains("小明"))
        assertTrue(report.contains("这是实际处理后的文本。"))
        assertFalse(report.contains("private-secret"))
        assertFalse(report.contains("https://example.com"))
        assertTrue(report.endsWith(repository.speechLogEntries.value.last().text))
        repository.recordSpeech(record.copy(details = listOf(R.string.tts_log_original to "长".repeat(20_000))))
        val truncated = repository.speechLogEntries.value.last().text
        assertTrue(truncated.contains(ApplicationProvider.getApplicationContext<Application>().getString(R.string.tts_log_truncated)))
        assertFalse(truncated.contains("长".repeat(16_384)))
        assertTrue(repository.speechLogReport().endsWith(truncated))
        val speechFile = repository.prepareSpeechLogFile(report)
        val appFile = File(logsDir, "application.log").apply { writeText("application") }
        assertEquals(listOf(appFile.name), repository.getAvailableLogFiles())
        assertTrue(repository.deleteLogs())
        assertTrue(speechFile.exists())
        assertEquals(1000, repository.speechLogEntries.value.size)
        appFile.writeText("application")
        assertTrue(repository.deleteSpeechLogs())
        assertTrue(appFile.exists())
        assertFalse(speechFile.exists())
        assertTrue(repository.speechLogEntries.value.isEmpty())
    }

    @Test fun savedLogsRemainReadableWhenRecordingIsOff() {
        loadArchive()
        assertTrue(repository.fileLogEntries.any { it.text == line && it.logLevel == LogLevel.ERROR })
    }

    @Test fun deletingAllLogsAlsoClearsTheCurrentRunAndLoadedContent() {
        val archive = loadArchive()
        repository.realTimeLogEntries.add(LogEntry("current run", LogLevel.INFO))
        repository.deleteLogs()
        assertFalse(archive.exists())
        assertTrue(repository.realTimeLogEntries.isEmpty())
        assertTrue(repository.fileLogEntries.isEmpty())
        assertTrue(repository.getAvailableLogFiles().isEmpty())
    }

    @Test fun sharingAnArchiveGrantsReadAccessToItsActualContent() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val archive = File(File(context.cacheDir, "logs").apply { mkdirs() }, "share-test.log")
        try {
            archive.writeText(line)
            // AndroidX uses '/' for canonical roots; Windows paths require the same adapter as EpubShareTest.
            if (File.separatorChar == '\\') {
                mockkStatic(FileProvider::class)
                every { FileProvider.getUriForFile(any(), any(), any<File>()) } answers {
                    val file = thirdArg<File>()
                    Uri.parse("content://${context.packageName}.provider/cache/logs/${file.name}").also {
                        shadowOf(context.contentResolver).registerInputStream(it, file.inputStream())
                    }
                }
            } else {
                val info = context.packageManager.resolveContentProvider("${context.packageName}.provider", 0)!!
                FileProvider().attachInfo(context, info)
            }
            repository = LoggerRepository(context)
            val viewModel = LogcatViewModel(repository, mockk(relaxed = true), LogFileSharer(context))
            viewModel.onSelectLogFile(archive.name)
            viewModel.shareLogs()
            val chooser = shadowOf(context).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!
            assertEquals("indi.renakoni.nextvol.MainActivity", (excluded.single() as android.content.ComponentName).className)
            val intent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("${context.packageName}.provider", uri.authority)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertEquals(line, context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })

            // A selected archive may be cleared before the next share attempt.
            assertTrue(repository.deleteLogs())
            viewModel.shareLogs()
            assertNull(shadowOf(context).nextStartedActivity)
            assertEquals(listOf(LIVE_LOG_OPTION), viewModel.logFilenameList)
        } finally {
            if (File.separatorChar == '\\') unmockkStatic(FileProvider::class)
            repository.deleteLogs()
            archive.delete()
        }
    }

    @Test fun sharingLiveLogsWritesTheReportAndRefreshesTheFileList() {
        val currentLine = "09-20 12:00:01.000  123  456 I Reader: current run"
        repository.realTimeLogEntries.addAll(listOf(
            LogEntry(line, LogLevel.ERROR),
            LogEntry(currentLine, LogLevel.INFO),
        ))
        repository.fileLogEntries.add(LogEntry("previous archive", LogLevel.SYSTEM))
        val sharer = mockk<LogFileSharer>(relaxed = true)
        val viewModel = LogcatViewModel(repository, mockk(relaxed = true), sharer)

        viewModel.shareLogs()

        val sharedFile = slot<File>()
        verify(exactly = 1) { sharer.share(capture(sharedFile)) }
        val export = sharedFile.captured
        assertEquals(logsDir, export.parentFile)
        assertTrue(export.name.matches(Regex("lnr_export_\\d{8}_\\d{6}\\.log")))
        assertEquals(listOf(export.name, LIVE_LOG_OPTION), viewModel.logFilenameList)
        val report = export.readText()
        assertTrue(report.startsWith("NextVol ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        assertTrue(report.contains("\nDate: "))
        assertTrue(report.contains("\nSDK_INT: 30\n"))
        assertTrue(report.contains("\nSUPPORTED_ABIS: "))
        assertTrue(report.endsWith("\n\n----- beginning of logs\n\n$line\n$currentLine\n----- end of logcat"))
        assertFalse(report.contains("previous archive"))
    }
}
