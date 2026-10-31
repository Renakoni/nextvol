package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

@Composable
internal fun SpeechLibraryControls(query: String, onQuery: (String) -> Unit, group: Int, onGroup: (Int) -> Unit,
    descending: Boolean, onSort: () -> Unit, selecting: Boolean, selectedCount: Int, onSelectMode: () -> Unit,
    onSelectAll: () -> Unit, onEnable: (Boolean) -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
    deleteCount: Int = selectedCount, noResults: Boolean = false) {
    var groupMenu by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.tts_voice_search)) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                TextButton(onClick = { groupMenu = true }) { Text(stringResource(groupLabels[group])) }
                DropdownMenu(groupMenu, { groupMenu = false }) {
                    groupLabels.forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { groupMenu = false; onGroup(index) })
                    }
                }
            }
            TextButton(onClick = onSort) { Text(stringResource(if (descending) R.string.tts_voice_sort_descending else R.string.tts_voice_sort_ascending)) }
            TextButton(onClick = onSelectMode) { Text(stringResource(if (selecting) R.string.tts_voice_clear_selection else R.string.tts_voice_batch)) }
        }
        if (selecting) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.tts_voice_select_all)) }
            TextButton(onClick = { onEnable(true) }, enabled = selectedCount > 0) { Text(stringResource(R.string.tts_voice_enable)) }
            TextButton(onClick = { onEnable(false) }, enabled = selectedCount > 0) { Text(stringResource(R.string.tts_voice_disable)) }
            TextButton(onClick = onCopy, enabled = selectedCount > 0) { Text(stringResource(R.string.tts_voice_copy)) }
            TextButton(onClick = onDelete, enabled = deleteCount > 0) { Text(stringResource(R.string.tts_remove_source) + " ($deleteCount)") }
        }
        if (noResults) Text(stringResource(R.string.search_no_results), Modifier.padding(vertical = 12.dp))
    }
}

private val groupLabels = listOf(R.string.tts_voice_group_engine, R.string.tts_voice_group_language, R.string.tts_voice_group_none)
