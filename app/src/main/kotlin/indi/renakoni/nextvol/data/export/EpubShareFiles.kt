package indi.renakoni.nextvol.data.export

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import indi.renakoni.nextvol.R
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Completed archives only. Offline reading content has a separate, persistent store. */
object EpubShareFiles {
    private const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun directory(context: Context, id: UUID) = File(context.cacheDir, "epub-shares/$id")

    fun publish(context: Context, id: UUID, staging: File, checkActive: () -> Unit = {}) {
        val target = directory(context, id)
        val root = target.parentFile!!
        check(root.isDirectory || root.mkdirs()) { "Cannot create share directory" }
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        root.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val files = staging.listFiles().orEmpty().filter { it.isFile && it.extension == "epub" && it.length() > 0 }.sortedBy { it.name }
        check(files.isNotEmpty())
        if (files.size > 1) {
            // Chat apps commonly accept SEND for files but restrict SEND_MULTIPLE to images.
            ZipOutputStream(File(staging, "${files.first().nameWithoutExtension} +${files.size - 1}.zip").outputStream()).use { zip ->
                val buffer = ByteArray(64 * 1024)
                for (file in files) {
                    checkActive()
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { input ->
                        while (true) {
                            checkActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            zip.write(buffer, 0, size)
                        }
                    }
                }
            }
        }
        File(staging, ".count").writeText(files.size.toString())
        checkActive()
        check(!target.exists() && staging.renameTo(target)) { "Cannot publish EPUB files" }
        target.setLastModified(System.currentTimeMillis())
    }

    fun files(context: Context, id: UUID): List<File> {
        val directory = directory(context, id)
        val count = runCatching { File(directory, ".count").readText().toIntOrNull() }.getOrNull()
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "epub" && it.length() > 0 }
        // Cache eviction may remove only one volume. Never silently share a partial selection.
        return if (count != null && count > 0 && files.size == count) files.sortedBy { it.name } else emptyList()
    }

    fun wasShared(context: Context, id: UUID) = File(directory(context, id), ".shared").isFile

    fun markShared(context: Context, id: UUID) {
        // Keep files after the chooser closes: recipients may open the stream later.
        runCatching { File(directory(context, id), ".shared").createNewFile() }
        directory(context, id).setLastModified(System.currentTimeMillis())
    }

    fun chooser(context: Context, id: UUID): Intent {
        val files = files(context, id)
        require(files.isNotEmpty()) { "The temporary export has expired" }
        val file = if (files.size == 1) files.single() else
            requireNotNull(directory(context, id).listFiles()?.singleOrNull { it.extension == "zip" && it.length() > 0 })
        val mime = if (files.size == 1) "application/epub+zip" else "application/zip"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)
            clipData = ClipData(ClipDescription(file.name, arrayOf(mime)), ClipData.Item(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(share, context.getString(R.string.epub_export_share)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
