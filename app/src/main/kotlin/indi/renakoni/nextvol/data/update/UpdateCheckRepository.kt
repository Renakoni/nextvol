package indi.renakoni.nextvol.data.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateCheckRepository @Inject constructor(
    @param:ApplicationContext @field:ApplicationContext private val context: Context,
    private val userDataRepository: UserDataRepository,
    private val downloader: UpdateArtifactDownloader,
    private val systemInteraction: AndroidUpdateInteraction,
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
            val updateChannelKey = userDataRepository.stringUserData(UserDataPath.Settings.App.UpdateChannel.path).get() ?: UpdateChannel.default.key
            val distributionPlatform = userDataRepository.stringUserData(UserDataPath.Settings.App.DistributionPlatform.path).get() ?: UpdatePlatform.default.key
            Log.i("UpdateChecker", "Checking for updates from $distributionPlatform/$updateChannelKey")
            _updatePhase.update { UpdatePhase(R.string.update_phase_waiting, listOf(distributionPlatform)) }
            try {
                release =
                    UpdatePlatform.fromKey(distributionPlatform)
                        .parserFor(UpdateChannel.fromKey(updateChannelKey))
                        .parser(_updatePhase)
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
        if (!_isDownloading.compareAndSet(false, true)) {
            Log.w("UpdateChecker", "Download already in progress")
            return
        }

        coroutineScope.launch {
            try {
                _downloadProgress.emit(0f)
                _updatePhase.emit(UpdatePhase(R.string.update_phase_downloading))
                systemInteraction.createNotificationChannel()
                val files = downloader.prepare()
                currentCoroutineContext().ensureActive()
                systemInteraction.showDownloadNotification(0, release.versionName)

                var lastNotificationUpdate = 0L
                val apkFile = downloader.download(
                    release, files,
                    onProgress = { copied, total ->
                        val progress = copied.toFloat() / total.toFloat()
                        _downloadProgress.emit(progress)
                        val progressPercent = (progress * 100).toInt()
                        _updatePhase.emit(UpdatePhase(R.string.update_phase_download_progress, listOf(progressPercent)))
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationUpdate > 500) {
                            systemInteraction.showDownloadNotification(progressPercent, release.versionName)
                            lastNotificationUpdate = now
                        }
                    },
                    onProcessing = { _updatePhase.emit(UpdatePhase(R.string.update_phase_processing)) },
                )
                // Blocking HTTP/file work can return normally after cancellation.
                currentCoroutineContext().ensureActive()

                if (apkFile != null) {
                    _downloadProgress.emit(1f)
                    _updatePhase.emit(UpdatePhase(R.string.update_phase_download_complete))
                    systemInteraction.showDownloadCompleteNotification(apkFile)
                    withContext(Dispatchers.Main) {
                        systemInteraction.installApk(apkFile)
                    }
                } else {
                    Log.e("UpdateChecker", "Downloaded file is empty")
                    _updatePhase.emit(UpdatePhase(R.string.update_phase_empty_file))
                    systemInteraction.showDownloadFailedNotification(context.getString(R.string.update_download_empty_file))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.e("UpdateChecker", "Download failed", e)
                val phase = when {
                    e is MissingUpdateApkException -> UpdatePhase(R.string.update_phase_missing_apk, listOf(e.archiveName))
                    e.localizedMessage != null -> UpdatePhase(R.string.update_phase_download_failed, listOf(e.localizedMessage!!))
                    else -> UpdatePhase(R.string.update_phase_download_failed_unknown)
                }
                _updatePhase.emit(phase)
                systemInteraction.showDownloadFailedNotification(context.getString(phase.messageId, *phase.arguments.toTypedArray()))
            }
        }.invokeOnCompletion {
            // Also release admission if cancellation prevents the coroutine body from starting.
            _isDownloading.value = false
        }
    }
}
