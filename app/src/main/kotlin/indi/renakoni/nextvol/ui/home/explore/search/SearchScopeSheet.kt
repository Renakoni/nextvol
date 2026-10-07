package indi.renakoni.nextvol.ui.home.explore.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.web.SourceCategory
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScopeSheet(
    state: SearchHubState, onDismiss: () -> Unit,
    onScope: (SourceCategory?) -> Unit, onSource: (Identifier) -> Unit,
) {
    var choosingSource by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedName = if (state.selectedSource == null) "" else
        state.sources.firstOrNull { it.id == state.selectedSource }?.name ?: state.selectedSourceName
    val scopeList = rememberLazyListState()
    val matches = remember(state.sources, state.selectedSource, query) {
        if (query.isBlank()) state.sources.filter { it.id == state.selectedSource }
        else state.sources.filter {
            it.name.contains(query.trim(), ignoreCase = true) || it.id.toString().contains(query.trim(), ignoreCase = true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val back: () -> Unit = {
            keyboard?.hide()
            focus.clearFocus()
            query = ""
            choosingSource = false
        }
        BackHandler(enabled = choosingSource, onBack = back)
        val pageTransition = updateTransition(choosingSource, label = "SearchScopePage")
        pageTransition.AnimatedContent(
            modifier = Modifier.fillMaxWidth().height(400.dp),
            transitionSpec = {
                val direction = if (targetState) 1 else -1
                ((fadeIn(tween(180)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 16 * direction })
                    togetherWith (fadeOut(tween(100)) +
                        slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 16 * direction })).using(null)
            },
        ) { showSource ->
            if (!showSource) {
                LazyColumn(Modifier.fillMaxSize().testTag("search_scope_options"), state = scopeList,
                    contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        ScopeRow(stringResource(R.string.source_range_all),
                            selected = state.selectedSource == null && state.scope == null,
                            count = state.sources.size, onClick = { onScope(null) })
                        ScopeRow(stringResource(R.string.search_single_source), selected = state.selectedSource != null,
                            opensSearch = true, value = selectedName, onClick = { choosingSource = true })
                    }
                    // Only categories with searchable sources; an active empty scope stays visible as selected.
                    items(SourceCategory.entries.filter { category ->
                        category == state.scope || state.sources.any { it.category == category }
                    }, key = { it.name }) { category ->
                        ScopeRow(stringResource(category.title), selected = state.selectedSource == null && state.scope == category,
                            count = state.sources.count { it.category == category }, onClick = { onScope(category) })
                    }
                }
            } else {
                val requester = remember { FocusRequester() }
                LaunchedEffect(pageTransition.currentState, pageTransition.targetState) {
                    if (pageTransition.currentState && pageTransition.targetState) requester.requestFocus()
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = back, modifier = Modifier.testTag("search_scope_back")) {
                            Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
                        }
                        TextField(value = query, onValueChange = { query = it },
                            modifier = Modifier.weight(1f).focusRequester(requester).testTag("search_source_query"),
                            placeholder = { Text(stringResource(R.string.search_find_source)) }, singleLine = true,
                            shape = MaterialTheme.shapes.extraLarge,
                            trailingIcon = if (query.isNotEmpty()) {{
                                IconButton(onClick = { query = "" }) {
                                    Icon(painterResource(R.drawable.close_24px), stringResource(R.string.search_clear_query), Modifier.size(20.dp))
                                }
                            }} else null,
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }))
                    }
                    key(query.trim()) {
                        LazyColumn(Modifier.weight(1f).testTag("search_source_matches"), state = rememberLazyListState(),
                            contentPadding = PaddingValues(bottom = 16.dp)) {
                            if (query.isNotBlank()) item {
                                Text(if (matches.isEmpty()) stringResource(R.string.search_source_not_found)
                                    else stringResource(R.string.search_source_matches, matches.size),
                                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(matches, key = { it.id.toString() }) { source ->
                                ScopeRow(source.name, selected = source.id == state.selectedSource,
                                    description = stringResource(if (query.isBlank()) R.string.search_selected_source
                                        else source.category?.title ?: R.string.source_group_ungrouped),
                                    onClick = { keyboard?.hide(); focus.clearFocus(); onSource(source.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(title: String, selected: Boolean, count: Int? = null, description: String? = null,
    opensSearch: Boolean = false, value: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        .clip(MaterialTheme.shapes.medium)
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
        .selectable(selected, role = if (opensSearch) Role.Button else Role.RadioButton, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (opensSearch) {
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(24.dp).testTag("search_source_arrow"), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.arrow_forward_ios_24px), null,
                    Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (count != null) Text(count.toString(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
