package indi.renakoni.nextvol.data.logging

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.messageResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class LoggerRepository @Inject constructor(
    @field:ApplicationContext private val context: Context,
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val logsDir = File(context.cacheDir, "logs")
    var logLevel: LogLevel = LogLevel.NONE

    val fileLogEntries = mutableStateListOf<LogEntry>()
    val realTimeLogEntries = mutableStateListOf<LogEntry>()

    @Volatile var speechDebugEnabled: Boolean = false
    private val speechRecords = MutableStateFlow<List<LogEntry>>(emptyList())
    val speechLogEntries = speechRecords.asStateFlow()

    fun recordSpeech(record: SpeechLogRecord) {
        if (!speechDebugEnabled && record.level != LogLevel.ERROR) return
        // One shared per-record budget bounds both the live list and its exported representation.
        var remaining = 16_384
        var truncated = record.truncated
        fun bounded(value: String): String {
            val result = value.take(remaining)
            remaining -= result.length
            if (result.length != value.length) truncated = true
            return result
        }
        val subject = record.subject?.let(::bounded)
        val voice = record.voice?.let { it.copy(label = bounded(it.label)) }
        val details = record.details.mapNotNull { (label, value) ->
            if (remaining == 0) { truncated = true; null } else label to bounded(value)
        }
        val entry = speechLogEntry(record.copy(voice = voice, subject = subject, details = details, truncated = truncated))
        speechRecords.update { (it + entry).takeLast(1000) }
    }

    fun speechLogEntry(record: SpeechLogRecord): LogEntry {
        val stage = when (record.stage) {
            SpeechLogStage.Import -> R.string.tts_diagnostic_import
            SpeechLogStage.Configuration -> R.string.tts_diagnostic_configuration
            SpeechLogStage.Analysis -> R.string.tts_diagnostic_analysis
            SpeechLogStage.Request -> R.string.tts_diagnostic_request
            SpeechLogStage.Initialization -> R.string.tts_diagnostic_initialization
            SpeechLogStage.Audio -> R.string.tts_diagnostic_audio
            SpeechLogStage.Playback -> R.string.tts_diagnostic_playback
        }
        val event = when (record.event) {
            SpeechLogEvent.Started -> R.string.tts_diagnostic_started
            SpeechLogEvent.Completed -> R.string.tts_diagnostic_completed
            SpeechLogEvent.Failed -> R.string.tts_failed
        }
        return LogEntry(buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(record.timestamp)))
            record.processId?.let { append(" #").append(it) }
            record.segment?.let { append('/').append(it) }
            append(' ').append(context.getString(stage)).append(" · ").append(context.getString(event))
            record.error?.let { append('\n').append(context.getString(it.messageResource)) }
            record.subject?.takeIf(String::isNotEmpty)?.let { append('\n').append(it) }
            record.voice?.label?.takeIf(String::isNotEmpty)?.let { append('\n').append(it) }
            record.details.forEach { (label, value) -> append('\n').append(context.getString(label)).append(": ").append(value) }
            if (record.truncated) append('\n').append(context.getString(R.string.tts_log_truncated))
        }, record.level)
    }

    fun speechLogReport(): String = "NextVol listening diagnostics\n" +
        speechRecords.value.joinToString("\n\n") { it.text }

    fun prepareSpeechLogFile(report: String): File {
        val directory = File(logsDir, "speech").also { it.mkdirs() }
        return File(directory, "diagnostics.log").apply { writeText(report) }
    }

    fun deleteSpeechLogs(): Boolean {
        speechRecords.value = emptyList()
        val exported = File(logsDir, "speech/diagnostics.log")
        return !exported.exists() || exported.delete()
    }

    private var loggingJob: Job? = null
    private var fileLoadJob: Job? = null
    private val currentPid = Process.myPid()

    fun startLogging() {
        if (loggingJob?.isActive == true) return
        if (logLevel == LogLevel.NONE) return

        loggingJob = coroutineScope.launch(Dispatchers.IO) {
            Runtime.getRuntime().exec("logcat -c").waitFor()

            val process = Runtime.getRuntime().exec("logcat -v threadtime --pid=$currentPid")
            val reader = process.inputStream.bufferedReader()

            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue

                    parseLine(line)?.let { entry ->
                        withContext(Dispatchers.Main.immediate) {
                            realTimeLogEntries.apply {
                                add(entry)
                                if (size > 1000) removeAt(0)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                reader.close()
                process.destroy()
            }
        }
    }

    private fun parseLine(line: String, filterByLevel: Boolean = true): LogEntry? {
        if (line.isBlank()) return null

        val logcatRegex = Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")
        return if (logcatRegex.containsMatchIn(line)) {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 5) return null

            val priority = when (parts[4].firstOrNull()?.uppercaseChar()) {
                'V' -> LogLevel.VERBOSE
                'D' -> LogLevel.DEBUG
                'I' -> LogLevel.INFO
                'W' -> LogLevel.WARNING
                'E', 'F' -> LogLevel.ERROR
                else -> return null
            }

            if (filterByLevel && priority.level > logLevel.level) return null

            LogEntry(text = line, logLevel = priority)
        } else {
            LogEntry(text = line, logLevel = LogLevel.SYSTEM)
        }
    }


    fun prepareLogFile(fileName: String? = null): File? {
        val logFile = if (fileName != null) {
            File(logsDir, fileName).takeIf { it.exists() }
        } else {
            val logText = buildLogReport(realTimeLogEntries)
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val exportFile = File(logsDir.also { it.mkdirs() }, "lnr_export_${sdf.format(Date())}.log")
            exportFile.writeText(logText)
            exportFile
        }

        if (logFile == null || !logFile.exists()) {
            Log.w("LoggerRepository", "Log file doesn't exist")
            return null
        }

        return logFile
    }


    fun stopLogging() {
        loggingJob?.cancel()
        loggingJob = null
    }

    fun getAvailableLogFiles(): List<String> {
        if (!logsDir.exists()) return emptyList()
        return logsDir.listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()
    }

    fun loadLogFile(fileName: String) {
        fileLoadJob?.cancel()
        fileLogEntries.clear()
        val file = File(logsDir, fileName)

        fileLoadJob = coroutineScope.launch(Dispatchers.IO) {
            val newEntries = try {
                file.readLines().mapNotNull { parseLine(it, filterByLevel = false) }
            } catch (_: IOException) {
                listOf(LogEntry(context.getString(R.string.log_read_failed), LogLevel.ERROR))
            }

            withContext(Dispatchers.Main.immediate) {
                fileLogEntries.clear()
                fileLogEntries.addAll(newEntries)
            }
        }
    }

    fun deleteLogs(): Boolean {
        fileLoadJob?.cancel()
        fileLogEntries.clear()
        realTimeLogEntries.clear()
        val files = getAvailableLogFiles().map { File(logsDir, it) }
        return files.map { !it.exists() || it.delete() }.all { it }
    }
}
