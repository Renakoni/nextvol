package indi.renakoni.nextvol.ui.book.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import kotlinx.coroutines.launch

/** Reader-local modal surface. Material owns the sheet gestures and predictive back, not a Dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
    state: SheetState,
    onDismissRequest: () -> Unit,
    backHandlerEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val dismiss = {
        scope.launch {
            state.hide()
            if (!state.isVisible) onDismissRequest()
        }
        Unit
    }
    val closeLabel = stringResource(R.string.close)
    val sheetTitle = stringResource(R.string.reader_settings)
    val scrim = BottomSheetDefaults.ScrimColor
    val alpha by animateFloatAsState(
        if (state.targetValue == SheetValue.Hidden) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "reader-settings-scrim",
    )
    // Consume only the sheet's actual top offset, so expanded content clears the status bar
    // without adding status-bar padding to the partially expanded surface.
    val offsetInsets = remember(state) {
        object : WindowInsets {
            override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
            override fun getBottom(density: Density) = 0
            override fun getTop(density: Density) =
                if (state.hasExpandedState) state.requireOffset().toInt().coerceAtLeast(0) else 0
        }
    }
    BackHandler(backHandlerEnabled && !state.isVisible) { onDismissRequest() }
    Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(dismiss) { detectTapGestures { dismiss() } }
            .semantics {
                contentDescription = closeLabel
                traversalIndex = 1f
                onClick { dismiss(); true }
            }) { drawRect(scrim, alpha = alpha) }
        BottomSheet(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter).consumeWindowInsets(offsetInsets)
                .semantics { paneTitle = sheetTitle }
                .focusRequester(focus)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            onDismissRequest = onDismissRequest,
            backHandlerEnabled = backHandlerEnabled,
            contentWindowInsets = { BottomSheetDefaults.modalWindowInsets },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 16.dp,
            content = content,
        )
    }
    if (state.hasExpandedState) {
        LaunchedEffect(state) {
            state.show()
            if (inputMode.inputMode == InputMode.Keyboard) focus.requestFocus()
        }
    }
}
