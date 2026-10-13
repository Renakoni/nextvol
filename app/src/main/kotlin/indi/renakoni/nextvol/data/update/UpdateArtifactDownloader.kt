package indi.renakoni.nextvol.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class UpdateArtifactDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    data class Files(val apk: File, val temporary: File)

    fun prepare(): Files {
        val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = cacheDir.resolve("LightNovelReader-update.apk").apply {
            if (exists()) delete()
        }
        val tempFile = cacheDir.resolve("LightNovelReader-update.tmp").apply {
            if (exists()) delete()
        }
        return Files(apkFile, tempFile)
    }

    suspend fun download(
        release: Release,
        files: Files,
        onProgress: suspend (copied: Long, total: Long) -> Unit,
        onProcessing: suspend () -> Unit,
        onHttpFailure: suspend (Int) -> Unit,
    ): File? {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(release.downloadUrl)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                onHttpFailure(response.code)
                return@use
            }
            response.body.let { body ->
                val total = body.contentLength()
                val input = body.byteStream()
                FileOutputStream(files.temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytesCopied = 0L
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        bytes = input.read(buffer)
                        if (total > 0) onProgress(bytesCopied, total)
                    }
                }
            }
        }

        release.downloadFileProgress?.let { transform ->
            onProcessing()
            transform(files.temporary, files.apk)
        } ?: files.temporary.renameTo(files.apk)

        return files.apk.takeIf { it.exists() && it.length() > 0L }
    }
}
