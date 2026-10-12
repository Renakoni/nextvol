package indi.renakoni.nextvol.ui.home.settings.list

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import indi.renakoni.nextvol.utils.textToast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.home.settings.ImportUserDataDialog
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.backupFailureMessage
import indi.renakoni.nextvol.utils.uriLauncher
import kotlinx.coroutines.launch

@Composable
fun DataSettingsList(
    onClickExportUserData: () -> Unit,
    importData: (Uri, Boolean) -> OneTimeWorkRequest,
    onClickStorageManager: () -> Unit,
) {
    val dataImportSuccessText by rememberUpdatedState(stringResource(R.string.data_import_success))
    val dataFileChooserTitle = stringResource(R.string.data_file_choose)

    val context = LocalContext.current
    val workManager = WorkManager.getInstance(context)
    val scope = rememberCoroutineScope()

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    val startImport: (Uri, Boolean) -> Unit = { uri, overwrite ->
        isImporting = true
        scope.launch {
            workManager.getWorkInfoByIdFlow(importData(uri, overwrite).id).collect {
                when (it?.state) {
                    WorkInfo.State.FAILED -> {
                        isImporting = false
                        showImportDialog = false
                        pendingImportUri = null
                        textToast(context, backupFailureMessage(it?.outputData, R.string.data_import_failed), Toast.LENGTH_LONG).show()
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        isImporting = false
                        showImportDialog = false
                        pendingImportUri = null
                        textToast(context, dataImportSuccessText, Toast.LENGTH_SHORT).show()
                    }
                    WorkInfo.State.CANCELLED -> {
                        isImporting = false
                        showImportDialog = false
                        pendingImportUri = null
                    }
                    else -> {}
                }
            }
        }
    }

    val importDataLauncher = uriLauncher { uri ->
        pendingImportUri = uri
        showImportDialog = true
    }

    if (showImportDialog && pendingImportUri != null) {
        val uri = pendingImportUri!!
        ImportUserDataDialog(
            isImporting = isImporting,
            onDismissRequest = {
                showImportDialog = false
                pendingImportUri = null
            },
            onClickMerge = { startImport(uri, false) },
            onClickOverwrite = { startImport(uri, true) },
        )
    }

    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.output_24px),
        title = stringResource(R.string.settings_snap_data),
        description = stringResource(R.string.settings_snap_data_desc),
        onClick = onClickExportUserData
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.input_24px),
        title = stringResource(R.string.settings_import_data),
        description = stringResource(R.string.settings_import_data_desc),
        onClick = { selectDataFile(importDataLauncher, dataFileChooserTitle) }
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.database_24px),
        title = stringResource(R.string.settings_storage_manager),
        description = stringResource(R.string.settings_storage_manager_desc),
        onClick = onClickStorageManager
    )
}

fun selectDataFile(launcher: ManagedActivityResultLauncher<Intent, ActivityResult>, chooserTitle: String) {
    val initUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initUri)
    }
    launcher.launch(Intent.createChooser(intent, chooserTitle))
}
