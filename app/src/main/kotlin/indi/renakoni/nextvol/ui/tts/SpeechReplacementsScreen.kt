package indi.renakoni.nextvol.ui.tts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SPEECH_POOL_FEMALE
import indi.renakoni.nextvol.tts.SPEECH_POOL_MALE
import indi.renakoni.nextvol.tts.SpeechPersonOrigin
import indi.renakoni.nextvol.tts.SpeechReplacementFailure
import indi.renakoni.nextvol.tts.SpeechReplacementProblem
import indi.renakoni.nextvol.tts.messageResource
import indi.renakoni.nextvol.tts.speechLanguageLabel
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar

@Composable
internal fun SpeechReplacementsScreen(
    state: SpeechReplacementsState,
    onBack: () -> Unit,
    onEdit: (String?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onPreview: () -> Unit,
    onSort: (Boolean) -> Unit,
    onToggleGroup: (Set<String>, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(searching, closeSearch)
    val visible = remember(state.rules, query) { filterSpeechReplacements(state.rules, query) }
    val grouped = state.rules.any { it.group.isNotEmpty() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SpeechReplacementsTopBar(
                query = query,
                onQuery = { query = it },
                searching = searching,
                onSearch = { searching = true },
                onCloseSearch = closeSearch,
                onBack = onBack,
                busy = state.loading || state.saving,
                onAdd = { onEdit(null) },
                onPreview = onPreview,
                onSort = onSort,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("speech-replacements-list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
            }
            if (state.storageFailed) {
                item {
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Text(stringResource(R.string.tts_error_storage), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!state.loading && visible.isEmpty()) {
                item {
                    EmptyPage(
                        modifier = Modifier.fillParentMaxHeight(0.75f),
                        icon = painterResource(if (query.isEmpty()) R.drawable.find_replace_24px else R.drawable.search_24px),
                        title = stringResource(if (query.isEmpty()) R.string.tts_replace_title else R.string.tts_replace_no_results),
                        description = if (query.isEmpty()) stringResource(R.string.tts_replace_empty) else "",
                    )
                }
            }
            visible.groupBy { it.group }.forEach { (group, rules) ->
                val expanded = query.isNotEmpty() || group !in collapsed
                if (grouped) {
                    item(key = "group:$group") {
                        val enabledCount = rules.count { it.activate }
                        val label = group.ifEmpty { stringResource(R.string.tts_replace_ungrouped) }
                        val arrowRotation by animateFloatAsState(if (expanded) 0f else 180f, label = "replaceGroupArrow")
                        EditorialCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 16.dp,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        collapsed = if (group in collapsed) collapsed - group else collapsed + group
                                    }
                                    .padding(start = 14.dp, end = 6.dp)
                                    .heightIn(min = 52.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.keyboard_arrow_up_24px),
                                    contentDescription = stringResource(if (expanded) R.string.tts_replace_collapse else R.string.tts_replace_expand),
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(arrowRotation),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.W600,
                                )
                                TriStateCheckbox(
                                    state = when (enabledCount) {
                                        0 -> ToggleableState.Off
                                        rules.size -> ToggleableState.On
                                        else -> ToggleableState.Indeterminate
                                    },
                                    onClick = { onToggleGroup(rules.map { it.id }.toSet(), enabledCount != rules.size) },
                                    enabled = !state.saving,
                                    modifier = Modifier.semantics { contentDescription = label },
                                )
                            }
                        }
                    }
                }
                if (expanded) {
                    itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                        var menu by remember { mutableStateOf(false) }
                        EditorialCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("replace-rule-${rule.id}"),
                            cornerRadius = 16.dp,
                            onClick = { onEdit(rule.id) },
                            enabled = !state.saving,
                            contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = rule.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.W500,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (rule.source.isNotBlank()) {
                                        Text(
                                            text = "${rule.source} → ${rule.target}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Checkbox(
                                    checked = rule.activate,
                                    onCheckedChange = { onToggle(rule.id, it) },
                                    enabled = !state.saving,
                                    modifier = Modifier
                                        .testTag("replace-toggle-${rule.id}")
                                        .semantics { contentDescription = rule.label },
                                )
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = !state.saving) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert_24px),
                                            contentDescription = stringResource(R.string.tts_replace_actions, rule.label),
                                        )
                                    }
                                    EditorialPopupMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        EditorialPopupMenuHeader(text = rule.label)
                                        EditorialPopupMenuItem(
                                            text = stringResource(R.string.tts_replace_edit),
                                            leadingIcon = painterResource(R.drawable.edit_square_24px),
                                            onClick = {
                                                menu = false
                                                onEdit(rule.id)
                                            },
                                        )
                                        EditorialPopupMenuItem(
                                            text = stringResource(R.string.tts_replace_up),
                                            enabled = index > 0,
                                            onClick = {
                                                menu = false
                                                onMove(rule.id, state.rules.indexOf(rules[index - 1]) - state.rules.indexOf(rule))
                                            },
                                        )
                                        EditorialPopupMenuItem(
                                            text = stringResource(R.string.tts_replace_down),
                                            enabled = index < rules.lastIndex,
                                            onClick = {
                                                menu = false
                                                onMove(rule.id, state.rules.indexOf(rules[index + 1]) - state.rules.indexOf(rule))
                                            },
                                        )
                                        EditorialHairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                                        EditorialPopupMenuItem(
                                            text = stringResource(R.string.tts_replace_delete),
                                            leadingIcon = painterResource(R.drawable.delete_forever_24px),
                                            destructive = true,
                                            onClick = {
                                                menu = false
                                                onDelete(rule.id)
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechReplacementPreviewScreen(
    state: SpeechReplacementsState,
    onBack: () -> Unit,
    onPreview: (String) -> Unit,
    onClearPreview: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val sample = stringResource(R.string.tts_replace_sample)
    var text by rememberSaveable { mutableStateOf(sample) }
    DisposableEffect(Unit) { onDispose(onClearPreview) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.tts_replace_preview),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "preview_input") {
                EditorialCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReplacementField(
                            value = text,
                            onChange = {
                                text = it
                                onClearPreview()
                            },
                            label = stringResource(R.string.tts_replace_input),
                            modifier = Modifier.testTag("replace-preview-input"),
                            minLines = 3,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onPreview(text) },
                                enabled = !state.loading && !state.previewing,
                                shape = CircleShape,
                            ) {
                                Text(stringResource(R.string.tts_replace_run_preview))
                            }
                            if (state.previewing) {
                                TextButton(onClick = onClearPreview) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        }
                    }
                }
            }
            if (state.previewing) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
            }
            item { ReplacementPreviewError(state) }
            state.preview?.let { plan ->
                item(key = "preview_structured") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            modifier = Modifier.padding(start = 6.dp),
                            text = stringResource(R.string.tts_replace_after_whole),
                        )
                        EditorialCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = plan.structuredText,
                                    modifier = Modifier.testTag("replace-preview-structure"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                item(key = "preview_segments_header") {
                    SectionHeader(
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                        text = stringResource(R.string.tts_replace_after_normal),
                    )
                }
                if (plan.segments.isEmpty()) {
                    item {
                        EditorialCard(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.tts_replace_no_speech),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                itemsIndexed(plan.segments) { _, segment ->
                    EditorialCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 16.dp,
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                segment.speaker?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.W600,
                                    )
                                }
                                segment.roleMatch?.let { match ->
                                    val origin = stringResource(
                                        when (match.origin) {
                                            SpeechPersonOrigin.Manual -> R.string.tts_person_manual
                                            SpeechPersonOrigin.Pair -> R.string.tts_person_pair
                                            SpeechPersonOrigin.Ner -> R.string.tts_person_ner
                                            SpeechPersonOrigin.NewWord -> R.string.tts_person_new_word
                                            else -> R.string.tts_person_rule
                                        },
                                    )
                                    val group = when (match.poolGroup) {
                                        SPEECH_POOL_MALE -> stringResource(R.string.tts_pool_male)
                                        SPEECH_POOL_FEMALE -> stringResource(R.string.tts_pool_female)
                                        else -> match.poolGroup ?: match.ruleName
                                    }
                                    Text(
                                        text = listOf(origin, group, segment.selectedVoice?.label.orEmpty())
                                            .filter { it.isNotEmpty() }
                                            .joinToString(" → "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                segment.language?.takeIf { it != "lang:mark" && it != "lang:latin" }?.let {
                                    Text(
                                        text = stringResource(R.string.tts_segment_language, speechLanguageLabel(it.removePrefix("lang:"))),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(segment.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReplacementPreviewError(state: SpeechReplacementsState) {
    state.failure?.let { SpeechReplacementError(it) }
    state.previewError?.let { Text(stringResource(it.messageResource), color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun SpeechReplacementError(failure: SpeechReplacementFailure) {
    val reason = stringResource(
        when (failure.problem) {
            SpeechReplacementProblem.Pattern -> R.string.tts_replace_error_pattern
            SpeechReplacementProblem.Replacement -> R.string.tts_replace_error_target
            SpeechReplacementProblem.Budget -> R.string.tts_replace_error_budget
        },
    )
    Text(stringResource(R.string.tts_replace_error_rule, failure.ruleName, reason), color = MaterialTheme.colorScheme.error)
}
