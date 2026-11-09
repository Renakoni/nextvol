package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.key
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEnginesScreen(state: SpeechEnginesState, onBack: () -> Unit,
    onEdit: (SpeechEngineConfig?) -> Unit, onAddVoice: (SpeechEngineConfig) -> Unit, onOrder: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var local by remember(state.engines) { mutableStateOf(state.engines) }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val moving = local.firstOrNull { it.key == from.key }
        val target = local.indexOfFirst { it.key == to.key }
        if (moving != null && target >= 0) local = local.toMutableList().apply { remove(moving); add(target, moving) }
    }
    val closeSearch = { searching = false; query = "" }
    BackHandler(searching, closeSearch)
    Scaffold(topBar = {
        SpeechSourcesTopBar(query, { query = it }, searching, { searching = true }, closeSearch, onBack,
            state.saving, onImport = {}, onRefresh = null, title = R.string.tts_engines,
            searchLabel = R.string.tts_engine_search, onAdd = { onEdit(null) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, contentPadding = PaddingValues(vertical = 12.dp)) {
            items(local.filter { engine -> query.isBlank() || listOf(engine.name, engine.code, engine.note).any { it.contains(query, true) } }, key = { it.key }) { engine ->
                ReorderableItem(reorder, engine.key) {
                    Surface {
                        Row(Modifier.fillMaxWidth().then(if (query.isBlank() && !state.saving)
                            Modifier.longPressDraggableHandle(onDragStopped = { onOrder(local.map { it.key }) }) else Modifier)
                            .heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(if (engine.type == "http") R.drawable.language_24px else R.drawable.settings_applications_24px),
                                if (engine.type == "http") "HttpTTS" else "innerTTS", Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(engine.name.ifBlank { engine.code }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(engine.code, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!engine.enabled || engine.deleted) Text(stringResource(if (engine.deleted) R.string.tts_engine_missing else R.string.tts_engine_disabled),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { onAddVoice(engine) }, enabled = !state.saving && !engine.deleted) {
                                Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_engine_add_speaker))
                            }
                            IconButton(onClick = { onEdit(engine) }, enabled = !state.saving) {
                                Icon(painterResource(R.drawable.edit_square_24px), stringResource(R.string.tts_engine_edit))
                            }
                        }
                    }
                }
            }
            if (!state.loading && local.isEmpty()) item { Text(stringResource(R.string.tts_engine_empty), Modifier.padding(22.dp)) }
            state.error?.let { error -> item { Text(stringResource(error), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
}
