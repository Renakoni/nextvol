package indi.renakoni.nextvol.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import indi.renakoni.nextvol.data.backup.BackupArchive
import indi.renakoni.nextvol.data.localbook.LocalBookFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class ExternalFile(val uri: Uri, val name: String, val bookFormat: LocalBookFormat?)

object ExternalFiles {
    const val BACKUP_MIME = "application/vnd.nextvol.backup"
    const val STAGED_BACKUP = "stagedExternalBackup"

    fun uri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        else -> null
    }?.takeIf { it.scheme == "content" || it.scheme == "file" }

    suspend fun resolve(resolver: ContentResolver, intent: Intent): ExternalFile? = withContext(Dispatchers.IO) {
        val uri = uri(intent) ?: return@withContext null
        val name = (runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()).substringAfterLast('/').substringAfterLast('\\')
        // The provider's display name is authoritative when a sender labels every file as text or ZIP.
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "epub" -> ExternalFile(uri, name, LocalBookFormat.EPUB)
            "txt" -> ExternalFile(uri, name, LocalBookFormat.TXT)
            "nvbackup", "lnr" -> ExternalFile(uri, name, null)
            else -> when (intent.type?.takeUnless { it in setOf("*/*", "application/octet-stream", "application/zip", "application/x-zip-compressed") }
                ?: runCatching { resolver.getType(uri) }.getOrNull()) {
                "application/epub+zip" -> ExternalFile(uri, name, LocalBookFormat.EPUB)
                "text/plain" -> ExternalFile(uri, name, LocalBookFormat.TXT)
                BACKUP_MIME -> ExternalFile(uri, name, null)
                else -> null
            }
        }
    }

    fun backupFile(context: Context, name: String): File {
        require(name.matches(Regex("[0-9a-f-]{36}\\.nvbackup")))
        return File(context.filesDir, "external-backups/$name")
    }

    suspend fun stageBackup(context: Context, uri: Uri): File {
        val file = backupFile(context, "${UUID.randomUUID()}.nvbackup")
        try {
            return withContext(Dispatchers.IO) {
                val caller = currentCoroutineContext()
                if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw IOException("Cannot stage backup")
                (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open backup")).use { input ->
                    file.outputStream().use { output ->
                        BackupArchive.copyLimited(input, output, BackupArchive.MAX_ARCHIVE_BYTES) { caller.ensureActive() }
                    }
                }
                caller.ensureActive()
                file
            }
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }
}
