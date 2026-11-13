package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.components.LocalPageActive
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.color
import dev.southstar21.vectra.core.maps.cumulativeHaversineDistances
import dev.southstar21.vectra.core.maps.isValidLatLon
import dev.southstar21.vectra.core.maps.pointAtDistance
import dev.southstar21.vectra.core.maps.position
import dev.southstar21.vectra.core.maps.samePoints
import dev.southstar21.vectra.core.maps.snapToPolyline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.VectraEasing
import dev.southstar21.vectra.core.maps.VehicleMarker
import dev.southstar21.vectra.core.maps.VehicleVariant
import org.maplibre.compose.overlay.MapOverlayScope
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/*
 * Animated vehicle markers. The pure maths
 * (transitionFor, resolveBearing, easeInOutQuad, AnimatedVehicleInput) lives
 * in VehicleAnimMath.kt; this file is the per-frame driver and the markers.
 *
 * Usage inside `TransitMap(overlay = { … })`:
 * ```
 * val frames = rememberVehicleAnimations(animVehicles)
 * AnimatedVehicleMarkers(frames, mapBearing = { mapState.cameraPosition.bearing })
 * ```
 */

/** One vehicle's rendered state for the current frame. */
@Immutable
data class VehicleFrame(
    val id: String,
    /** Where the marker sits now (snapped onto / gliding along the polyline). */
    val position: LatLon,
    /** Heading in degrees (0 = north), rounded to a whole degree. */
    val bearing: Double,
    val variant: VehicleVariant,
)

private class VehicleAnimState(
    var polyline: List<LatLon>,
    var dists: List<Double>,
    var currentDist: Double,
    var fromDist: Double,
    var toDist: Double,
    var startMs: Double,
    var durationMs: Double,
    var done: Boolean,
    var variant: VehicleVariant,
    var position: LatLon,
    var lastBearing: Double?,
    var fallbackBearing: Double?,
) {
    fun apply(t: VehicleTransition) {
        fromDist = t.fromDist
        toDist = t.toDist
        startMs = t.startMs
        durationMs = t.durationMs
        done = t.done
    }
}

/**
 * The animation driver behind [rememberVehicleAnimations]: keeps one state per
 * vehicle id, retargets on every [sync] and advances on [step].
 */
class VehicleAnimator internal constructor() {
    private val clock = TimeSource.Monotonic.markNow()
    private val states = LinkedHashMap<String, VehicleAnimState>()

    /** Monotonic milliseconds. */
    internal fun now(): Double = clock.elapsedNow().inWholeMicroseconds / 1000.0

    /** The data effect: snap new vehicles, retarget known ones, drop the gone. */
    internal fun sync(vehicles: List<AnimatedVehicleInput>) {
        val now = now()
        val seen = HashSet<String>()
        for (v in vehicles) {
            if (!isValidLatLon(v.location) || v.polyline.size < 2) continue
            seen += v.id
            val snapped = snapToPolyline(v.location, v.polyline, v.bearing)
            val targetDist = snapped?.distance ?: 0.0
            val targetPt = snapped?.point ?: v.location
            val variant = v.variant ?: VehicleVariant.Primary
            var existing = states[v.id]
            // The marker is styled per variant; rebuild when a vehicle switches.
            if (existing != null && existing.variant != variant) {
                states.remove(v.id)
                existing = null
            }
            if (existing != null && samePoints(existing.polyline, v.polyline)) {
                val next = transitionFor(existing.currentDist, targetDist, now)
                existing.apply(next)
                if (next.done) {
                    existing.currentDist = next.toDist
                    existing.position = pointAtDistance(existing.polyline, existing.dists, existing.currentDist)
                }
                existing.fallbackBearing = v.bearing
                updateBearing(existing)
            } else if (existing != null) {
                val dists = cumulativeHaversineDistances(v.polyline)
                existing.polyline = v.polyline
                existing.dists = dists
                existing.currentDist = targetDist
                existing.apply(transitionFor(null, targetDist, now))
                existing.fallbackBearing = v.bearing
                existing.position = targetPt
                existing.lastBearing = resolveBearing(v.polyline, dists, targetDist, v.bearing)
            } else {
                val dists = cumulativeHaversineDistances(v.polyline)
                val t = transitionFor(null, targetDist, now)
                states[v.id] = VehicleAnimState(
                    polyline = v.polyline,
                    dists = dists,
                    currentDist = targetDist,
                    fromDist = t.fromDist,
                    toDist = t.toDist,
                    startMs = t.startMs,
                    durationMs = t.durationMs,
                    done = t.done,
                    variant = variant,
                    position = targetPt,
                    lastBearing = resolveBearing(v.polyline, dists, targetDist, v.bearing),
                    fallbackBearing = v.bearing,
                )
            }
        }
        states.keys.retainAll(seen)
    }

    private fun updateBearing(st: VehicleAnimState) {
        val bearing = resolveBearing(st.polyline, st.dists, st.currentDist, st.fallbackBearing)
        val last = st.lastBearing
        if (last == null || abs(bearing - last) >= 0.5) st.lastBearing = bearing
    }

    /** One animation frame; returns true while any vehicle is still gliding. */
    internal fun step(now: Double = now()): Boolean {
        var active = false
        for (st in states.values) {
            if (!st.done) {
                active = true
                val t = min(1.0, (now - st.startMs) / (st.durationMs.takeIf { it != 0.0 } ?: 1.0))
                st.currentDist = if (t >= 1) st.toDist else st.fromDist + (st.toDist - st.fromDist) * easeInOutQuad(t)
                if (t >= 1) st.done = true
                st.position = pointAtDistance(st.polyline, st.dists, st.currentDist)
                updateBearing(st)
            }
        }
        return active
    }

    internal fun frames(): List<VehicleFrame> = states.map { (id, st) ->
        VehicleFrame(id, st.position, (st.lastBearing ?: 0.0).roundToInt().toDouble(), st.variant)
    }
}

/** A glide moves well under a pixel per display frame, so 20 updates a second look the same. */
private const val GlideFrameMs = 50L

/**
 * Vehicles glide along their polylines (easeInOutQuad
 * over transitionFor's 0.6–10s at ~10 m/s) and point along the heading of
 * travel (resolveBearing). Returns the per-frame marker states; recomposes
 * every frame only while something is moving.
 */
@Composable
fun rememberVehicleAnimations(vehicles: List<AnimatedVehicleInput>): List<VehicleFrame> {
    val animator = remember { VehicleAnimator() }
    var frames by remember { mutableStateOf<List<VehicleFrame>>(emptyList()) }
    val active = LocalPageActive.current
    val reduceMotion = Vectra.reduceMotion
    LaunchedEffect(vehicles, active, reduceMotion) {
        animator.sync(vehicles)
        if (reduceMotion) animator.step(Double.MAX_VALUE)
        frames = animator.frames()
        if (!active || reduceMotion) return@LaunchedEffect
        while (true) {
            val gliding = withFrameNanos { animator.step() }
            frames = animator.frames()
            if (!gliding) break
            delay(GlideFrameMs)
        }
    }
    return frames
}

/**
 * The teardrop marker at [position], pointing along [bearing] relative to the
 * map's own bearing. Heading changes ease out over 300ms, along the shortest turn.
 */
@Composable
fun MapOverlayScope.PlacedVehicleMarker(
    position: LatLon,
    color: Color,
    bearing: Double,
    /** A lambda so panning, which moves the camera state, doesn't recompose the overlay. */
    mapBearing: () -> Double,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val rotation = rememberMarkerRotation(bearing, mapBearing)
    VehicleMarker(
        color = color,
        bearing = 0f,
        modifier = modifier
            .placedAt(position.position, Alignment.Center)
            .graphicsLayer { rotationZ = rotation.value },
        size = size,
    )
}

/**
 * A marker's on-screen heading: [bearing] less the map's own, easing over 300ms along the
 * shortest turn when either changes.
 */
@Composable
internal fun rememberMarkerRotation(bearing: Double, mapBearing: () -> Double): Animatable<Float, AnimationVector1D> {
    val rotation = remember { Animatable((bearing - mapBearing()).toFloat()) }
    val bearingNow by rememberUpdatedState(bearing)
    val mapBearingNow by rememberUpdatedState(mapBearing)
    LaunchedEffect(rotation) {
        snapshotFlow { (bearingNow - mapBearingNow()).toFloat() }.collectLatest { target ->
            val current = rotation.value
            // Unwrap so 359° → 1° turns 2°, not 358°.
            val delta = ((target - current) % 360f + 540f) % 360f - 180f
            rotation.animateTo(current + delta, tween(300, easing = VectraEasing.Fade))
        }
    }
    return rotation
}

/** All animated vehicles as markers, coloured by their meaning variant (or [colorFor]). */
@Composable
fun MapOverlayScope.AnimatedVehicleMarkers(
    frames: List<VehicleFrame>,
    mapBearing: () -> Double,
    colorFor: ((VehicleFrame) -> Color)? = null,
) {
    for (f in frames) {
        key(f.id) {
            PlacedVehicleMarker(
                position = f.position,
                color = colorFor?.invoke(f) ?: f.variant.color(),
                bearing = f.bearing,
                mapBearing = mapBearing,
            )
        }
    }
}
