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
import androidx.compose.ui.window.PopupProperties
import indi.renakoni.nextvol.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechRolesTopBar(query: String, onQuery: (String) -> Unit, searching: Boolean,
    onSearch: () -> Unit, onCloseSearch: () -> Unit, onSubmit: () -> Unit,
    suggestions: List<String>, onBack: () -> Unit, busy: Boolean, onAdd: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var suggest by remember { mutableStateOf(false) }
    val choices = suggestions.filter { it.contains(query.trimEnd().substringAfterLast(' '), ignoreCase = true) }
    LaunchedEffect(searching) { if (searching) { focus.requestFocus(); suggest = true } }
    TopAppBar(title = {
        if (!searching) SpeechPageTitle(stringResource(R.string.tts_roles))
        else Box {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.heightIn(min = 40.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(query, { onQuery(it); suggest = true }, Modifier.weight(1f).focusRequester(focus)
                        .testTag("role-search-input"), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { suggest = false; onSubmit(); keyboard?.hide() }),
                        decorationBox = { field -> Box {
                            if (query.isEmpty()) Text(stringResource(R.string.tts_role_search),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            field()
                        } })
                    IconButton(onClick = onCloseSearch) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(if (query.isNotEmpty()) R.string.search_clear_query else R.string.close))
                    }
                }
            }
            DropdownMenu(searching && suggest && choices.isNotEmpty(), { suggest = false },
                modifier = Modifier.heightIn(max = 280.dp), properties = PopupProperties(focusable = false)) {
                choices.forEach { suggestion -> DropdownMenuItem(text = { Text(suggestion) }, onClick = {
                    val prefix = query.trimEnd().substringBeforeLast(' ', "")
                    onQuery((if (prefix.isEmpty()) "" else "$prefix ") + suggestion + " ")
                    suggest = false
                }) }
            }
        }
    }, navigationIcon = {
        IconButton(onClick = { if (searching) onCloseSearch() else onBack() }) {
            Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
        }
    }, actions = {
        if (!searching) IconButton(onClick = onSearch) {
            Icon(painterResource(R.drawable.search_24px), stringResource(R.string.tts_role_search))
        }
        IconButton(onClick = onAdd, enabled = !busy, modifier = Modifier.testTag("role-add")) {
            Icon(painterResource(R.drawable.add_circle_24px), stringResource(R.string.tts_role_add))
        }
    })
}
