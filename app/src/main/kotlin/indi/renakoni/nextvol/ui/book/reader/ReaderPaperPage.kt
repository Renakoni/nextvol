package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry

@Composable
internal fun ReaderPaperEntry(settings: ReaderSettingsEditor, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsClickableEntry(
        modifier = modifier,
        painter = painterResource(R.drawable.article_24px),
        title = stringResource(R.string.paper_settings),
        option = stringResource(ReaderPaper.fromId(settings.paperId).label),
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderPaperPage(
    settings: ReaderSettingsEditor, onBack: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    Surface(Modifier.fillMaxSize()) {
        Column {
            TopAppBar(
                windowInsets = windowInsets,
                title = { Text(stringResource(R.string.paper_settings),
                    style = MaterialTheme.typography.displayLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back_24px), stringResource(R.string.sources_back))
                    }
                },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) { ReaderPaperSelector(settings) }
        }
    }
}
