package indi.renakoni.nextvol.ui.book.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R

@Composable
internal fun DirectoryVolumeHeader(
    volumeId: String,
    title: String,
    readCount: Int,
    chapterCount: Int,
    pageRange: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(200))
    val fullyRead = readCount >= chapterCount
    val progress = if (fullyRead) stringResource(R.string.info_reading_finished)
        else stringResource(R.string.info_reading_progress, readCount, chapterCount)
    Row(
        Modifier.fillMaxWidth().testTag("directory-volume:$volumeId")
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand),
                role = Role.Button, onClick = onToggle,
            ).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(5f).padding(vertical = 12.dp)) {
            Text(title, style = typography.titleMedium,
                color = if (fullyRead) colorScheme.secondary else colorScheme.onSurface)
            Text(if (pageRange == null) progress else "$progress · $pageRange",
                style = typography.titleSmall, fontWeight = FontWeight.Normal, color = colorScheme.secondary)
        }
        Spacer(Modifier.weight(1f))
        Icon(painterResource(R.drawable.arrow_forward_ios_24px), null, Modifier.size(16.dp).rotate(rotation))
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
internal fun DirectoryToolbar(
    chapterCount: Int,
    page: Int,
    descending: Boolean,
    hideRead: Boolean,
    selecting: Boolean,
    canLocate: Boolean,
    searchExpanded: Boolean,
    query: String,
    matchCount: Int?,
    onPageChange: (Int) -> Unit,
    onToggleOrder: () -> Unit,
    onToggleHideRead: () -> Unit,
    onLocate: () -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    var optionsExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.detail_contents), modifier = Modifier.alignByBaseline(),
                    style = typography.displayMedium, fontWeight = FontWeight.W600)
                Text(stringResource(R.string.info_volume_chapters_count, chapterCount),
                    modifier = Modifier.alignByBaseline(), style = typography.labelMedium,
                    color = colorScheme.onSurfaceVariant)
            }
            IconToggleButton(checked = descending, onCheckedChange = { onToggleOrder() }, enabled = chapterCount > 0) {
                Icon(painterResource(R.drawable.sort_24px),
                    stringResource(if (descending) R.string.detail_directory_descending else R.string.detail_directory_ascending),
                    modifier = Modifier.rotate(if (descending) 0f else 180f),
                    tint = if (descending) colorScheme.primary else colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleSearch, enabled = chapterCount > 0) {
                Icon(painterResource(if (searchExpanded) R.drawable.close_24px else R.drawable.search_24px),
                    stringResource(if (searchExpanded) R.string.reader_directory_clear_search else R.string.detail_directory_search))
            }
            Box {
                IconButton(onClick = { optionsExpanded = true }, enabled = chapterCount > 0) {
                    Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.detail_directory_options),
                        tint = if (hideRead && !selecting) colorScheme.primary else colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = optionsExpanded, onDismissRequest = { optionsExpanded = false }) {
                    if (canLocate) DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_directory_locate), style = typography.bodyLarge) },
                        onClick = { optionsExpanded = false; onLocate() },
                    )
                    if (!selecting) DropdownMenuItem(
                        text = { Text(stringResource(R.string.hide_read), style = typography.bodyLarge) },
                        modifier = Modifier.semantics { selected = hideRead },
                        trailingIcon = { if (hideRead) Icon(painterResource(R.drawable.check_24px), null) },
                        onClick = { optionsExpanded = false; onToggleHideRead() },
                    )
                }
            }
        }
        val matches = if (matchCount != null) pluralStringResource(R.plurals.reader_directory_matches, matchCount, matchCount) else null
        val status = listOfNotNull(matches, if (hideRead && !selecting) stringResource(R.string.hide_read) else null)
            .joinToString(" · ")
        val showPages = query.isBlank() && directoryPageCount(chapterCount) > 1
        if (status.isNotEmpty() || showPages) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(status, modifier = Modifier.weight(1f), style = typography.labelMedium, color = colorScheme.onSurfaceVariant)
                if (showPages) DirectoryRangeMenu(chapterCount, page, descending, onPageChange)
            }
        }
        if (searchExpanded) {
            val focus = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = query, onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.reader_directory_search_hint)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedPlaceholderColor = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    unfocusedPlaceholderColor = colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                ),
                leadingIcon = { Icon(painterResource(R.drawable.search_24px), null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                        Icon(painterResource(R.drawable.close_24px), stringResource(R.string.reader_directory_clear_search))
                    }
                },
                shape = RoundedCornerShape(16.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
        }
    }
}

@Composable
internal fun DirectoryPageNavigation(
    chapterCount: Int, page: Int, descending: Boolean, onPageChange: (Int) -> Unit,
) {
    val pageCount = directoryPageCount(chapterCount)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("directory-pagination"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onPageChange(page - 1) }, enabled = page > 0) {
            Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.detail_directory_previous), Modifier.size(20.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            DirectoryRangeMenu(chapterCount, page, descending, onPageChange)
        }
        IconButton(onClick = { onPageChange(page + 1) }, enabled = page < pageCount - 1) {
            Icon(painterResource(R.drawable.arrow_forward_24px), stringResource(R.string.detail_directory_next), Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DirectoryRangeMenu(chapterCount: Int, page: Int, descending: Boolean, onPageChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val range = directoryPageRange(chapterCount, page, descending)
    val pageCount = directoryPageCount(chapterCount)
    val scrollState = rememberScrollState()
    val rowHeight = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(expanded, page, descending, scrollState.maxValue) {
        if (expanded) {
            scrollState.scrollTo(page * rowHeight)
        }
    }
    Box {
        val rangeDescription = stringResource(R.string.detail_directory_range_summary, range.first + 1, range.last + 1, chapterCount)
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = rangeDescription },
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.onSurfaceVariant),
        ) {
            Text(stringResource(R.string.detail_directory_range, range.first + 1, range.last + 1), style = typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.arrow_forward_ios_24px), stringResource(R.string.detail_directory_ranges),
                Modifier.size(12.dp).rotate(if (expanded) 270f else 90f))
        }
        DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp), scrollState = scrollState,
        ) {
            for (index in 0 until pageCount) {
                val entryRange = directoryPageRange(chapterCount, index, descending)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.detail_directory_range, entryRange.first + 1, entryRange.last + 1),
                        color = if (index == page) colorScheme.primary else colorScheme.onSurface) },
                    modifier = Modifier.semantics { selected = index == page },
                    trailingIcon = { if (index == page) Icon(painterResource(R.drawable.check_24px), null, tint = colorScheme.primary) },
                    onClick = { expanded = false; onPageChange(index) },
                )
            }
        }
    }
}
