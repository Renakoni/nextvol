package indi.renakoni.nextvol.ui.home.bookshelf.home

import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import indi.renakoni.nextvol.ui.home.HomeSettingsAction
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfSortTypeOptions
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationGraphicsApi::class)
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
    val sortLocked = uiState.selectedBookshelf?.sortType != BookshelfSortType.Default
    val layoutIcon = if (uiState.layout == BookshelfLayout.List) R.drawable.bookshelf_layout_grid_24px else R.drawable.view_list_24px
    val layoutLabel = stringResource(if (uiState.layout == BookshelfLayout.List) R.string.bookshelf_layout_switch_grid else R.string.bookshelf_layout_switch_list)
    val switchLayout = {
        uiState.changeLayout(if (uiState.layout == BookshelfLayout.List) BookshelfLayout.Grid else BookshelfLayout.List)
    }

    TopAppBar(
        expandedHeight = 56.dp,
        title = {
            if (uiState.selectMode) {
                val selectionLabel = stringResource(R.string.nav_bookshelf_select_mode, uiState.selectedBookIds.size)
                Text(
                    text = uiState.selectedBookIds.size.toString(),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = selectionLabel },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            if (uiState.selectMode) {
                IconButton(
                    onClick = uiState.onDisableSelectMode
                ) {
                    Icon(
                        painter = painterResource(R.drawable.cancel_24px),
                        contentDescription = stringResource(R.string.cancel)
                    )
                }
            } else {
                Icon(
                    painter = rememberAnimatedVectorPainter(AnimatedImageVector.animatedVectorResource(R.drawable.animated_bookshelf), false),
                    contentDescription = stringResource(R.string.nav_bookshelf),
                    modifier = Modifier.padding(12.dp)
                )
            }
        },
        actions = {
            if (!uiState.selectMode) {
                IconButton(onClick = switchLayout) {
                    Icon(painterResource(layoutIcon), layoutLabel)
                }
            }
            when {
                !uiState.selectMode -> {
                    Box {
                        var sortMenuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.sort_24px),
                                contentDescription = stringResource(R.string.action_sort)
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            Text(
                                text = stringResource(R.string.bookshelf_sort_type),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(top = 12.dp, bottom = 6.dp)
                            )
                            BookshelfSortTypeOptions.optionList.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(item.nameId),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    leadingIcon = {
                                        RadioButton(
                                            selected = item.key == uiState.selectedBookshelf?.sortType?.key,
                                            onClick = null
                                        )
                                    },
                                    onClick = {
                                        uiState.changeSortType(BookshelfSortTypeOptions.getOptionWithValue(item.key).value)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                enabled = uiState.selectedBookshelf?.sortType != BookshelfSortType.Default,
                                text = {
                                    Text(
                                        text = stringResource(R.string.bookshelf_sort_reverse),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                leadingIcon = {
                                    Checkbox(
                                        checked = uiState.selectedBookshelf?.sortType != BookshelfSortType.Default &&
                                                uiState.selectedBookshelf?.sortReversed == true,
                                        enabled = uiState.selectedBookshelf?.sortType != BookshelfSortType.Default,
                                        onCheckedChange = null
                                    )
                                },
                                onClick = {
                                    if (uiState.selectedBookshelf?.sortType == BookshelfSortType.Default) return@DropdownMenuItem
                                    uiState.selectedBookshelf?.sortReversed?.let {
                                        uiState.changeSortReversed(!it)
                                    }
                                }
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { mainMenuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.more_vert_24px),
                                contentDescription = stringResource(R.string.action_more_options)
                            )
                        }
                        DropdownMenu(
                            expanded = mainMenuExpanded,
                            onDismissRequest = { mainMenuExpanded = false }
                        ) {
                            Text(
                                text = uiState.selectedBookshelf?.name ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .padding(top = 12.dp, bottom = 6.dp),
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.bookshelf_create_title),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                onClick = {
                                    mainMenuExpanded = false
                                    uiState.onCreate()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.bookshelf_settings),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                onClick = {
                                    mainMenuExpanded = false
                                    uiState.onEdit(uiState.selectedBookshelfId)
                                }
                            )
                            DropdownMenuItem(
                                enabled = !sortLocked,
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = stringResource(R.string.bookshelf_adjust_order),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        if (sortLocked) {
                                            Text(
                                                text = stringResource(R.string.bookshelf_adjust_order_locked),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    mainMenuExpanded = false
                                    uiState.enableReorderMode()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.share_bookshelf),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                onClick = onShareBookshelf
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.bookshelf_adjust_bookshelf_order),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                onClick = {
                                    mainMenuExpanded = false
                                    uiState.enableBookshelfReorderMode()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.local_book_import), style = MaterialTheme.typography.bodyLarge) },
                                onClick = { mainMenuExpanded = false; onImportLocalBook() }
                            )
                        }
                    }
                }

                else -> {
                    IconButton(onClick = uiState.onSelectAll) {
                        Icon(
                            painter = painterResource(R.drawable.select_all_24px),
                            contentDescription = stringResource(R.string.select_all)
                        )
                    }
                    Box {
                        var expanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { expanded = true }) {
                            Icon(painterResource(R.drawable.more_vert_24px), stringResource(R.string.bookshelf_selection_actions))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text(layoutLabel) },
                                leadingIcon = { Icon(painterResource(layoutIcon), null) },
                                onClick = { expanded = false; switchLayout() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.bookshelf_pin_selected)) },
                                onClick = { expanded = false; uiState.onPin() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.bookshelf_remove_selected)) },
                                onClick = { expanded = false; uiState.onRemove() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.bookshelf_collect_selected)) },
                                onClick = { expanded = false; uiState.onMarkSelectedBooks() })
                        }
                    }
                }
            }
            HomeSettingsAction(onSettings)
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = backgroundColor,
            scrolledContainerColor = backgroundColor
        )
    )
}
