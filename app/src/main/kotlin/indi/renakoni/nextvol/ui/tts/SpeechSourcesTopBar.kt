package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechSourcesTopBar(
    query: String,
    onQuery: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
    busy: Boolean,
    onImport: () -> Unit,
    onRefresh: (() -> Unit)?,
    title: Int = R.string.tts_sources,
    searchLabel: Int = R.string.tts_voice_search,
    onAdd: (() -> Unit)? = null,
    onManage: (() -> Unit)? = null,
    selectedCount: Int? = null,
    onExitManagement: () -> Unit = {},
    onBackgroundMusic: (() -> Unit)? = null,
    importLabel: Int = R.string.tts_import_sources,
) {
    val tokens = NextVolDesign.tokens
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = tokens.topBarFallbackSurface,
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!searching) {
                    if (selectedCount == null) {
                        SpeechPageTitle(stringResource(title))
                    } else {
                        Text(
                            text = stringResource(R.string.tts_manage_selected, selectedCount),
                            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.W600,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = tokens.cardSurface,
                        modifier = Modifier
                            .weight(1f)
                            .border(0.75.dp, tokens.cardBorder, CircleShape),
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 42.dp)
                                .padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = query,
                                onValueChange = onQuery,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focus)
                                    .testTag("voice-search-input"),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                decorationBox = { field ->
                                    Box {
                                        if (query.isEmpty()) {
                                            Text(
                                                text = stringResource(searchLabel),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        field()
                                    }
                                },
                            )
                            IconButton(onClick = { if (query.isNotEmpty()) onQuery("") else onCloseSearch() }) {
                                Icon(
                                    painter = painterResource(R.drawable.close_24px),
                                    contentDescription = stringResource(if (query.isNotEmpty()) R.string.search_clear_query else R.string.close),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        navigationIcon = {
            EditorialIconButton(
                onClick = {
                    if (selectedCount != null) onExitManagement()
                    else if (searching) onCloseSearch()
                    else onBack()
                },
                painter = painterResource(if (selectedCount != null) R.drawable.close_24px else R.drawable.arrow_back_24px),
                contentDescription = stringResource(if (selectedCount != null) R.string.tts_manage_exit else R.string.sources_back),
            )
        },
        actions = {
            if (!searching) {
                EditorialIconButton(
                    onClick = onSearch,
                    painter = painterResource(R.drawable.search_24px),
                    contentDescription = stringResource(searchLabel),
                )
            }
            if (onAdd != null) {
                EditorialIconButton(
                    onClick = onAdd,
                    enabled = !busy,
                    painter = painterResource(R.drawable.add_circle_24px),
                    contentDescription = stringResource(R.string.tts_engine_new),
                )
            } else if (selectedCount == null) {
                Box {
                    EditorialIconButton(
                        onClick = { menu = true },
                        painter = painterResource(R.drawable.more_vert_24px),
                        contentDescription = stringResource(R.string.tts_library_actions),
                    )
                    EditorialPopupMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        EditorialPopupMenuHeader(text = stringResource(R.string.tts_library_actions))
                        if (onBackgroundMusic != null) {
                            EditorialPopupMenuItem(
                                text = stringResource(R.string.tts_background_music),
                                leadingIcon = painterResource(R.drawable.music_note_24px),
                                modifier = Modifier.testTag("background-music-menu"),
                                onClick = {
                                    menu = false
                                    onBackgroundMusic()
                                },
                            )
                        }
                        if (onManage != null) {
                            EditorialPopupMenuItem(
                                text = stringResource(R.string.tts_manage_voices),
                                leadingIcon = painterResource(R.drawable.checklist_24px),
                                enabled = !busy,
                                onClick = {
                                    menu = false
                                    onManage()
                                },
                            )
                        }
                        EditorialPopupMenuItem(
                            text = stringResource(importLabel),
                            enabled = !busy,
                            leadingIcon = painterResource(R.drawable.input_24px),
                            onClick = {
                                menu = false
                                onImport()
                            },
                        )
                        if (onRefresh != null) {
                            EditorialHairlineDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            EditorialPopupMenuItem(
                                text = stringResource(R.string.tts_refresh),
                                enabled = !busy,
                                leadingIcon = painterResource(R.drawable.refresh_24px),
                                onClick = {
                                    menu = false
                                    onRefresh()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}
