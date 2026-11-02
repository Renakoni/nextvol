package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechSourcesTopBar(query: String, onQuery: (String) -> Unit, searching: Boolean,
    onSearch: () -> Unit, onCloseSearch: () -> Unit, onBack: () -> Unit, busy: Boolean,
    onImport: () -> Unit, onRefresh: (() -> Unit)?, title: Int = R.string.tts_sources,
    searchLabel: Int = R.string.tts_voice_search, onAdd: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null, selectedCount: Int? = null, onExitManagement: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    TopAppBar(title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (selectedCount == null) stringResource(title) else stringResource(R.string.tts_manage_selected, selectedCount),
                style = if (selectedCount == null) MaterialTheme.typography.displayLarge else MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (searching) Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f)) {
                Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(query, onQuery, Modifier.weight(1f).focusRequester(focus).testTag("voice-search-input"), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        decorationBox = { field ->
                            Box {
                                if (query.isEmpty()) Text(stringResource(searchLabel),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                field()
                            }
                        })
                    IconButton(onClick = { if (query.isNotEmpty()) onQuery("") else onCloseSearch() }) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(if (query.isNotEmpty()) R.string.search_clear_query else R.string.close))
                    }
                }
            }
        }
    }, navigationIcon = {
        IconButton(onClick = { if (selectedCount != null) onExitManagement() else if (searching) onCloseSearch() else onBack() }) {
            Icon(painterResource(if (selectedCount != null) R.drawable.close_24px else R.drawable.arrow_back_24px),
                stringResource(if (selectedCount != null) R.string.tts_manage_exit else R.string.sources_back))
        }
    }, actions = {
        if (!searching) IconButton(onClick = onSearch) {
            Icon(painterResource(R.drawable.search_24px), stringResource(searchLabel))
        }
        if (onAdd != null) IconButton(onClick = onAdd, enabled = !busy) {
            Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_engine_new))
        } else if (selectedCount == null) Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.tts_library_actions))
            }
            DropdownMenu(menu, { menu = false }) {
                if (onManage != null) DropdownMenuItem(text = { Text(stringResource(R.string.tts_manage_voices)) },
                    leadingIcon = { Icon(painterResource(R.drawable.checklist_24px), null) }, enabled = !busy,
                    onClick = { menu = false; onManage() })
                DropdownMenuItem(text = { Text(stringResource(R.string.tts_import_sources)) }, enabled = !busy,
                    leadingIcon = { Icon(painterResource(R.drawable.input_24px), null) },
                    onClick = { menu = false; onImport() })
                if (onRefresh != null) DropdownMenuItem(text = { Text(stringResource(R.string.tts_refresh)) }, enabled = !busy,
                    leadingIcon = { Icon(painterResource(R.drawable.refresh_24px), null) },
                    onClick = { menu = false; onRefresh() })
            }
        }
    })
}
