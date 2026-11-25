package indi.renakoni.nextvol.ui.tts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.tts.ReadAloudState
import indi.renakoni.nextvol.tts.SpeechAction
import indi.renakoni.nextvol.tts.SpeechPhase
import indi.renakoni.nextvol.tts.SpeechRequest
import indi.renakoni.nextvol.ui.components.design.GlassVariant
import indi.renakoni.nextvol.ui.components.design.LiquidGlassSurface
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val LocalHideReadAloudOverlay = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** Count owners because the sheet and settings can overlap during navigation transitions. */
@Composable
internal fun HideReadAloudOverlay() {
    val hide = LocalHideReadAloudOverlay.current
    DisposableEffect(hide) {
        hide(true)
        onDispose { hide(false) }
    }
}

@Composable
internal fun ReadAloudOverlayHost(
    state: ReadAloudState,
    onCommand: (SpeechAction) -> Unit,
    onOpenBook: (SpeechRequest) -> Unit,
    cover: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val bookId = state.request?.takeUnless { it.isPreview }?.bookId
    var collapsed by rememberSaveable(bookId, state.showFloatingPlayer) { mutableStateOf(false) }
    var hiddenOwners by remember { mutableIntStateOf(0) }
    val hide: (Boolean) -> Unit = remember { { hiddenOwners += if (it) 1 else -1 } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = bookId != null && state.showFloatingPlayer && hiddenOwners == 0 &&
        state.phase !in setOf(SpeechPhase.Stopped, SpeechPhase.Completed) &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var playerBounds by remember { mutableStateOf(Rect.Zero) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalHideReadAloudOverlay provides hide) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { hostOrigin = it.positionInRoot() }
                .pointerInput(visible) {
                    if (visible) {
                        awaitEachGesture {
                            // Observe before children consume the event, without consuming it ourselves.
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (!playerBounds.contains(down.position + hostOrigin)) collapsed = true
                        }
                    }
                },
        ) {
            content()
            // Keep placement across hidden panels, backgrounding and window size changes.
            ReadAloudFloatingPlayer(
                state = state,
                visible = visible,
                collapsed = collapsed,
                onCollapsed = { collapsed = it },
                cover = cover,
                onCommand = onCommand,
                onOpenBook = {
                    state.request?.let { request ->
                        val position = state.position?.takeIf { it.bookId == request.bookId }
                        onOpenBook(if (position == null) request else request.copy(chapterId = position.chapterId))
                    }
                },
                onBounds = { playerBounds = it },
            )
        }
    }
}

@Composable
private fun ReadAloudFloatingPlayer(
    state: ReadAloudState,
    visible: Boolean,
    collapsed: Boolean,
    onCollapsed: (Boolean) -> Unit,
    cover: @Composable () -> Unit,
    onCommand: (SpeechAction) -> Unit,
    onOpenBook: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    var dockLeft by rememberSaveable { mutableStateOf(false) }
    var heightFraction by rememberSaveable { mutableFloatStateOf(0.22f) }
    var rotation by rememberSaveable(state.request?.bookId) { mutableFloatStateOf(0f) }
    LaunchedEffect(visible, collapsed, state.phase, state.request?.bookId) {
        var previousFrame = 0L
        while (visible && !collapsed && state.phase == SpeechPhase.Playing && isActive) {
            withInfiniteAnimationFrameNanos { frame ->
                if (previousFrame != 0L) {
                    rotation = (rotation + (frame - previousFrame) / 16_000_000_000f * 360f) % 360f
                }
                previousFrame = frame
            }
        }
    }
    if (!visible) return
    // Docking and the cover/play/close order refer to physical screen edges.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            val density = LocalDensity.current
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val margin = with(density) { 12.dp.toPx() }
            val playerHeight = with(density) { 50.dp.toPx() }
            val maxY = (heightPx - playerHeight - margin).coerceAtLeast(margin)
            // Width, edge inset and content opacity share one clock; the edge cannot chase the width.
            val expansion by animateFloatAsState(if (collapsed) 0f else 1f, tween(220), label = "playerExpansion")
            val width = (40 + 96 * expansion).dp
            val playerWidth = with(density) { width.toPx() }
            var dragging by remember { mutableStateOf(false) }
            var dragX by remember { mutableFloatStateOf(0f) }
            var dragY by remember { mutableFloatStateOf(0f) }
            val scope = rememberCoroutineScope()
            val docking = remember { Animatable(1f) }
            var dockJob by remember { mutableStateOf<Job?>(null) }
            val dockMargin = margin * expansion
            val dockX = if (dockLeft) dockMargin else (widthPx - playerWidth - dockMargin).coerceAtLeast(0f)
            val x = (if (dragging) dragX else dragX + (dockX - dragX) * docking.value)
                .coerceIn(0f, (widthPx - playerWidth).coerceAtLeast(0f))
            val y = if (dragging) dragY else (heightFraction * heightPx).coerceIn(margin, maxY)
            val settle = {
                dockLeft = dragX + playerWidth / 2 < widthPx / 2
                heightFraction = dragY.coerceIn(margin, maxY) / heightPx.coerceAtLeast(1f)
                if (dragX <= 0f || dragX + playerWidth >= widthPx) onCollapsed(true)
                dockJob?.cancel()
                dockJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    // Seed from the released position before switching away from direct finger tracking.
                    docking.snapTo(0f)
                    dragging = false
                    docking.animateTo(1f, tween(240))
                }
            }
            val currentPosition by rememberUpdatedState(Offset(x, y))
            val currentWidth by rememberUpdatedState(playerWidth)
            val currentSettle by rememberUpdatedState(settle)
            val collapseLabel = stringResource(R.string.collapse)
            Box(
                modifier = Modifier
                    .absoluteOffset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(width, 50.dp)
                    .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                    .testTag("read-aloud-floating-player")
                    .semantics {
                        if (!collapsed) {
                            customActions = listOf(
                                CustomAccessibilityAction(collapseLabel) {
                                    onCollapsed(true)
                                    true
                                },
                            )
                        }
                    }
                    .pointerInput(widthPx, heightPx) {
                        detectDragGestures(
                            onDragStart = {
                                dockJob?.cancel()
                                dragX = currentPosition.x
                                dragY = currentPosition.y
                                dragging = true
                            },
                            onDragEnd = { currentSettle() },
                            onDragCancel = { currentSettle() },
                        ) { change, delta ->
                            change.consume()
                            dragX = (dragX + delta.x).coerceIn(0f, (widthPx - currentWidth).coerceAtLeast(0f))
                            dragY = (dragY + delta.y).coerceIn(margin, maxY)
                        }
                    },
                contentAlignment = if (dockLeft) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (expansion < 1f) {
                    Surface(
                        onClick = { onCollapsed(false) },
                        enabled = collapsed,
                        modifier = Modifier
                            .size(40.dp, 50.dp)
                            .zIndex(1f)
                            .graphicsLayer { alpha = 1f - expansion }
                            .testTag("read-aloud-expand"),
                        color = Color.Transparent,
                    ) {
                        Box(contentAlignment = if (dockLeft) Alignment.CenterStart else Alignment.CenterEnd) {
                            LiquidGlassSurface(
                                modifier = Modifier.size(22.dp, 42.dp),
                                shape = RoundedCornerShape(
                                    topStart = if (dockLeft) 0.dp else 12.dp,
                                    bottomStart = if (dockLeft) 0.dp else 12.dp,
                                    topEnd = if (dockLeft) 12.dp else 0.dp,
                                    bottomEnd = if (dockLeft) 12.dp else 0.dp,
                                ),
                                variant = GlassVariant.Frosted,
                                hazeState = null,
                                elevation = 6.dp,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.arrow_forward_ios_24px),
                                        contentDescription = stringResource(R.string.tts_expand_player),
                                        tint = colors.primary,
                                        modifier = Modifier
                                            .size(13.dp)
                                            .graphicsLayer { rotationZ = if (dockLeft) 0f else 180f },
                                    )
                                }
                            }
                        }
                    }
                }
                if (expansion > 0f) {
                    LiquidGlassSurface(
                        modifier = Modifier
                            .wrapContentSize(
                                align = if (dockLeft) Alignment.CenterStart else Alignment.CenterEnd,
                                unbounded = true,
                            )
                            .requiredSize(136.dp, 50.dp)
                            .graphicsLayer { alpha = expansion },
                        shape = CircleShape,
                        variant = GlassVariant.Frosted,
                        hazeState = null,
                        elevation = 10.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 5.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val openLabel = stringResource(R.string.tts_return_to_book, state.bookTitle)
                            IconButton(
                                onClick = onOpenBook,
                                enabled = !collapsed && expansion == 1f,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("read-aloud-cover"),
                            ) {
                                Box(
                                    Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, tokens.activePillBorder, CircleShape)
                                        .clearAndSetSemantics { contentDescription = openLabel }
                                        .graphicsLayer { rotationZ = rotation },
                                ) {
                                    cover()
                                }
                            }
                            IconButton(
                                onClick = {
                                    onCommand(if (state.isActive) SpeechAction.Pause else SpeechAction.Resume)
                                },
                                enabled = !collapsed && expansion == 1f,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(tokens.activePillFill)
                                        .border(1.dp, tokens.activePillBorder, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (state.isActive) R.drawable.pause_24px else R.drawable.play_arrow_24px,
                                        ),
                                        contentDescription = stringResource(
                                            if (state.isActive) R.string.tts_pause else R.string.tts_resume,
                                        ),
                                        tint = colors.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onCommand(SpeechAction.Stop) },
                                enabled = !collapsed && expansion == 1f,
                                modifier = Modifier.size(36.dp, 40.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close_24px),
                                    contentDescription = stringResource(R.string.tts_close_player),
                                    modifier = Modifier.size(17.dp),
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
