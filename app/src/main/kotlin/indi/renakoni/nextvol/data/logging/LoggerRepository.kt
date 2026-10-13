package indi.renakoni.nextvol.data.logging

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
