package indi.renakoni.nextvol.ui.home.explore.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.AnimatedText
import indi.renakoni.nextvol.ui.components.BookCardItem
import indi.renakoni.nextvol.ui.components.EmptyPage
import indi.renakoni.nextvol.ui.components.LnrSnackbar
import indi.renakoni.nextvol.ui.components.SectionHeader
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.home.discovery.DiscoveryFailure
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.addToBookshelfAction
import indi.renakoni.nextvol.utils.navigationBarSpacer
import indi.renakoni.nextvol.utils.withHaptic

@Composable
fun ExploreSearchScreen(
    exploreSearchUiState: ExploreSearchUiState,
    refresh: () -> Unit,
    requestAddBookToBookshelf: (String) -> Unit,
    onClickBack: () -> Unit,
    onChangeSearchType: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClickDeleteHistory: (String) -> Unit,
    onClickClearAllHistory: () -> Unit,
    onClickBook: (String) -> Unit,
    updateSuggestions: (keyword: String) -> Unit,
    onManageSources: () -> Unit,
    onLoadMore: () -> Unit = {},
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val searchKeyword = exploreSearchUiState.query

    val submit: (String) -> Unit = { keyword ->
        if (keyword.isNotBlank()) {
            keyboard?.hide()
            focus.clearFocus()
            exploreSearchUiState.setSearchBarExpandedState(false)
            onSearch(keyword)
        }
    }

    val collapseSearch: () -> Unit = {
        keyboard?.hide()
        focus.clearFocus()
        exploreSearchUiState.setSearchBarExpandedState(false)
    }

    BackHandler(
        enabled = exploreSearchUiState.searchBarExpanded && exploreSearchUiState.submittedKeyword.isNotBlank(),
        onBack = collapseSearch,
    )

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    EditorialIconButton(
                        onClick = {
                            if (exploreSearchUiState.searchBarExpanded && exploreSearchUiState.submittedKeyword.isNotBlank()) {
                                collapseSearch()
                            } else {
                                onClickBack()
                            }
                        },
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = stringResource(R.string.sources_back),
                    )

                    TextField(
                        value = searchKeyword,
                        onValueChange = {
                            exploreSearchUiState.setSearchBarExpandedState(true)
                            updateSuggestions(it)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .border(0.75.dp, tokens.cardBorder, CircleShape)
                            .onFocusChanged { state ->
                                if (state.isFocused) exploreSearchUiState.setSearchBarExpandedState(true)
                            },
                        placeholder = {
                            AnimatedText(
                                text = exploreSearchUiState.searchTip.resolve(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
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
                        keyboardActions = KeyboardActions(onSearch = { submit(searchKeyword) }),
                        trailingIcon = if (searchKeyword.isNotBlank()) {
                            {
                                IconButton(
                                    onClick = {
                                        exploreSearchUiState.setSearchBarExpandedState(true)
                                        updateSuggestions("")
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.close_24px),
                                        contentDescription = stringResource(R.string.search_clear_query),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )

                    if (exploreSearchUiState.searchTypeIdList.size > 1) {
                        Box {
                            EditorialIconButton(
                                onClick = { exploreSearchUiState.setDropdownMenuExpandedState(true) },
                                painter = painterResource(R.drawable.filter_alt_24px),
                                contentDescription = stringResource(R.string.search_choose_type),
                            )
                            EditorialPopupMenu(
                                expanded = exploreSearchUiState.dropdownMenuExpanded,
                                onDismissRequest = { exploreSearchUiState.setDropdownMenuExpandedState(false) },
                            ) {
                                EditorialPopupMenuHeader(text = stringResource(R.string.search_choose_type))
                                exploreSearchUiState.searchTypeIdList.forEach { typeId ->
                                    val label = exploreSearchUiState.searchTypeNameMap[typeId]?.resolve() ?: return@forEach
                                    EditorialPopupMenuItem(
                                        text = label,
                                        selected = typeId == exploreSearchUiState.searchType,
                                        onClick = {
                                            exploreSearchUiState.setDropdownMenuExpandedState(false)
                                            onChangeSearchType(typeId)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    EditorialIconButton(
                        onClick = { submit(searchKeyword) },
                        enabled = searchKeyword.isNotBlank(),
                        painter = painterResource(R.drawable.search_24px),
                        contentDescription = stringResource(R.string.search_hub_title),
                        containerColor = if (searchKeyword.isNotBlank()) colors.primaryContainer.copy(alpha = 0.72f) else tokens.cardSurface,
                        contentColor = if (searchKeyword.isNotBlank()) colors.primary else colors.onSurfaceVariant,
                    )
                }

                val activeTypeLabel = exploreSearchUiState.searchTypeNameMap[exploreSearchUiState.searchType]?.resolve()
                if (exploreSearchUiState.sourceName.isNotBlank() || !activeTypeLabel.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (exploreSearchUiState.sourceName.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(tokens.activePillFill)
                                    .border(0.75.dp, tokens.activePillBorder, CircleShape)
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = exploreSearchUiState.sourceName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W600),
                                    color = colors.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (!activeTypeLabel.isNullOrBlank()) {
                            Text(
                                text = activeTypeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }
                EditorialHairlineDivider()
            }
        },
        snackbarHost = {
            SnackbarHost(LocalSnackbarHost.current) { data -> LnrSnackbar(data) }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding(),
        ) {
            AnimatedContent(
                targetState = exploreSearchUiState.searchBarExpanded,
                label = "exploreSearchMode",
            ) { expanded ->
                if (expanded) {
                    val hasHistory = exploreSearchUiState.historyList.any { it.isNotEmpty() }
                    val showHistory = exploreSearchUiState.suggestions.isEmpty() || searchKeyword.isEmpty()
                    Column(Modifier.fillMaxSize()) {
                        (exploreSearchUiState.failure ?: exploreSearchUiState.suggestionFailure)?.let { failure ->
                            DiscoveryFailure(
                                error = failure.error,
                                retry = refresh,
                                manage = onManageSources,
                                back = onClickBack,
                                field = failure.field,
                                permission = failure.permission,
                                diagnostic = failure.diagnostic,
                                httpStatus = failure.httpStatus,
                            )
                        }
                        if (showHistory && !hasHistory) {
                            EmptyPage(
                                icon = painterResource(R.drawable.schedule_90dp),
                                title = stringResource(R.string.nothing_here),
                                description = stringResource(R.string.nothing_here_desc_search),
                            )
                        } else if (showHistory) {
                            val entries = exploreSearchUiState.historyList.filter { it.isNotEmpty() }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item(key = "history_header") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SectionHeader(
                                            modifier = Modifier.weight(1f),
                                            text = stringResource(id = R.string.search_history),
                                        )
                                        TextButton(onClick = onClickClearAllHistory) {
                                            Text(stringResource(id = R.string.clear_all))
                                        }
                                    }
                                }
                                item(key = "history_card") {
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 20.dp,
                                    ) {
                                        entries.forEachIndexed { index, history ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { submit(history) }
                                                    .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.outline_schedule_24px),
                                                    contentDescription = null,
                                                    tint = colors.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Text(
                                                    text = history,
                                                    modifier = Modifier.weight(1f),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = colors.onSurface,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                IconButton(onClick = { onClickDeleteHistory(history) }) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.close_24px),
                                                        contentDescription = stringResource(R.string.search_delete_history),
                                                        tint = colors.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            }
                                            if (index != entries.lastIndex) {
                                                EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                                            }
                                        }
                                    }
                                }
                                navigationBarSpacer()
                            }
                        } else {
                            val suggestions = exploreSearchUiState.suggestions.filter { it.isNotEmpty() }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                item(key = "suggestions_card") {
                                    EditorialCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        cornerRadius = 20.dp,
                                    ) {
                                        suggestions.forEachIndexed { index, suggestion ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { submit(suggestion) }
                                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.search_24px),
                                                    contentDescription = null,
                                                    tint = colors.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Text(
                                                    text = suggestion,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = colors.onSurface,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (index != suggestions.lastIndex) {
                                                EditorialHairlineDivider(Modifier.padding(horizontal = 16.dp))
                                            }
                                        }
                                    }
                                }
                                navigationBarSpacer()
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        exploreSearchUiState.failure?.let { failure ->
                            DiscoveryFailure(
                                error = failure.error,
                                retry = refresh,
                                manage = onManageSources,
                                back = onClickBack,
                                field = failure.field,
                                permission = failure.permission,
                                diagnostic = failure.diagnostic,
                                httpStatus = failure.httpStatus,
                            )
                        }
                        if (exploreSearchUiState.isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                            )
                        }
                        if (
                            exploreSearchUiState.isLoadingComplete &&
                            exploreSearchUiState.searchResult.isEmpty() &&
                            exploreSearchUiState.nextPage == null &&
                            exploreSearchUiState.failure == null
                        ) {
                            EmptyPage(
                                icon = painterResource(R.drawable.not_found_90dp),
                                title = stringResource(R.string.search_no_results),
                                description = stringResource(R.string.search_no_results_desc),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (exploreSearchUiState.submittedKeyword.isNotBlank()) {
                                    stickyHeader {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(colors.surface.copy(alpha = 0.96f)),
                                        ) {
                                            AnimatedText(
                                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp),
                                                text = stringResource(
                                                    R.string.search_results_title,
                                                    exploreSearchUiState.submittedKeyword,
                                                    exploreSearchUiState.searchResult.size,
                                                    if (exploreSearchUiState.isLoadingComplete) "" else "...",
                                                ),
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontWeight = FontWeight.W600,
                                                    fontFeatureSettings = "tnum",
                                                ),
                                                color = colors.onSurfaceVariant,
                                            )
                                            EditorialHairlineDivider()
                                        }
                                    }
                                }
                                items(exploreSearchUiState.searchResult, key = { it.first }) { item ->
                                    val addToBookshelf = addToBookshelfAction.toSwipeAction {
                                        requestAddBookToBookshelf(item.first)
                                    }
                                    BookCardItem(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                        bookInformationFlow = item.second,
                                        onClick = { onClickBook(item.first) },
                                        onLongPress = withHaptic {},
                                        collected = exploreSearchUiState.allBookshelfBookIds.contains(item.first),
                                        swipeToRightActions = listOf(addToBookshelf),
                                        titleHeight = null,
                                    )
                                }
                                if (
                                    !exploreSearchUiState.isLoadingComplete &&
                                    exploreSearchUiState.failure == null &&
                                    exploreSearchUiState.submittedKeyword.isNotBlank()
                                ) {
                                    item {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp, horizontal = 20.dp),
                                        )
                                    }
                                }
                                if (exploreSearchUiState.nextPage != null && !exploreSearchUiState.isLoading) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            FilledTonalButton(
                                                onClick = onLoadMore,
                                                shape = RoundedCornerShape(14.dp),
                                            ) {
                                                Text(stringResource(R.string.search_load_more))
                                            }
                                        }
                                    }
                                }
                                navigationBarSpacer()
                            }
                        }
                    }
                }
            }
        }
    }
}
