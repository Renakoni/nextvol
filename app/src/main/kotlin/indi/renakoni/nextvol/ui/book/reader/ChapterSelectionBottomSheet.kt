package indi.renakoni.nextvol.ui.book.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.Loading
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterSelectionBottomSheet(
    sheetState: SheetState,
    selectedVolumeId: String,
    bookVolumes: BookVolumes,
    readingChapterId: String,
    onDismissRequest: () -> Unit,
    onClickChapter: (chapterId: String) -> Unit,
    onChangeSelectedVolumeId: (volumeId: String) -> Unit,
    directoryIncomplete: Boolean = false,
) {
    val tokens = NextVolDesign.tokens
    val lazyColumnState = rememberLazyListState()
    var autoScrolled by rememberSaveable(bookVolumes.bookId, readingChapterId) { mutableStateOf(false) }
    var query by rememberSaveable(bookVolumes.bookId) { mutableStateOf("") }
    val initialVolumeId = remember(bookVolumes, readingChapterId, selectedVolumeId) {
        selectedVolumeId.ifBlank {
            bookVolumes.volumes.firstOrNull { volume ->
                volume.chapters.any { it.id == readingChapterId }
            }?.volumeId ?: bookVolumes.volumes.firstOrNull()?.volumeId.orEmpty()
        }
    }
    var expandedVolumeIds by rememberSaveable(bookVolumes.bookId) {
        mutableStateOf(listOfNotNull(initialVolumeId.takeIf { it.isNotBlank() }))
    }
    val titleQuery = query.trim()
    val searching = titleQuery.isNotEmpty()
    val searchListState = key(titleQuery) { rememberLazyListState() }
    val matches by key(bookVolumes, titleQuery) {
        produceState<List<DirectorySearchMatch>?>(null) {
            if (searching) value = withContext(Dispatchers.Default) { searchDirectory(bookVolumes, titleQuery) }
        }
    }
    val scope = rememberCoroutineScope()
    var searchFocused by remember { mutableStateOf(false) }
    val searchHint = stringResource(R.string.reader_directory_search_hint)
    val heightFraction by animateFloatAsState(
        targetValue = if (searchFocused || searching) 0.92f else 0.78f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "chapterSheetHeightFraction",
    )

    val readingLocation = remember(bookVolumes, readingChapterId) {
        bookVolumes.volumes.forEachIndexed { volumeIndex, volume ->
            val chapterIndex = volume.chapters.indexOfFirst { it.id == readingChapterId }
            if (chapterIndex >= 0) return@remember Triple(volumeIndex, volume.volumeId, chapterIndex)
        }
        null
    }

    val scrollToCurrent: () -> Unit = {
        readingLocation?.let { (_, volumeId, chapterIndex) ->
            val nextExpanded = if (volumeId in expandedVolumeIds) expandedVolumeIds else expandedVolumeIds + volumeId
            expandedVolumeIds = nextExpanded
            onChangeSelectedVolumeId(volumeId)
            val targetItemIndex = computeChapterListIndex(bookVolumes.volumes, nextExpanded, volumeId, chapterIndex)
            scope.launch {
                lazyColumnState.animateScrollToItem(targetItemIndex)
            }
        }
    }

    LaunchedEffect(sheetState.currentValue, readingLocation, autoScrolled, searching) {
        if (searching || autoScrolled || sheetState.currentValue == SheetValue.Hidden || readingLocation == null) {
            return@LaunchedEffect
        }
        val (_, volumeId, chapterIndex) = readingLocation
        if (volumeId !in expandedVolumeIds) {
            expandedVolumeIds = expandedVolumeIds + volumeId
            onChangeSelectedVolumeId(volumeId)
            return@LaunchedEffect
        }
        val targetItemIndex = computeChapterListIndex(bookVolumes.volumes, expandedVolumeIds, volumeId, chapterIndex)
        lazyColumnState.scrollToItem(targetItemIndex)
        autoScrolled = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = tokens.sheetRadius, topEnd = tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = query.isEmpty(),
            isAppearanceLightStatusBars = colorScheme.surface.luminance() > 0.5f,
            isAppearanceLightNavigationBars = colorScheme.surface.luminance() > 0.5f,
        ),
    ) {
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val closeSearch: () -> Unit = {
            query = ""
            focusManager.clearFocus()
            keyboard?.hide()
        }
        BackHandler(enabled = query.isNotEmpty(), onBack = closeSearch)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(heightFraction)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.select_chapter),
                    style = typography.displayMedium.copy(fontWeight = FontWeight.W600),
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (readingLocation != null && !searching) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.activePillFill)
                            .border(0.75.dp, tokens.activePillBorder, CircleShape)
                            .clickable(role = Role.Button, onClick = scrollToCurrent)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.target_24px),
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = stringResource(R.string.detail_directory_locate),
                            style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                            color = colorScheme.primary,
                        )
                    }
                }
            }

            if (directoryIncomplete) {
                Text(
                    text = stringResource(R.string.book_directory_incomplete),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    style = typography.bodySmall,
                    color = colorScheme.error,
                )
            }

            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .border(0.75.dp, tokens.cardBorder, CircleShape)
                    .semantics { contentDescription = searchHint }
                    .onFocusChanged {
                        searchFocused = it.isFocused
                        if (it.isFocused) scope.launch { sheetState.expand() }
                    },
                placeholder = { Text(searchHint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = closeSearch) {
                            Icon(
                                painter = painterResource(R.drawable.close_24px),
                                contentDescription = stringResource(R.string.reader_directory_clear_search),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = tokens.cardSurface,
                    unfocusedContainerColor = tokens.cardSurface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    },
                ),
            )

            if (searching && matches != null) {
                Text(
                    text = pluralStringResource(R.plurals.reader_directory_matches, matches!!.size, matches!!.size),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    style = typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colorScheme.onSurfaceVariant,
                )
            }

            EditorialHairlineDivider(Modifier.padding(top = 4.dp))

            if (!searching && bookVolumes.volumes.all { it.chapters.isEmpty() }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyPage(
                        icon = painterResource(R.drawable.empty_90dp),
                        title = stringResource(R.string.nothing_here),
                        description = stringResource(R.string.info_volume_chapters_count, 0),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = if (searching) searchListState else lazyColumnState,
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (searching) {
                        val results = matches
                        when {
                            results == null -> item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Loading()
                                }
                            }
                            results.isEmpty() -> item {
                                EmptyPage(
                                    icon = painterResource(R.drawable.not_found_90dp),
                                    title = stringResource(R.string.reader_directory_no_results),
                                    description = stringResource(R.string.reader_directory_no_results_hint),
                                )
                            }
                            else -> items(results, key = { it.key }, contentType = { "chapter" }) { result ->
                                DirectoryChapterRow(
                                    chapter = result.chapter,
                                    selected = readingChapterId == result.chapter.id,
                                    volumeTitle = result.volumeTitle,
                                ) {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    onClickChapter(result.chapter.id)
                                }
                            }
                        }
                    } else {
                        bookVolumes.volumes.forEach { volume ->
                            val expanded = volume.volumeId in expandedVolumeIds
                            item(key = "volume:${volume.volumeId}", contentType = "volume") {
                                DirectoryVolumeRow(volume, expanded) {
                                    val next = if (expanded) {
                                        expandedVolumeIds - volume.volumeId
                                    } else {
                                        expandedVolumeIds + volume.volumeId
                                    }
                                    expandedVolumeIds = next
                                    onChangeSelectedVolumeId(if (expanded) next.lastOrNull().orEmpty() else volume.volumeId)
                                }
                            }
                            if (expanded) {
                                items(
                                    items = volume.chapters,
                                    key = { "chapter:${volume.volumeId.length}:${volume.volumeId}:${it.id}" },
                                    contentType = { "chapter" },
                                ) { chapter ->
                                    DirectoryChapterRow(
                                        chapter = chapter,
                                        selected = readingChapterId == chapter.id,
                                    ) {
                                        onClickChapter(chapter.id)
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

private fun computeChapterListIndex(
    volumes: List<Volume>,
    expandedVolumeIds: List<String>,
    targetVolumeId: String,
    targetChapterIndex: Int,
): Int {
    var index = 0
    for (volume in volumes) {
        if (volume.volumeId == targetVolumeId) {
            return index + 1 + targetChapterIndex
        }
        index += 1
        if (volume.volumeId in expandedVolumeIds) {
            index += volume.chapters.size
        }
    }
    return 0
}

@Composable
private fun DirectoryVolumeRow(volume: Volume, expanded: Boolean, onClick: () -> Unit) {
    val tokens = NextVolDesign.tokens
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(200),
        label = "tocVolumeArrow",
    )
    val radius = 15.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .squircleBackground(color = tokens.cardSurface, cornerRadius = radius)
            .squircleBorder(width = 0.75.dp, color = tokens.cardBorder, cornerRadius = radius)
            .squircleClip(radius)
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(if (expanded) R.string.collapse else R.string.expand),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = volume.volumeTitle,
                style = typography.titleSmall.copy(fontWeight = FontWeight.W600),
                color = colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.info_volume_chapters_count, volume.chapters.size),
                style = typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(R.drawable.arrow_forward_ios_24px),
            contentDescription = null,
            modifier = Modifier
                .size(14.dp)
                .rotate(rotation),
            tint = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DirectoryChapterRow(
    chapter: ChapterInformation,
    selected: Boolean,
    volumeTitle: String? = null,
    onClick: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val radius = 13.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .squircleBackground(
                color = if (selected) tokens.activePillFill else Color.Transparent,
                cornerRadius = radius,
            )
            .then(
                if (selected) Modifier.squircleBorder(width = 0.75.dp, color = tokens.activePillBorder, cornerRadius = radius)
                else Modifier,
            )
            .squircleClip(radius)
            .selectable(selected, role = Role.Button, onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.play_arrow_24px),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = chapter.title,
                style = typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.W600 else FontWeight.W400,
                ),
                color = if (selected) colorScheme.primary else colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (volumeTitle != null) {
                Text(
                    text = volumeTitle,
                    style = typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
