package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The app's haptic vocabulary. Components call these by meaning so the feel stays
 * consistent: a selection always ticks, a commit always confirms.
 */
@Immutable
class VectraHaptics(private val feedback: HapticFeedback?) {
    /** A discrete value changed: tab, segment, picker step, sheet detent. */
    fun tick() = perform(HapticFeedbackType.SegmentTick)
    /** Continuous scrubbing crossed a detent (navbar drag, sliders). */
    fun frequentTick() = perform(HapticFeedbackType.SegmentFrequentTick)
    fun toggle(on: Boolean) = perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    /** A gesture passed the point where releasing commits it (pull-to-refresh, swipe action). */
    fun threshold() = perform(HapticFeedbackType.GestureThresholdActivate)
    /** An action succeeded or was committed. */
    fun confirm() = perform(HapticFeedbackType.Confirm)
    /** An action was refused or a destructive step was armed. */
    fun reject() = perform(HapticFeedbackType.Reject)
    fun longPress() = perform(HapticFeedbackType.LongPress)
    /** A secondary menu opened from a tap. */
    fun contextClick() = perform(HapticFeedbackType.ContextClick)

    private fun perform(type: HapticFeedbackType) {
        feedback?.performHapticFeedback(type)
    }
}

val LocalVectraHaptics = staticCompositionLocalOf { VectraHaptics(null) }
