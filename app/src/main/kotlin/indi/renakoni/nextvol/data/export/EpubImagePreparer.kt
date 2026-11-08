package indi.renakoni.nextvol.data.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.utils.ImageUtils
import indi.renakoni.nextvol.utils.DefaultBookCoverRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

class EpubImagePreparer(
    private val context: Context,
    private val book: SourceBookId,
    private val tasks: List<Task>,
    private val onDownloaded: suspend (Task) -> Unit = {},
    private val onTask: (Task) -> Unit = {},
    val onProgress: (Int, Int) -> Unit,
) {
    var count = 0
        private set

    data class Task(val file: File, val uri: Uri, val cover: Boolean = false,
        val defaultCover: DefaultBookCoverRenderer.Text? = null, val fresh: Boolean = false,
        val sourceFile: File? = null)

    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        Log.i("EpubImagePreparer", "total tasks: ${tasks.size}")
        tasks.forEach { task ->
            currentCoroutineContext().ensureActive()
            onTask(task)
            val result = downloadWithRetry(task, maxRetry = 3)
            result
                .onOk { bitmap ->
                    try {
                        task.file.parentFile?.mkdirs()
                        task.file.outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) { "Image encoding failed" }
                        }
                        currentCoroutineContext().ensureActive()
                        onDownloaded(task)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.e(
                            "EpubImagePreparer",
                            "task $count: file write failed, file=${task.file}",
                            e
                        )
                        return@withContext false
                    }
                }
                .onErr { t ->
                    Log.e(
                        "EpubImagePreparer",
                        "task $count failed for ${book.fileKey}: ${t.javaClass.simpleName}"
                    )
                    task.defaultCover?.let { text ->
                        // Only a book/volume cover has a local substitute. Content image failures still fail export.
                        try {
                            DefaultBookCoverRenderer.writeTo(context, task.file, text.title, text.bookId, text.author)
                        } catch (_: Exception) { return@withContext false }
                        count++
                        onProgress(count, tasks.size)
                        return@forEach
                    }
                    return@withContext false
                }
            count++
            onProgress(count, tasks.size)
            Log.i("EpubImagePreparer", "tasks: $count/${tasks.size}")
        }
        return@withContext true
    }

    private suspend fun downloadWithRetry(
        task: Task,
        maxRetry: Int
    ): Result<Bitmap, Throwable> {

        var lastError: Throwable? = null

        repeat(maxRetry) { attempt ->
            // Retaining offline bytes needs a disk/source read even if a decoded bitmap is still in memory.
            val result = ImageUtils.uriToBitmap(task.uri, context, book.storageKey, task.cover,
                task.sourceFile == null && task.fresh, allowMemoryCache = false, sourceFile = task.sourceFile)
            var shouldRetry = false

            result
                .onOk {
                    return result
                }
                .onErr { error ->
                    if (error is CancellationException) throw error
                    lastError = error
                    if (error is SocketTimeoutException || error is ConnectException) {
                        shouldRetry = true
                        Log.w(
                            "EpubImagePreparer",
                            "retry ${attempt + 1}/$maxRetry for ${book.fileKey}"
                        )
                    } else {
                        return result
                    }
                }
            if (shouldRetry) {
                delay(500L * (attempt + 1))
            }
        }
        return Err(lastError ?: RuntimeException("unknown error"))
    }
}
