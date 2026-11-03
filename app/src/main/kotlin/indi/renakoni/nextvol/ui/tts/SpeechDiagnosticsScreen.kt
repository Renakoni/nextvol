package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.logging.LogEntry
import indi.renakoni.nextvol.ui.home.settings.SettingsTopBar
import indi.renakoni.nextvol.ui.home.settings.logcat.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechDiagnosticsScreen(logs: List<LogEntry>, onBack: () -> Unit,
    onClear: () -> Unit, onExport: () -> Unit) {
    val listState = rememberLazyListState()
    val logState = remember { MutableLogcatUiState() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(logs.lastOrNull()) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding().testTag("speech-diagnostics")) {
        SettingsTopBar(TopAppBarDefaults.pinnedScrollBehavior(), R.string.tts_diagnostics, onBack)
        Box(Modifier.weight(1f)) {
            if (logs.isEmpty()) EmptyLogListContent(false, "info", showDescription = false)
            else LogListContent(logs, listState, Arrangement.spacedBy(8.dp))
        }
        if (logs.isNotEmpty()) LogToolbar(logState, listOf(LIVE_LOG_OPTION), true,
            onSelectLogFile = {}, onShare = onExport, onDelete = { confirmClear = true })
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        icon = { Icon(painterResource(R.drawable.delete_forever_24px), null) },
        title = { Text(stringResource(R.string.log_clear)) },
        text = { Text(stringResource(R.string.log_clear_all_confirm)) },
        confirmButton = {
            TextButton(onClick = { onClear(); confirmClear = false }) {
                Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
