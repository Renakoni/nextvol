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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import io.nightfish.lightnovelreader.api.identifier.Identifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScopeSheet(
    state: SearchHubState,
    onDismiss: () -> Unit,
    onScope: (String?) -> Unit,
    onSource: (Identifier) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    var choosingSource by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedName = if (state.selectedSource == null) {
        ""
    } else {
        state.sources.firstOrNull { it.id == state.selectedSource }?.name ?: state.selectedSourceName
    }
    val scopeList = rememberLazyListState()
    val matches = remember(state.sources, state.selectedSource, query) {
        if (query.isBlank()) {
            state.sources.filter { it.id == state.selectedSource }
        } else {
            state.sources.filter {
                it.name.contains(query.trim(), ignoreCase = true) ||
                    it.id.toString().contains(query.trim(), ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
    ) {
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
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(min = 240.dp, max = 520.dp),
            transitionSpec = {
                val direction = if (targetState) 1 else -1
                (
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 16 * direction })
                        togetherWith
                        (fadeOut(tween(100)) + slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 16 * direction })
                    ).using(null)
            },
        ) { showSource ->
            if (!showSource) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_scope_options"),
                    state = scopeList,
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.search_scope_title),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.search_scope_groups_help),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ScopeRow(
                            title = stringResource(R.string.source_range_all),
                            selected = state.selectedSource == null && state.scope == null,
                            count = state.sources.size,
                            onClick = { onScope(null) },
                        )
                        ScopeRow(
                            title = stringResource(R.string.search_single_source),
                            selected = state.selectedSource != null,
                            opensSearch = true,
                            value = selectedName,
                            onClick = { choosingSource = true },
                        )
                    }
                    if (state.scope == "" || state.sources.any { it.groupIds.isEmpty() }) {
                        item(key = "ungrouped") {
                            ScopeRow(
                                title = stringResource(R.string.source_group_ungrouped),
                                selected = state.selectedSource == null && state.scope == "",
                                count = state.sources.count { it.groupIds.isEmpty() },
                                onClick = { onScope("") },
                            )
                        }
                    }
                    items(state.groups, key = { it.id }) { group ->
                        ScopeRow(
                            title = group.name,
                            selected = state.selectedSource == null && state.scope == group.id,
                            count = state.sources.count { group.id in it.groupIds },
                            onClick = { onScope(group.id) },
                        )
                    }
                }
            } else {
                val requester = remember { FocusRequester() }
                LaunchedEffect(pageTransition.currentState, pageTransition.targetState) {
                    if (pageTransition.currentState && pageTransition.targetState) requester.requestFocus()
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 20.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EditorialIconButton(
                            onClick = back,
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(R.string.sources_back),
                            modifier = Modifier.testTag("search_scope_back"),
                        )
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .weight(1f)
                                .border(0.75.dp, tokens.cardBorder, CircleShape)
                                .focusRequester(requester)
                                .testTag("search_source_query"),
                            placeholder = { Text(stringResource(R.string.search_find_source)) },
                            singleLine = true,
                            shape = CircleShape,
                            trailingIcon = if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            painterResource(R.drawable.close_24px),
                                            stringResource(R.string.search_clear_query),
                                            Modifier.size(18.dp),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = tokens.cardSurface,
                                unfocusedContainerColor = tokens.cardSurface,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboard?.hide()
                                    focus.clearFocus()
                                },
                            ),
                        )
                    }
                    key(query.trim()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .testTag("search_source_matches"),
                            state = rememberLazyListState(),
                            contentPadding = PaddingValues(bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (query.isNotBlank()) {
                                item {
                                    Text(
                                        text = if (matches.isEmpty()) {
                                            stringResource(R.string.search_source_not_found)
                                        } else {
                                            stringResource(R.string.search_source_matches, matches.size)
                                        },
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(matches, key = { it.id.toString() }) { source ->
                                ScopeRow(
                                    title = source.name,
                                    selected = source.id == state.selectedSource,
                                    description = if (query.isBlank()) {
                                        stringResource(R.string.search_selected_source)
                                    } else {
                                        state.groups
                                            .filter { it.id in source.groupIds }
                                            .joinToString(" · ") { it.name }
                                            .ifEmpty { stringResource(R.string.source_group_ungrouped) }
                                    },
                                    onClick = {
                                        keyboard?.hide()
                                        focus.clearFocus()
                                        onSource(source.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeRow(
    title: String,
    selected: Boolean,
    count: Int? = null,
    description: String? = null,
    opensSearch: Boolean = false,
    value: String = "",
    onClick: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(if (selected) tokens.activePillFill else tokens.cardSurface)
            .border(
                width = if (selected) 1.dp else 0.75.dp,
                color = if (selected) tokens.activePillBorder else tokens.cardBorder,
                shape = shape,
            )
            .selectable(
                selected = selected,
                role = if (opensSearch) Role.Button else Role.RadioButton,
                onClick = onClick,
            )
            .heightIn(min = 54.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                ),
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (opensSearch) {
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    modifier = Modifier.weight(0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .testTag("search_source_arrow"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.arrow_forward_ios_24px),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        } else {
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.check_24px),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
