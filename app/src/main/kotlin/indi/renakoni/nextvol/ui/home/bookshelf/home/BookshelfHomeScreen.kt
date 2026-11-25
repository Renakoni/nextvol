package indi.renakoni.nextvol.ui.home.bookshelf.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.backup.BackupArchive
import indi.renakoni.nextvol.data.work.SaveBookshelfWork
import indi.renakoni.nextvol.ui.LocalBottomBarController
import indi.renakoni.nextvol.ui.components.backupFailureMessage
import indi.renakoni.nextvol.ui.components.design.GlassVariant
import indi.renakoni.nextvol.ui.components.design.LiquidGlassSurface
import indi.renakoni.nextvol.utils.textToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfHomeScreen(
    init: () -> Unit,
    uiState: BookshelfHomeUiState,
    onSettings: () -> Unit,
    onImportLocalBook: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val shareTitle by rememberUpdatedState(stringResource(R.string.share_bookshelf))
    val workManager = WorkManager.getInstance(context)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val setBottomBarVisible = LocalBottomBarController.current
    val contentHazeState = rememberHazeState()
    val backgroundColor by animateColorAsState(
        targetValue = if (uiState.selectMode) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "bookshelfTopBarBg",
    )
    val listState = rememberSaveable(uiState.selectedBookshelfId, saver = LazyListState.Saver) { LazyListState() }
    val gridState = rememberSaveable(uiState.selectedBookshelfId, saver = LazyGridState.Saver) { LazyGridState() }

    LaunchedEffect(uiState.selectMode) {
        setBottomBarVisible(!uiState.selectMode)
    }
    DisposableEffect(Unit) {
        onDispose { setBottomBarVisible(true) }
    }

    BackHandler(uiState.selectMode) {
        uiState.onDisableSelectMode()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        init()
    }

    val shareBookshelf: () -> Unit = remember(
        context,
        workManager,
        coroutineScope,
        uiState.selectedBookshelfId,
    ) {
        {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.applicationInfo.processName}.provider",
                File(context.cacheDir, BackupArchive.BOOKSHELF_FILE_NAME),
            )
            val workRequest = OneTimeWorkRequestBuilder<SaveBookshelfWork>()
                .setInputData(
                    workDataOf(
                        "bookshelfId" to uiState.selectedBookshelfId,
                        "uri" to uri.toString(),
                    ),
                )
                .build()
            workManager.enqueueUniqueWork(
                uri.toString(),
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
            coroutineScope.launch(Dispatchers.Main) {
                workManager.getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                    when (workInfo?.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            ShareCompat.IntentBuilder(context)
                                .setType("application/zip")
                                .setSubject(shareTitle)
                                .addStream(uri)
                                .setChooserTitle(shareTitle)
                                .startChooser()
                        }
                        WorkInfo.State.FAILED -> {
                            textToast(
                                context,
                                backupFailureMessage(workInfo.outputData, R.string.backup_export_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        else -> return@collect
                    }
                }
            }
        }
    }

    val barSlideSpec = spring<IntOffset>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BookshelfHomeTopBar(
                scrollBehavior = scrollBehavior,
                backgroundColor = backgroundColor,
                uiState = uiState,
                onShareBookshelf = shareBookshelf,
                onSettings = onSettings,
                onImportLocalBook = onImportLocalBook,
            )

            BookshelfHomeContent(
                modifier = Modifier
                    .weight(1f)
                    .hazeSource(contentHazeState),
                uiState = uiState,
                listState = listState,
                gridState = gridState,
                scrollBehavior = scrollBehavior,
            )
        }

        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = uiState.selectMode,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = barSlideSpec) + fadeIn(tween(200)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = barSlideSpec) + fadeOut(tween(160)),
        ) {
            BookshelfSelectionActionBar(
                hasSelection = uiState.selectedBookIds.isNotEmpty(),
                hazeState = contentHazeState,
                onPin = uiState.onPin,
                onCollect = uiState.onMarkSelectedBooks,
                onRemove = uiState.onRemove,
            )
        }
    }
}

@Composable
private fun BookshelfSelectionActionBar(
    hasSelection: Boolean,
    hazeState: HazeState,
    onPin: () -> Unit,
    onCollect: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                ),
            )
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassSurface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = CircleShape,
            variant = GlassVariant.Liquid,
            hazeState = hazeState,
            elevation = 12.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionCapsuleAction(
                    painter = painterResource(R.drawable.keep_24px),
                    label = stringResource(R.string.bookshelf_pin_selected),
                    tint = colors.onSurface,
                    enabled = hasSelection,
                    onClick = onPin,
                )
                SelectionCapsuleAction(
                    painter = painterResource(R.drawable.bookmark_add_24px),
                    label = stringResource(R.string.bookshelf_collect_selected),
                    tint = colors.primary,
                    enabled = hasSelection,
                    onClick = onCollect,
                )
                SelectionCapsuleAction(
                    painter = painterResource(R.drawable.bookmark_remove_24px),
                    label = stringResource(R.string.bookshelf_remove_selected),
                    tint = colors.error,
                    enabled = hasSelection,
                    onClick = onRemove,
                )
            }
        }
    }
}

@Composable
private fun RowScope.SelectionCapsuleAction(
    painter: Painter,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.W600),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
