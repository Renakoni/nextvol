package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton
import indi.renakoni.nextvol.ui.components.design.EditorialTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagerScreen(
    onClickBack: () -> Unit,
    uiState: StorageManagerUiState,
    clearReadingCache: suspend () -> Boolean,
    clearDownloads: suspend () -> Unit,
    onOpenBooks: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            EditorialTopBar(
                title = stringResource(R.string.storage_manager_title),
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
                        onClick = uiState.load,
                        enabled = !uiState.isLoading,
                        painter = painterResource(id = R.drawable.refresh_24px),
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                },
            )
        },
    ) { insets ->
        StorageOverviewContent(
            modifier = Modifier
                .padding(insets)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            uiState = uiState,
            clearReadingCache = clearReadingCache,
            clearDownloads = clearDownloads,
            onOpenBooks = onOpenBooks,
        )
    }
}
