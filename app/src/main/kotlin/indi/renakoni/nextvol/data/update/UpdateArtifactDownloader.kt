package indi.renakoni.nextvol.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
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
            .followSslRedirects(false)
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
            val downloaded = downloadFile(release.downloadUrl, files.temporary, release.sha256) { copied, total ->
                if (total > 0) onProgress(copied, total)
            }
            if (!downloaded) return null
            if (!files.temporary.renameTo(files.apk)) throw IOException("Cannot save update APK")
            return files.apk
        } finally {
            files.temporary.delete()
        }
    }

    internal suspend fun selectDownloadUrl(urls: List<String>): String? {
        val results = Channel<String?>(urls.size)
        val calls = urls.map { client.newCall(Request.Builder().url(it).head().build()) }
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) { results.trySend(null) }
            override fun onResponse(call: Call, response: Response) {
                results.trySend(response.use { if (it.code == 200) it.request.url.toString() else null })
            }
        }
        try {
            return withTimeoutOrNull(8_000) {
                calls.forEach { it.enqueue(callback) }
                repeat(calls.size) {
                    val url = results.receive()
                    if (url != null) return@withTimeoutOrNull url
                }
                null
            }
        } finally {
            calls.forEach { it.cancel() }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    internal suspend fun downloadFile(
        url: String, destination: File, expectedSha256: String? = null,
        onProgress: suspend (copied: Long, total: Long) -> Unit,
    ): Boolean {
        val request = Request.Builder().url(url).get().build()
        val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
        val task = currentCoroutineContext()
        val call = client.newCall(request)
        // A stalled socket cannot reach the copy loop's cancellation check.
        val cancellation = task.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        try {
            call.execute().use { response ->
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
                        digest?.update(buffer, 0, bytes)
                        copied += bytes
                        onProgress(copied, total)
                    }
                    if (copied == 0L) return false
                }
            }
        } catch (failure: IOException) {
            task.ensureActive()
            throw failure
        } finally {
            cancellation.dispose()
        }
        if (digest != null && digest.digest().joinToString("") { "%02x".format(it) } != expectedSha256) {
            throw IOException("SHA-256 mismatch")
        }
        task.ensureActive()
        return true
    }
}
