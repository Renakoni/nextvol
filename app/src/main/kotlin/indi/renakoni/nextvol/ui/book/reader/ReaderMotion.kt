package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf

internal val LocalReduceReaderMotion = compositionLocalOf { false }

internal val ReaderSettings.animatePageTurns: Boolean
    get() = !reduceMotion && flipAnime != ReaderFlipAnimation.None

/** Scope component motion to the reader, retaining the same composition and saved preferences. */
@Composable
internal fun ReaderMotionTheme(reduced: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(motionScheme = if (reduced) StaticReaderMotion else MaterialTheme.motionScheme) {
        CompositionLocalProvider(LocalReduceReaderMotion provides reduced, content = content)
    }
}

private object StaticReaderMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

internal fun readerContentTransform(reduced: Boolean) = if (reduced) {
    ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = SizeTransform { _, _ -> snap() })
} else {
    // AnimatedContent's normal fade/scale transition.
    (fadeIn(tween(220, delayMillis = 90)) + scaleIn(tween(220, delayMillis = 90), initialScale = 0.92f))
        .togetherWith(fadeOut(tween(90)))
}
