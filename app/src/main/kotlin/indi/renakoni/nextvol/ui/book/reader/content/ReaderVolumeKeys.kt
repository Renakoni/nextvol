package indi.renakoni.nextvol.ui.book.reader.content

import android.view.ViewTreeObserver
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

internal val LocalReaderVolumeKeysEnabled = staticCompositionLocalOf { false }

@Composable
internal fun Modifier.readerVolumeKeys(
    enabled: Boolean,
    intervalSeconds: Float,
    onStep: suspend (ReaderVolumeDirection) -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val currentStep by rememberUpdatedState(onStep)
    val input = remember(scope, intervalSeconds) {
        ReaderVolumeKeyInput(scope, intervalSeconds) { currentStep(it) }
    }
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val windowInfo = LocalWindowInfo.current
    val view = LocalView.current
    val selection = LocalReaderSelectionState.current
    val available = enabled && LocalReaderRendererActive.current && LocalReaderVolumeKeysEnabled.current &&
        lifecycleState == Lifecycle.State.RESUMED && windowInfo.isWindowFocused && !selection.hasSelection

    SideEffect { input.setEnabled(available && focused) }
    LaunchedEffect(available) {
        if (available) focusRequester.requestFocus()
    }
    DisposableEffect(input, lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) input.setEnabled(false)
        }
        val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) input.setEnabled(false)
        }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowFocusListener)
        onDispose {
            input.setEnabled(false)
            lifecycle.removeObserver(observer)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(windowFocusListener)
        }
    }
    return this
        .onPreviewKeyEvent { event ->
            // A preview event is delivered only through the focused node. The focus callback can
            // lag one composition behind during a scroll remeasure, so do not reject that event
            // using the callback's cached state.
            input.setEnabled(available && windowInfo.isWindowFocused &&
                lifecycle.currentState == Lifecycle.State.RESUMED && !selection.hasSelection)
            val direction = when (event.key) {
                Key.VolumeUp -> ReaderVolumeDirection.Backward
                Key.VolumeDown -> ReaderVolumeDirection.Forward
                else -> return@onPreviewKeyEvent false
            }
            when (event.type) {
                KeyEventType.KeyDown -> input.onKey(direction, true, event.nativeKeyEvent.repeatCount)
                KeyEventType.KeyUp -> input.onKey(direction, false)
                else -> false
            }
        }
        .focusRequester(focusRequester)
        .onFocusChanged {
            focused = it.hasFocus
            if (!it.hasFocus) input.setEnabled(false)
        }
        .focusable()
}
