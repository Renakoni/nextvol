package indi.renakoni.nextvol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsSliderStepTest {

    @Test
    fun resolveDiscreteStepIndexMatchesExactClampsOutOfRangeAndSnapsNearest() {
        val steps = listOf(10f, 14f, 18f, 24f)

        assertEquals(0, resolveDiscreteStepIndex(emptyList(), 15f))
        assertEquals(2, resolveDiscreteStepIndex(steps, 18f))
        assertEquals(0, resolveDiscreteStepIndex(steps, 4f))
        assertEquals(3, resolveDiscreteStepIndex(steps, 40f))
        assertEquals(1, resolveDiscreteStepIndex(steps, 15.5f))
        assertEquals(2, resolveDiscreteStepIndex(steps, 17f))
    }

    @Test
    fun sliderCommitStateCommitsAccessibilityImmediatelyAndDefersPointerDrags() {
        val state = SliderCommitState()

        // Accessibility setProgress changes without pointer interaction must persist immediately.
        assertEquals(18f, state.onValueChanged(18f))
        assertNull(state.onValueChangeFinished())

        // Pointer drag defers writes until finish without double committing on release.
        state.onPointerDown()
        assertNull(state.onValueChanged(20f))
        assertNull(state.onValueChanged(22f))
        assertEquals(22f, state.onValueChangeFinished())
        assertNull(state.onPointerReleased())

        // Cancelled drag without onValueChangeFinished flushes pending value on release.
        state.onPointerDown()
        assertNull(state.onValueChanged(24f))
        assertEquals(24f, state.onPointerReleased())
        assertNull(state.onValueChangeFinished())
    }
}
