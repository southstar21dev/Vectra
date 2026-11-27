package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * The frame clock a loading cover shares with its animation (the map's orb cover, the radar's
 * sweep): the latest frame time plus the frame a timed stretch (the melt, the sweep) began at.
 * Frames are read in draw only, so ticking never recomposes.
 */
@Stable
internal class CoverClock(private val engine: OrbEngine) {
    private var frame by mutableLongStateOf(0L)
    private var start by mutableLongStateOf(0L)

    fun tick(nanos: Long) {
        frame = nanos
    }

    /** Marks [nanos] as the start of the timed stretch; false when it had already started. */
    fun begin(nanos: Long): Boolean {
        if (start != 0L) return false
        start = nanos
        return true
    }

    /** Forgets the start, so the next [begin] starts a new stretch. */
    fun restart() {
        start = 0L
    }

    val started: Boolean get() = start != 0L

    /** Nanoseconds since [begin] (0 before it). */
    fun elapsedNanos(): Long = if (start == 0L) 0L else frame - start

    /** The orb's clock: a still frame at 0.6 until frames run, or always with reduced motion. */
    fun orbTime(reduced: Boolean): Double = if (reduced || frame == 0L) 0.6 else frame / 1e9 * engine.speed
}
