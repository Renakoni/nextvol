package indi.renakoni.nextvol.ui.tts

import androidx.compose.foundation.border
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRolesTopBar(
    query: String,
    onQuery: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSubmit: () -> Unit,
    suggestions: List<String>,
    onBack: () -> Unit,
    busy: Boolean,
    onAdd: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var suggest by remember { mutableStateOf(false) }
    val choices = suggestions.filter { it.contains(query.trimEnd().substringAfterLast(' '), ignoreCase = true) }
    LaunchedEffect(searching) {
        if (searching) {
            focus.requestFocus()
            suggest = true
        }
    }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = tokens.topBarFallbackSurface,
        ),
        title = {
            if (!searching) {
                SpeechPageTitle(stringResource(R.string.tts_roles))
            } else {
                Box {
                    Surface(
                        shape = CircleShape,
                        color = tokens.cardSurface,
                        modifier = Modifier.border(0.75.dp, tokens.cardBorder, CircleShape),
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 42.dp)
                                .padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = query,
                                onValueChange = {
                                    onQuery(it)
                                    suggest = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focus)
                                    .testTag("role-search-input"),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        suggest = false
                                        onSubmit()
                                        keyboard?.hide()
                                    },
                                ),
                                decorationBox = { field ->
                                    Box {
                                        if (query.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.tts_role_search),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        field()
                                    }
                                },
                            )
                            IconButton(onClick = onCloseSearch) {
                                Icon(
                                    painter = painterResource(R.drawable.close_24px),
                                    contentDescription = stringResource(if (query.isNotEmpty()) R.string.search_clear_query else R.string.close),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    EditorialPopupMenu(
                        expanded = searching && suggest && choices.isNotEmpty(),
                        onDismissRequest = { suggest = false },
                        modifier = Modifier.heightIn(max = 280.dp),
                        properties = PopupProperties(focusable = false),
                    ) {
                        choices.forEach { suggestion ->
                            EditorialPopupMenuItem(
                                text = suggestion,
                                onClick = {
                                    val prefix = query.trimEnd().substringBeforeLast(' ', "")
                                    onQuery((if (prefix.isEmpty()) "" else "$prefix ") + suggestion + " ")
                                    suggest = false
                                },
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            EditorialIconButton(
                onClick = { if (searching) onCloseSearch() else onBack() },
                painter = painterResource(R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
        },
        actions = {
            if (!searching) {
                EditorialIconButton(
                    onClick = onSearch,
                    painter = painterResource(R.drawable.search_24px),
                    contentDescription = stringResource(R.string.tts_role_search),
                )
            }
            EditorialIconButton(
                onClick = onAdd,
                enabled = !busy,
                painter = painterResource(R.drawable.add_circle_24px),
                contentDescription = stringResource(R.string.tts_role_add),
                modifier = Modifier.testTag("role-add"),
            )
        },
    )
}
