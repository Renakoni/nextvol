package indi.renakoni.nextvol.data.logging

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class CrashLogHandler (
    @param:ApplicationContext @field:ApplicationContext private val context: Context,
    private val loggerRepository: LoggerRepository
): Thread.UncaughtExceptionHandler {
    private val logsDir = File(context.cacheDir, "logs")

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Log.e("LogUtils", "uncaughtException", throwable)

        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "lnr_panic_${sdf.format(Date())}.log"
        val logFile = File(logsDir.also { it.mkdirs() }, fileName)

        val logText = buildCrashReport(thread, throwable, loggerRepository.realTimeLogEntries)
        logFile.writeText(logText)
        exitProcess(1)
    }
}
