package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuHeader
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.home.HomeSettingsAction
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfSortTypeOptions
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfHomeTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    backgroundColor: Color,
    uiState: BookshelfHomeUiState,
    onShareBookshelf: () -> Unit,
    onSettings: () -> Unit,
    onImportLocalBook: () -> Unit,
) {
    var mainMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val selectedBookshelf = uiState.selectedBookshelf
    val sortLocked = selectedBookshelf?.sortType != BookshelfSortType.Default
    val isListLayout = uiState.layout == BookshelfLayout.List

    TopAppBar(
        title = {
            if (uiState.selectMode) {
                val selectionA11y = stringResource(R.string.nav_bookshelf_select_mode, uiState.selectedBookIds.size)
                Text(
                    text = stringResource(R.string.book_manager_selected_count, uiState.selectedBookIds.size),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = selectionA11y },
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.W700,
                        fontFeatureSettings = "tnum",
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(R.string.nav_bookshelf),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            if (uiState.selectMode) {
                EditorialIconButton(
                    onClick = uiState.onDisableSelectMode,
                    painter = painterResource(R.drawable.close_24px),
                    contentDescription = stringResource(R.string.cancel),
                )
            }
        },
        actions = {
            if (uiState.selectMode) {
                EditorialIconButton(
                    onClick = uiState.onSelectAll,
                    painter = painterResource(R.drawable.select_all_24px),
                    contentDescription = stringResource(R.string.select_all),
                )
                Spacer(Modifier.width(4.dp))
            } else {
                Box {
                    EditorialIconButton(
                        onClick = { sortMenuExpanded = true },
                        painter = painterResource(R.drawable.sort_24px),
                        contentDescription = stringResource(R.string.action_sort),
                    )
                    EditorialPopupMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        EditorialPopupMenuItem(
                            text = stringResource(
                                if (isListLayout) R.string.bookshelf_layout_switch_grid
                                else R.string.bookshelf_layout_switch_list,
                            ),
                            leadingIcon = painterResource(
                                if (isListLayout) R.drawable.bookshelf_layout_grid_24px
                                else R.drawable.view_list_24px,
                            ),
                            onClick = {
                                sortMenuExpanded = false
                                uiState.changeLayout(
                                    if (isListLayout) BookshelfLayout.Grid else BookshelfLayout.List,
                                )
                            },
                        )

                        EditorialHairlineDivider(modifier = Modifier.padding(vertical = 4.dp))

                        EditorialPopupMenuHeader(text = stringResource(R.string.bookshelf_sort_type))
                        BookshelfSortTypeOptions.optionList.forEach { item ->
                            val isSelected = item.key == selectedBookshelf?.sortType?.key
                            EditorialPopupMenuItem(
                                text = stringResource(item.nameId),
                                selected = isSelected,
                                onClick = {
                                    uiState.changeSortType(
                                        BookshelfSortTypeOptions.getOptionWithValue(item.key).value,
                                    )
                                },
                            )
                        }

                        EditorialHairlineDivider(modifier = Modifier.padding(vertical = 4.dp))

                        val reverseEnabled = selectedBookshelf?.sortType != BookshelfSortType.Default
                        val isReversed = reverseEnabled && selectedBookshelf?.sortReversed == true
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.bookshelf_sort_reverse),
                            leadingIcon = painterResource(R.drawable.sort_24px),
                            selected = isReversed,
                            enabled = reverseEnabled,
                            onClick = {
                                if (!reverseEnabled) return@EditorialPopupMenuItem
                                selectedBookshelf?.sortReversed?.let { current ->
                                    uiState.changeSortReversed(!current)
                                }
                            },
                        )
                    }
                }

                Box {
                    EditorialIconButton(
                        onClick = { mainMenuExpanded = true },
                        painter = painterResource(R.drawable.more_vert_24px),
                        contentDescription = stringResource(R.string.action_more_options),
                    )
                    EditorialPopupMenu(
                        expanded = mainMenuExpanded,
                        onDismissRequest = { mainMenuExpanded = false },
                    ) {
                        val shelfName = selectedBookshelf?.name.orEmpty()
                        if (shelfName.isNotBlank()) {
                            EditorialPopupMenuHeader(text = shelfName)
                        }
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.bookshelf_settings),
                            leadingIcon = painterResource(R.drawable.edit_square_24px),
                            onClick = {
                                mainMenuExpanded = false
                                uiState.onEdit(uiState.selectedBookshelfId)
                            },
                        )
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.bookshelf_adjust_order),
                            supportingText = if (sortLocked) {
                                stringResource(R.string.bookshelf_adjust_order_locked)
                            } else {
                                null
                            },
                            leadingIcon = painterResource(R.drawable.quick_reorder_24px),
                            enabled = !sortLocked,
                            onClick = {
                                mainMenuExpanded = false
                                uiState.enableReorderMode()
                            },
                        )
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.share_bookshelf),
                            leadingIcon = painterResource(R.drawable.ios_share_24px),
                            onClick = {
                                mainMenuExpanded = false
                                onShareBookshelf()
                            },
                        )

                        EditorialHairlineDivider(modifier = Modifier.padding(vertical = 4.dp))

                        EditorialPopupMenuItem(
                            text = stringResource(R.string.bookshelf_create_title),
                            leadingIcon = painterResource(R.drawable.add_circle_24px),
                            onClick = {
                                mainMenuExpanded = false
                                uiState.onCreate()
                            },
                        )
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.bookshelf_adjust_bookshelf_order),
                            leadingIcon = painterResource(R.drawable.outline_shelves_24px),
                            onClick = {
                                mainMenuExpanded = false
                                uiState.enableBookshelfReorderMode()
                            },
                        )
                        EditorialPopupMenuItem(
                            text = stringResource(R.string.local_book_import),
                            leadingIcon = painterResource(R.drawable.library_add_24px),
                            onClick = {
                                mainMenuExpanded = false
                                onImportLocalBook()
                            },
                        )
                    }
                }

                HomeSettingsAction(onSettings)
                Spacer(Modifier.width(4.dp))
            }
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = backgroundColor,
            scrolledContainerColor = backgroundColor,
        ),
    )
}
