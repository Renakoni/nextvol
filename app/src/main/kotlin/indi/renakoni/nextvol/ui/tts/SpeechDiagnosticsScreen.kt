package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.logging.LogEntry
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.settings.logcat.EmptyLogListContent
import indi.renakoni.nextvol.ui.home.settings.logcat.LIVE_LOG_OPTION
import indi.renakoni.nextvol.ui.home.settings.logcat.LogListContent
import indi.renakoni.nextvol.ui.home.settings.logcat.LogToolbar
import indi.renakoni.nextvol.ui.home.settings.logcat.MutableLogcatUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechDiagnosticsScreen(
    logs: List<LogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val logState = remember { MutableLogcatUiState() }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(logs.lastOrNull()) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .testTag("speech-diagnostics"),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.tts_diagnostics),
                scrollBehavior = scrollBehavior,
                hazeState = null,
                frosted = false,
                navigationIcon = {
                    EditorialIconButton(
                        onClick = onBack,
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
        ) {
            Box(Modifier.weight(1f)) {
                if (logs.isEmpty()) EmptyLogListContent(false, "info", showDescription = false)
                else LogListContent(logs, listState, Arrangement.spacedBy(8.dp))
            }
            if (logs.isNotEmpty()) {
                LogToolbar(
                    logState,
                    listOf(LIVE_LOG_OPTION),
                    true,
                    onSelectLogFile = {},
                    onShare = onExport,
                    onDelete = { confirmClear = true },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = RoundedCornerShape(tokens.sheetRadius),
            containerColor = tokens.cardElevatedSurface,
            icon = {
                EditorialIconBadge(
                    painter = painterResource(R.drawable.delete_forever_24px),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.log_clear), fontWeight = FontWeight.W600) },
            text = { Text(stringResource(R.string.log_clear_all_confirm), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = {
                        onClear()
                        confirmClear = false
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
