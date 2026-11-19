package indi.renakoni.nextvol.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class UpdateArtifactDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    data class Files(val apk: File, val temporary: File)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun prepare(): Files {
        val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = cacheDir.resolve("NextVol-update.apk").apply { if (exists()) delete() }
        val tempFile = cacheDir.resolve("NextVol-update.tmp").apply { if (exists()) delete() }
        return Files(apkFile, tempFile)
    }

    suspend fun download(
        release: Release,
        files: Files,
        onProgress: suspend (copied: Long, total: Long) -> Unit,
    ): File? {
        try {
            if (!downloadVerified(release.downloadUrl, release.sha256, files.temporary, onProgress)) return null
            if (!files.temporary.renameTo(files.apk)) throw IOException("Cannot save update APK")
            return files.apk
        } finally {
            files.temporary.delete()
        }
    }

    internal suspend fun firstReachable(urls: List<String>): String? = channelFlow {
        for (url in urls) launch(Dispatchers.IO) {
            val call = client.newCall(Request.Builder().url(url).head().build())
            call.timeout().timeout(8, TimeUnit.SECONDS)
            val reachable = try {
                call.useResponse { it.isSuccessful }
            } catch (_: IOException) {
                false
            }
            if (reachable) send(url)
        }
    }.firstOrNull()

    internal suspend fun downloadVerified(
        url: String, sha256: String, destination: File,
        onProgress: suspend (copied: Long, total: Long) -> Unit,
    ): Boolean {
        val request = Request.Builder().url(url).get().build()
        val digest = MessageDigest.getInstance("SHA-256")
        val task = currentCoroutineContext()
        client.newCall(request).useResponse { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength()
            val input = response.body.byteStream()
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    task.ensureActive()
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    output.write(buffer, 0, bytes)
                    digest.update(buffer, 0, bytes)
                    copied += bytes
                    if (total > 0) onProgress(copied, total)
                }
                if (copied == 0L) return false
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) throw IOException("SHA-256 mismatch")
        task.ensureActive()
        return true
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend inline fun <T> Call.useResponse(read: (Response) -> T): T {
        val task = currentCoroutineContext()
        // Cancel the socket even while waiting for headers or a stalled read.
        val cancellation = task.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) cancel()
        }
        try {
            return execute().use(read)
        } catch (failure: IOException) {
            task.ensureActive()
            throw failure
        } finally {
            cancellation.dispose()
        }
    }
}
