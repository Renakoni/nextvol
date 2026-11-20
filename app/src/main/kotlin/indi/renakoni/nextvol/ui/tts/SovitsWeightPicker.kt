package indi.renakoni.nextvol.ui.tts

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.sovits.SovitsImportFile
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private data class WeightEntry(val file: SovitsImportFile, val folder: Boolean)
private data class WeightDirectory(val name: String, val entries: List<WeightEntry>)

// DocumentsUI groups both checkpoint extensions under the same MIME type. Browse the granted tree by display name.
private suspend fun readWeightDirectory(resolver: ContentResolver, directory: Uri): WeightDirectory = withContext(Dispatchers.IO) {
    val task = currentCoroutineContext()
    val name = resolver.query(directory, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }.orEmpty()
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getDocumentId(directory))
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    val entries = ArrayList<WeightEntry>()
    checkNotNull(resolver.query(children, columns, null, null, null)).use { cursor ->
        while (cursor.moveToNext()) {
            task.ensureActive()
            val uri = DocumentsContract.buildDocumentUriUsingTree(directory, cursor.getString(0))
            val filename = cursor.getString(1) ?: continue
            entries += WeightEntry(SovitsImportFile(uri, filename), cursor.getString(2) == Document.MIME_TYPE_DIR)
        }
    }
    WeightDirectory(name, entries.sortedWith(compareByDescending<WeightEntry> { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SovitsWeightPicker(directory: Uri, suffix: String, onFolder: (Uri) -> Unit, onChangeFolder: () -> Unit,
    onBack: () -> Unit, onPick: (SovitsImportFile) -> Unit) {
    BackHandler(onBack = onBack)
    val resolver = LocalContext.current.contentResolver
    key(directory) {
        var contents by remember { mutableStateOf<WeightDirectory?>(null) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(directory) {
            try { contents = readWeightDirectory(resolver, directory) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        Scaffold(topBar = {
            SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.sovits_choose_file, onBack,
                titleText = stringResource(R.string.sovits_pick_weight_file, suffix)) {
                TextButton(onClick = onChangeFolder) { Text(stringResource(R.string.sovits_change_folder)) }
            }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                val listing = contents
                when {
                    failed -> item { Text(stringResource(R.string.sovits_folder_unreadable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                    listing == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    else -> {
                        item { Text(listing.name, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (listing.entries.isEmpty()) item { Text(stringResource(R.string.sovits_folder_empty), Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(listing.entries, key = { it.file.uri.toString() }) { entry ->
                            val enabled = entry.folder || entry.file.name.endsWith(suffix, ignoreCase = true)
                            val action = if (enabled) Modifier.clickable {
                                if (entry.folder) onFolder(entry.file.uri) else onPick(entry.file)
                            } else Modifier.semantics { disabled() }.alpha(0.38f)
                            Row(Modifier.fillMaxWidth().then(action).heightIn(min = 52.dp).padding(horizontal = 20.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(if (entry.folder) R.drawable.drive_file_move_24px else R.drawable.article_24px), null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(entry.file.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
