package indi.renakoni.nextvol.ui.home.bookshelf.edit

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.LnrSnackbar
import indi.renakoni.nextvol.ui.components.SettingsMenuEntry
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar
import indi.renakoni.nextvol.ui.home.bookshelf.BookshelfSortTypeOptions
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.utils.LocalClaimSnackbarHost
import indi.renakoni.nextvol.utils.LocalSnackbarHost
import indi.renakoni.nextvol.utils.navigationBarSpacer
import indi.renakoni.nextvol.utils.showSnackbar
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookshelfScreen(
    title: String,
    bookshelfId: Int,
    bookshelf: Bookshelf,
    init: (Int) -> Unit,
    onClickBack: () -> Unit,
    onClickSave: () -> Unit,
    onClickDelete: (Int) -> Unit,
    onNameChange: (String) -> Unit,
    onSortTypeChange: (BookshelfSortType) -> Unit,
    onAutoCacheChange: (Boolean) -> Unit,
    onSystemUpdateReminderChange: (Boolean) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHost.current
    val pinnedScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val interactionSource = remember { MutableInteractionSource() }
    var nameValidationTriggered by rememberSaveable(bookshelfId) { mutableStateOf(false) }
    val isNameEmpty = bookshelf.name.isBlank()
    val showNameError = nameValidationTriggered && isNameEmpty
    val bookshelfNamePlaceholder = stringResource(R.string.bookshelf_name_placeholder)

    val handleSave = {
        if (isNameEmpty) {
            nameValidationTriggered = true
            showSnackbar(
                coroutineScope = coroutineScope,
                hostState = snackbarHostState,
                message = bookshelfNamePlaceholder,
            )
        } else {
            onClickSave()
        }
    }

    LaunchedEffect(bookshelfId) {
        init(bookshelfId)
    }
    val claim = LocalClaimSnackbarHost.current

    DisposableEffect(Unit) {
        claim(true)
        onDispose { claim(false) }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pinnedScrollBehavior.nestedScrollConnection),
        topBar = {
            TopBar(
                title = title,
                scrollBehavior = pinnedScrollBehavior,
                onClickBack = onClickBack,
                onClickSave = handleSave,
            )
        },
        snackbarHost = {
            SnackbarHost(LocalSnackbarHost.current) { data -> LnrSnackbar(data) }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        ) {
            item(key = "name_input") {
                EditorialCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 14.dp),
                    cornerRadius = tokens.cardRadius,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = bookshelf.name,
                        onValueChange = {
                            nameValidationTriggered = true
                            onNameChange(it)
                        },
                        label = { Text(stringResource(R.string.name)) },
                        placeholder = { Text(bookshelfNamePlaceholder) },
                        supportingText = if (showNameError) {
                            {
                                Text(
                                    text = bookshelfNamePlaceholder,
                                    color = colors.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(tokens.controlRadius),
                        interactionSource = interactionSource,
                        isError = showNameError,
                        trailingIcon = if (bookshelf.name.isNotEmpty()) {
                            {
                                IconButton(
                                    onClick = {
                                        nameValidationTriggered = true
                                        onNameChange("")
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.cancel_24px),
                                        contentDescription = stringResource(R.string.action_clear_text),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            item(key = "bookshelf_settings") {
                SettingsCategory(title = stringResource(R.string.bookshelf_settings)) {
                    SettingsMenuEntry(
                        painter = painterResource(R.drawable.sort_24px),
                        title = stringResource(R.string.bookshelf_sort_type),
                        options = BookshelfSortTypeOptions,
                        selectedOptionKey = bookshelf.sortType.key,
                        onOptionChange = {
                            onSortTypeChange(BookshelfSortTypeOptions.getOptionWithValue(it).value)
                        },
                    )
                    SettingsSwitchEntry(
                        painter = painterResource(R.drawable.cloud_download_24px),
                        title = stringResource(R.string.settings_auto_cache),
                        description = stringResource(R.string.settings_auto_cache_desc),
                        checked = bookshelf.autoCache,
                        onCheckedChange = onAutoCacheChange,
                    )
                    SettingsSwitchEntry(
                        painter = painterResource(R.drawable.outline_schedule_24px),
                        title = stringResource(R.string.settings_book_update_reminder),
                        description = stringResource(R.string.settings_book_update_reminder_desc),
                        checked = bookshelf.systemUpdateReminder,
                        onCheckedChange = onSystemUpdateReminderChange,
                    )
                }
            }

            if (bookshelfId >= 0) {
                item(key = "danger_delete") {
                    Spacer(Modifier.height(6.dp))
                    EditorialCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        cornerRadius = tokens.cardRadius,
                        containerColor = colors.errorContainer.copy(alpha = if (tokens.isDark) 0.34f else 0.45f),
                        borderColor = colors.error.copy(alpha = 0.42f),
                        onClick = { onClickDelete(bookshelfId) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            EditorialIconBadge(
                                painter = painterResource(R.drawable.delete_forever_24px),
                                containerColor = colors.error.copy(alpha = 0.16f),
                                contentColor = colors.error,
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_delete_bookshelf),
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.W600),
                                    color = colors.error,
                                )
                                Text(
                                    text = stringResource(R.string.settings_delete_bookshelf_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onErrorContainer.copy(alpha = 0.85f),
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                                contentDescription = null,
                                tint = colors.error.copy(alpha = 0.72f),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            navigationBarSpacer()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onClickBack: () -> Unit,
    onClickSave: () -> Unit,
) {
    EditorialTopBar(
        title = title,
        scrollBehavior = scrollBehavior,
        hazeState = null,
        frosted = false,
        navigationIcon = {
            EditorialIconButton(
                onClick = onClickBack,
                painter = painterResource(id = R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
        },
        actions = {
            EditorialIconButton(
                onClick = onClickSave,
                painter = painterResource(R.drawable.save_24px),
                contentDescription = stringResource(R.string.action_save),
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
        },
    )
}
