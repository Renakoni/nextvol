package indi.renakoni.nextvol.data.work

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.export.EpubExportCancelled
import indi.renakoni.nextvol.data.export.EpubExportProgress
import indi.renakoni.nextvol.data.export.EpubExportRequest
import indi.renakoni.nextvol.data.export.EpubExportResult
import indi.renakoni.nextvol.data.export.ExportBookToEpubUseCase
import indi.renakoni.nextvol.data.export.ExportType
import indi.renakoni.nextvol.ui.export.EpubShareActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

@HiltWorker
class ExportBookToEPUBWork @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val exportBook: ExportBookToEpubUseCase,
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        fun ofId(id: String): String = "export_to_epub:${BookIdentity.bookKey(id)}"
        private const val TAG = "ExportEPUB"
    }

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var notification: Notification? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "BookEpubExport",
                applicationContext.getString(R.string.epub_export_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showProgressNotification() {
        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(applicationContext.getString(R.string.epub_export_notification_preparing))
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateFailureNotification(failure: EpubExportResult.Failure) {

        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(failureMessage(failure))
            .setStyle(NotificationCompat.BigTextStyle().bigText(failureMessage(failure)))
            .setSmallIcon(R.drawable.file_export_24px)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        postNotification(ofId(failure.book.storageKey), 0)
    }

    private fun updateCompletionNotification(bookId: String) {
        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: ""))
            .setContentText(applicationContext.getString(R.string.epub_export_share_ready))
            .setContentIntent(PendingIntent.getActivity(applicationContext, 0,
                EpubShareActivity.intent(applicationContext, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(0, 0, false)
            .setAutoCancel(true)
            .build()

        postNotification(ofId(bookId), 0)
    }

    private fun buildProgressNotification(
        progress: Int,
        stage: String,
        currentVolumeTitle: String,
        currentChapterTitle: String,
    ) {
        val text = "$stage $currentVolumeTitle / $currentChapterTitle"

        notification = NotificationCompat.Builder(applicationContext, "BookEpubExport")
            .setContentTitle(applicationContext.getString(R.string.export_book_started, inputData.getString("title") ?: "") + " ($progress%)")
            .setContentText(text)
            .setSmallIcon(R.drawable.file_export_24px)
            .setProgress(100, progress, false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        postNotification(null, id.hashCode())
    }

    private fun postNotification(tag: String?, notificationId: Int) {
        try { notificationManager.notify(tag, notificationId, notification) }
        catch (_: SecurityException) { Log.w(TAG, "Notifications are unavailable") }
    }

    override suspend fun doWork(): Result {
        val book = inputData.sourceBook() ?: return bookWorkFailure("invalid_book_identity")
        val type = runCatching { ExportType.valueOf(inputData.getString("exportType").orEmpty()) }.getOrNull()
            ?: return bookWorkFailure("invalid_export_type", book)
        val selected = inputData.getString("selectedVolume").orEmpty().split(',').filter(String::isNotEmpty).toSet()
        val request = runCatching { EpubExportRequest(
            id, book, type, selected, inputData.getBoolean("includeImages", true),
            inputData.getLong("downloadGeneration", 0),
        ) }.getOrElse { return bookWorkFailure("invalid_volume_identity", book) }
        val result = try {
            createNotificationChannel()
            showProgressNotification()
            val foreground = if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(
                id.hashCode(), notification!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ) else ForegroundInfo(id.hashCode(), notification!!)
            setForeground(foreground)
            exportBook.execute(request) { progress ->
                val label = when (progress.phase) {
                    EpubExportProgress.Phase.CHAPTERS -> R.string.epub_export_notification_stage_chapters
                    EpubExportProgress.Phase.IMAGES -> R.string.epub_export_notification_stage_images
                }
                buildProgressNotification(progress.percent,
                    applicationContext.getString(label, progress.completed, progress.total),
                    progress.volume, progress.chapter)
            }
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                updateFailureNotification((cancelled as? EpubExportCancelled)?.failure
                    ?: EpubExportResult.Failure(book, "cancelled"))
                throw cancelled
            }
            EpubExportResult.Failure(book, "source_unavailable")
        } catch (problem: Exception) {
            Log.e(TAG, "Export adapter failed for ${book.fileKey}: ${problem.javaClass.simpleName}")
            EpubExportResult.Failure(book, "export_failed")
        } finally {
            // Foreground setup may fail or be cancelled before execute() can run its own cleanup.
            exportBook.clearTemporaryFiles(request)
        }
        return when (result) {
            is EpubExportResult.Success -> {
                updateCompletionNotification(result.book.storageKey)
                Result.success(workDataOf("exportId" to result.id.toString(),
                    "completedVolumes" to result.completedVolumes, "totalVolumes" to result.totalVolumes))
            }
            is EpubExportResult.Failure -> {
                updateFailureNotification(result)
                Result.failure(workDataOf(
                    "reason" to result.reason, "bookId" to result.book.storageKey, "stage" to result.stage,
                    "volume" to result.volume.take(160), "chapter" to result.chapter.take(160),
                    "completedVolumes" to result.completedVolumes, "totalVolumes" to result.totalVolumes,
                    "message" to failureMessage(result),
                ))
            }
        }
    }

    private fun failureMessage(failure: EpubExportResult.Failure): String {
        val reason = when (failure.reason) {
            "empty_volume", "empty_directory" -> R.string.epub_export_error_empty
            "invalid_content" -> R.string.epub_export_error_content
            "image_failed" -> R.string.epub_export_error_image
            "share_failed" -> R.string.epub_export_share_failed
            "cancelled" -> R.string.epub_export_error_cancelled
            "cache_failed" -> R.string.epub_export_error_cache
            else -> R.string.epub_export_notification_failed
        }
        return applicationContext.getString(R.string.epub_export_failure_detail,
            applicationContext.getString(reason),
            listOf(failure.volume, failure.chapter).filter(String::isNotBlank).joinToString(" / ").take(240),
            failure.completedVolumes, failure.totalVolumes)
    }

}
