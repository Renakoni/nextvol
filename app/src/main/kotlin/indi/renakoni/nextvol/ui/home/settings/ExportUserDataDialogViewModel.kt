package indi.renakoni.nextvol.ui.home.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import indi.renakoni.nextvol.utils.textToast
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.backup.BackupArchive
import indi.renakoni.nextvol.data.work.ExportDataWork
import indi.renakoni.nextvol.ui.components.backupFailureMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ExportUserDataDialogViewModel @Inject constructor(
    private val workManager: WorkManager,
) : ViewModel() {

    private fun buildExportWork(
        uri: Uri,
        exportContext: ExportContext
    ): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<ExportDataWork>()
            .setInputData(
                workDataOf(
                    "uri" to uri.toString(),
                    "exportLocalBookCache" to exportContext.localBookCache,
                    "exportBookshelf" to exportContext.bookshelf,
                    "exportReadingData" to exportContext.readingData,
                    "exportSetting" to exportContext.settings,
                    "exportBookmark" to exportContext.bookmark,
                )
            )
            .build()
    }

    fun exportAndSendToFile(
        exportContext: ExportContext,
        context: Context,
        onFinish: () -> Unit
    ) {
        val file = File(context.cacheDir, BackupArchive.USER_DATA_FILE_NAME)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val request = buildExportWork(uri, exportContext)

        workManager.enqueueUniqueWork(
            uri.toString(),
            ExistingWorkPolicy.REPLACE,
            request
        )

        viewModelScope.launch {
            val workInfo = workManager
                .getWorkInfoByIdFlow(request.id)
                .first { it?.state?.isFinished == true }
            if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                val intent = ShareCompat.IntentBuilder(context)
                    .setType("application/zip")
                    .setSubject(context.getString(R.string.share_file))
                    .addStream(uri)
                    .setChooserTitle(context.getString(R.string.export_and_share))
                    .intent
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.export_and_share))
                )
            } else if (workInfo?.state == WorkInfo.State.FAILED) {
                textToast(context, backupFailureMessage(workInfo.outputData, R.string.backup_export_failed), Toast.LENGTH_LONG).show()
            }
            onFinish()
        }
    }

    fun exportToFile(uri: Uri, exportContext: ExportContext): OneTimeWorkRequest {
        val workRequest = buildExportWork(uri, exportContext)
        workManager.enqueueUniqueWork(
            uri.toString(),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        return workRequest
    }
}
