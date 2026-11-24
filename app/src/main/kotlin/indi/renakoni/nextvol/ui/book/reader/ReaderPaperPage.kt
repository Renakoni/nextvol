package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.SettingsClickableEntry
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.components.design.EditorialIconButton

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
    settings: ReaderSettingsEditor,
    onBack: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    embedded: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (embedded) Color.Transparent else colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorialIconButton(
                onClick = onBack,
                painter = painterResource(R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.sources_back),
            )
            Text(
                text = stringResource(R.string.paper_settings),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.W600),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        EditorialHairlineDivider()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .then(if (embedded) Modifier else Modifier.navigationBarsPadding()),
        ) {
            ReaderPaperSelector(settings)
        }
    }
}
