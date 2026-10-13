package indi.renakoni.nextvol.data.update

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import indi.renakoni.nextvol.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AndroidUpdateInteractionTest {
    private val context = RuntimeEnvironment.getApplication<Application>()
    private val interaction = AndroidUpdateInteraction(context)
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifications = shadowOf(manager)
    private val apk by lazy { context.cacheDir.resolve("updates/LightNovelReader-update.apk").apply {
        parentFile!!.mkdirs()
        writeText("apk")
    } }

    @Test fun notificationPermissionDoesNotPreventDirectInstallation() {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        interaction.createNotificationChannel()
        interaction.showDownloadNotification(50, "fixture")
        interaction.showDownloadCompleteNotification(apk)
        interaction.showDownloadFailedNotification("failed")
        assertEquals(0, notifications.size())
        interaction.installApk(apk)
        assertInstallIntent(shadowOf(context).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun channelProgressCompletionAndFailureKeepTheSameNotificationAndInstallContract() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        interaction.createNotificationChannel()
        val channel = manager.getNotificationChannel("AppUpdateDownload")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.canShowBadge())
        assertEquals(context.getString(R.string.update_notification_channel_description), channel.description)
        interaction.showDownloadNotification(37, "fixture")
        val downloading = notifications.getNotification(0x4C4E52)
        assertEquals("AppUpdateDownload", downloading.channelId)
        assertEquals(37, downloading.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(100, downloading.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(downloading.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(downloading.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

        interaction.showDownloadCompleteNotification(apk)
        val completed = notifications.getNotification(0x4C4E52)
        assertEquals(1, notifications.size())
        assertTrue(completed.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(0, completed.flags and Notification.FLAG_ONGOING_EVENT)
        val pending = shadowOf(completed.contentIntent)
        assertEquals(0, pending.requestCode)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, pending.flags)
        assertInstallIntent(pending.savedIntent)
        interaction.installApk(apk)
        assertInstallIntent(shadowOf(context).nextStartedActivity)

        interaction.showDownloadFailedNotification("HTTP 503")
        val failed = notifications.getNotification(0x4C4E52)
        assertEquals(1, notifications.size())
        assertEquals("HTTP 503", failed.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(failed.contentIntent)
        assertEquals(0, failed.flags and Notification.FLAG_ONGOING_EVENT)
    }

    private fun assertInstallIntent(intent: Intent) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(FileProvider.getUriForFile(context, "${context.packageName}.provider", apk), intent.data)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }
}
