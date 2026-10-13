package indi.renakoni.nextvol.data.logging

import android.os.Build
import android.util.Log
import indi.renakoni.nextvol.BuildConfig
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.regex.Pattern

internal fun buildLogReport(logEntries: List<LogEntry>): String = buildString {
    append(buildReportHeader())
    append("\n")
    append("\n----- beginning of logs\n\n")
    append(logEntries.joinToString("\n") { it.text })
    append("\n----- end of logcat")
}

internal fun buildCrashReport(thread: Thread, throwable: Throwable, logEntries: List<LogEntry>): String = buildString {
    append(buildReportHeader())
    append("\n----- start of panic\n")
    append("Thread: $thread\n\n")
    append(formatThrowable(throwable) + "\n\n")
    append("Logcat: \n\n")
    append(logEntries.joinToString("\n") { it.text })
    append("\n----- end of logcat")
}

private fun formatThrowable(throwable: Throwable): String {
    var format = throwable.javaClass.name
    val message = throwable.message
    if (!message.isNullOrBlank()) {
        format += ": $message"
    }
    format += "\n"

    format += throwable.stackTrace.joinToString("\n") {
        "    at ${it.className}.${it.methodName}(${it.fileName}:${if (it.isNativeMethod) "native" else it.lineNumber})"
    }

    val cause = throwable.cause
    if (cause != null) {
        format += "\n\nCaused by: " + formatThrowable(cause)
    }

    return format
}

private fun buildReportHeader(): String {
    val report = StringBuilder()
    report.append("NextVol ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    if (BuildConfig.DEBUG) report.append(", DEBUG build")
    report.append("\n")

    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)
    report.append("Date: ${sdf.format(Date())}\n\n")

    report.append(
        """
        OS_VERSION: ${getSystemPropertyWithAndroidAPI()}
        SDK_INT: ${Build.VERSION.SDK_INT}
        RELEASE: ${Build.VERSION.RELEASE}
        ID: ${Build.ID}
        DISPLAY: ${Build.DISPLAY}
        INCREMENTAL: ${Build.VERSION.INCREMENTAL}
        
        """.trimIndent()
    )

    val systemProperties = getSystemProperties()

    report.append(
        """
        SECURITY_PATCH: ${systemProperties.getProperty("ro.build.version.security_patch")}
        IS_DEBUGGABLE: ${systemProperties.getProperty("ro.debuggable")}
        IS_EMULATOR: ${systemProperties.getProperty("ro.boot.qemu")}
        IS_TREBLE_ENABLED: ${systemProperties.getProperty("ro.treble.enabled")}
        
        """.trimIndent()
    )

    report.append(
        """
        TYPE: ${Build.TYPE}
        TAGS: ${Build.TAGS}

        MANUFACTURER: ${Build.MANUFACTURER}
        BRAND: ${Build.BRAND}
        MODEL: ${Build.MODEL}
        PRODUCT: ${Build.PRODUCT}
        BOARD: ${Build.BOARD}
        HARDWARE: ${Build.HARDWARE}
        DEVICE: ${Build.DEVICE}
        SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.filter { it.isNotBlank() }.joinToString(", ")}
        
        """.trimIndent()
    )

    return report.toString()
}

private fun getSystemPropertyWithAndroidAPI(): String? {
    return try {
        System.getProperty("os.version")
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun getSystemProperties(): Properties {
    val systemProperties = Properties()

    val propertiesPattern = Pattern.compile("^\\[([^]]+)]: \\[(.+)]$")
    try {
        val process = ProcessBuilder().command("/system/bin/getprop")
            .redirectErrorStream(true)
            .start()
        val inputStream = process.inputStream
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        var line: String?
        var key: String
        var value: String
        while (bufferedReader.readLine().also { line = it } != null) {
            val matcher = propertiesPattern.matcher(line.toString())
            if (matcher.matches()) {
                key = matcher.group(1)!!
                value = matcher.group(2)!!
                if (key.isNotEmpty() && value.isNotEmpty()) systemProperties[key] = value
            }
        }
        bufferedReader.close()
        process.destroy()
    } catch (e: IOException) {
        Log.e(
            "LogUtils", "Failed to get system properties!", e,
        )
    }
    return systemProperties
}
