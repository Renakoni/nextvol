package indi.renakoni.nextvol.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateCheckRepository @Inject constructor(
    @param:ApplicationContext @field:ApplicationContext private val context: Context,
    private val userDataRepository: UserDataRepository
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var checkJob: Job? = null
    var release: Release? = null
        private set
    private val mutableAvailable: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val availableFlow: Flow<Boolean> = mutableAvailable
    private val _updatePhase = MutableStateFlow(UpdatePhase(R.string.update_phase_not_checked))
    val updatePhase: Flow<UpdatePhase> = _updatePhase
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private fun formattedNow(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "AppUpdateDownload"
        private const val NOTIFICATION_ID = 0x4C4E52
    }

    init {
        coroutineScope.launch {
            if (userDataRepository.booleanUserData(UserDataPath.Settings.App.AutoCheckUpdate.path).getOrDefault(true))
                check()
        }
    }

    fun resetAvailable() {
        coroutineScope.launch {
            mutableAvailable.update { false }
        }
    }

    fun check() {
        if (checkJob != null && checkJob!!.isActive) return
        checkJob = coroutineScope.launch {
            release = null
            mutableAvailable.emit(false)
            _updatePhase.update { UpdatePhase(R.string.update_phase_waiting, listOf("GitHub")) }
            try {
                release = GithubParser.parser(_updatePhase)
                if (release == null) {
                    _updatePhase.emit(UpdatePhase(R.string.update_phase_no_release))
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "failed to get release")
                e.printStackTrace()
                _updatePhase.emit(UpdatePhase(R.string.update_phase_check_failed, listOf(formattedNow(), e.javaClass.simpleName, e.message.orEmpty())))
            }
            if (release != null) {
                if (release!!.version > BuildConfig.VERSION_CODE) {
                    Log.i("UpdateChecker", "Updates available: ${release!!.versionName}")
                    _updatePhase.emit(UpdatePhase(R.string.update_phase_available, listOf(formattedNow(), release!!.versionName)))
                } else {
                    Log.i("UpdateChecker", "App is up to date (${release!!.versionName})")
                    _updatePhase.emit(UpdatePhase(R.string.update_phase_current, listOf(formattedNow(), release!!.versionName)))
                }
            }
            mutableAvailable.emit(release != null && release!!.version > BuildConfig.VERSION_CODE)
        }
    }

    fun downloadUpdate() {
        val release = release
        if (release == null) {
            Log.e("UpdateChecker", "Didn't find the release because release is null!")
            return
        }
        if (_isDownloading.value) {
            Log.w("UpdateChecker", "Download already in progress")
            return
        }

        val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = cacheDir.resolve("NextVol-update.apk").apply {
            if (exists()) delete()
        }
        val tempFile = cacheDir.resolve("NextVol-update.tmp").apply {
            if (exists()) delete()
        }

        coroutineScope.launch {
            _isDownloading.emit(true)
            _downloadProgress.emit(0f)
            try {
                _updatePhase.emit(UpdatePhase(R.string.update_phase_downloading))
                createNotificationChannel()
                showDownloadNotification(0, release.versionName)

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
                        throw IOException("HTTP ${response.code}")
                    }

                    response.body.let { body ->
                        val total = body.contentLength()
                        val input = body.byteStream()
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesCopied = 0L
                            var bytes = input.read(buffer)
                            var lastNotificationUpdate = 0L

                            while (bytes >= 0) {
                                output.write(buffer, 0, bytes)
                                bytesCopied += bytes
                                bytes = input.read(buffer)

                                if (total > 0) {
                                    val progress = bytesCopied.toFloat() / total.toFloat()
                                    _downloadProgress.emit(progress)
                                    val progressPercent = (progress * 100).toInt()
                                    _updatePhase.emit(UpdatePhase(R.string.update_phase_download_progress, listOf(progressPercent)))

                                    val now = System.currentTimeMillis()
                                    if (now - lastNotificationUpdate > 500) {
                                        showDownloadNotification(progressPercent, release.versionName)
                                        lastNotificationUpdate = now
                                    }
                                }
                            }
                        }
                    }
                }

                if (!tempFile.renameTo(apkFile)) throw IOException("Cannot save update APK")

                if (apkFile.exists() && apkFile.length() > 0L) {
                    validateUpdateApk(context, apkFile, release.version)
                    _downloadProgress.emit(1f)
                    _updatePhase.emit(UpdatePhase(R.string.update_phase_download_complete))
                    showDownloadCompleteNotification(apkFile)
                    withContext(Dispatchers.Main) {
                        installApk(apkFile)
                    }
                } else {
                    Log.e("UpdateChecker", "Downloaded file is empty")
                    _updatePhase.emit(UpdatePhase(R.string.update_phase_empty_file))
                    showDownloadFailedNotification(context.getString(R.string.update_download_empty_file))
                }
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Download failed", e)
                val phase = when {
                    e.localizedMessage != null -> UpdatePhase(R.string.update_phase_download_failed, listOf(e.localizedMessage!!))
                    else -> UpdatePhase(R.string.update_phase_download_failed_unknown)
                }
                _updatePhase.emit(phase)
                showDownloadFailedNotification(context.getString(phase.messageId, *phase.arguments.toTypedArray()))
                apkFile.delete()
            } finally {
                tempFile.delete()
                _isDownloading.emit(false)
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.update_notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showDownloadNotification(progress: Int, versionName: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_foreground)
            .setContentTitle(context.getString(R.string.update_notification_downloading, versionName))
            .setContentText(context.getString(R.string.update_notification_progress, progress))
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun showDownloadCompleteNotification(apkFile: File) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_foreground)
            .setContentTitle(context.getString(R.string.update_notification_complete))
            .setContentText(context.getString(R.string.update_notification_install))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun showDownloadFailedNotification(reason: String) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_foreground)
            .setContentTitle(context.getString(R.string.update_notification_failed))
            .setContentText(reason)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
