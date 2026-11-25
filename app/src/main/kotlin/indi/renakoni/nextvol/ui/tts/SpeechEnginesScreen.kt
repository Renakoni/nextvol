package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hnovel.speech.SpeechEngineConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.key
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechEnginesScreen(
    state: SpeechEnginesState,
    onBack: () -> Unit,
    onEdit: (SpeechEngineConfig?) -> Unit,
    onAddVoice: (SpeechEngineConfig) -> Unit,
    onOrder: (List<String>) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var local by remember(state.engines) { mutableStateOf(state.engines) }
    val list = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        val moving = local.firstOrNull { it.key == from.key }
        val target = local.indexOfFirst { it.key == to.key }
        if (moving != null && target >= 0) {
            local = local.toMutableList().apply {
                remove(moving)
                add(target, moving)
            }
        }
    }
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(searching, closeSearch)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SpeechSourcesTopBar(
                query = query,
                onQuery = { query = it },
                searching = searching,
                onSearch = { searching = true },
                onCloseSearch = closeSearch,
                onBack = onBack,
                busy = state.saving,
                onImport = {},
                onRefresh = null,
                title = R.string.tts_engines,
                searchLabel = R.string.tts_engine_search,
                onAdd = { onEdit(null) },
            )
        },
    ) { padding ->
        val visible = local.filter { engine ->
            query.isBlank() || listOf(engine.name, engine.code, engine.note).any { it.contains(query, true) }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(visible, key = { it.key }) { engine ->
                ReorderableItem(reorder, engine.key) { dragging ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = if (dragging) MaterialTheme.colorScheme.primary else tokens.cardBorder,
                        borderWidth = if (dragging) 1.25.dp else 0.75.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (query.isBlank() && !state.saving) {
                                        Modifier.longPressDraggableHandle(onDragStopped = { onOrder(local.map { it.key }) })
                                    } else {
                                        Modifier
                                    },
                                )
                                .heightIn(min = 72.dp)
                                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            EditorialIconBadge(
                                painter = painterResource(
                                    if (engine.type == "http") R.drawable.language_24px else R.drawable.settings_applications_24px,
                                ),
                                size = 40.dp,
                                iconSize = 20.dp,
                                cornerRadius = 12.dp,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = engine.name.ifBlank { engine.code },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.W600,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = engine.code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!engine.enabled || engine.deleted) {
                                    Text(
                                        text = stringResource(if (engine.deleted) R.string.tts_engine_missing else R.string.tts_engine_disabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            EditorialIconButton(
                                onClick = { onAddVoice(engine) },
                                enabled = !state.saving && !engine.deleted,
                                painter = painterResource(R.drawable.add_circle_24px),
                                contentDescription = stringResource(R.string.tts_engine_add_speaker),
                            )
                            EditorialIconButton(
                                onClick = { onEdit(engine) },
                                enabled = !state.saving,
                                painter = painterResource(R.drawable.edit_square_24px),
                                contentDescription = stringResource(R.string.tts_engine_edit),
                            )
                        }
                    }
                }
            }
            if (!state.loading && visible.isEmpty()) {
                item {
                    EmptyPage(
                        modifier = Modifier.fillParentMaxHeight(0.75f),
                        icon = painterResource(R.drawable.settings_applications_24px),
                        title = stringResource(if (query.isBlank()) R.string.tts_engines else R.string.search_no_results),
                        description = if (query.isBlank()) stringResource(R.string.tts_engine_empty) else "",
                    )
                }
            }
            state.error?.let { error ->
                item {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
