package indi.renakoni.nextvol.data.logging

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class LogFileSharer @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun share(logFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", logFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("log", uri)
        }
        context.startActivity(intent)
    }
}
