package dev.southstar21.vectra.core.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import kotlin.time.Duration.Companion.milliseconds
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Palette

/* Camera helpers (fits, jumps, the programmatic-move guard) and the map control column. */

/**
 * Distinguishes user camera moves from programmatic
 * ones. Run programmatic moves through [programmaticMove]; [userMoved] only
 * turns true when the user drags/zooms/rotates outside such a call. Wire
 * [handleUserMove] to `TransitMap(onUserMove = …)`.
 *
 * The flag clears when the move completes, with a 1.2s fallback for moves
 * that never settle — safe for animated fits.
 */
class ProgrammaticGuard internal constructor(private val scope: CoroutineScope) {
    internal var onUserMove: (() -> Unit)? = null
    private var programmatic = false
    private var job: Job? = null
    private var fallback: Job? = null

    /** `userMovedRef.current`: a plain flag (reading it does not recompose). */
    var userMoved: Boolean = false

    fun resetUserMoved() {
        userMoved = false
    }

    /** The map reported a gesture-driven camera start. */
    fun handleUserMove() {
        if (!programmatic) {
            userMoved = true
            onUserMove?.invoke()
        }
    }

    /** Run [block] as a programmatic camera move; supersedes any move still running. */
    fun programmaticMove(block: suspend () -> Unit): Job {
        programmatic = true
        job?.cancel()
        fallback?.cancel()
        fallback = scope.launch {
            delay(1200)
            programmatic = false
        }
        val j = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The map may detach mid-move (dialog closing); nothing to recover.
            }
        }
        j.invokeOnCompletion {
            if (job === j) {
                programmatic = false
                fallback?.cancel()
            }
        }
        job = j
        return j
    }
}

@Composable
fun rememberProgrammaticGuard(onUserMove: (() -> Unit)? = null): ProgrammaticGuard {
    val scope = rememberCoroutineScope()
    val guard = remember(scope) { ProgrammaticGuard(scope) }
    val latest = rememberUpdatedState(onUserMove)
    guard.onUserMove = { latest.value?.invoke() }
    return guard
}

/** maplibre-gl's default easeTo/fitBounds animation (500ms, `ease`). */
private val DefaultEase = CameraAnimation.Ease { duration = 500.milliseconds }

private fun Bounds.box() = BoundingBox(west = minLon, south = minLat, east = maxLon, north = maxLat)

/**
 * `map.fitBounds(bounds, { padding, maxZoom, animate })`. A degenerate box
 * (one point) centres on it at [maxZoom] (or the current zoom).
 *
 * [bottomInset]: the map's bottom part is out of sight (a map taller than the box showing it), so
 * the fit keeps clear of it. Null leaves the camera's own padding as it is.
 */
suspend fun MapState.fitBounds(bounds: Bounds, padding: Dp, maxZoom: Double? = null, animate: Boolean = false, bottomInset: Dp? = null) {
    val degenerate = bounds.minLat == bounds.maxLat && bounds.minLon == bounds.maxLon
    // A point is centred in the visible part through the camera's padding; a box through the fit's.
    val camPadding = when {
        bottomInset == null -> cameraPosition.padding
        degenerate -> DpPadding(bottom = bottomInset)
        else -> DpPadding()
    }
    val target: CameraPosition = if (degenerate) {
        cameraPosition.copy(
            center = LatLon(bounds.minLat, bounds.minLon).position,
            zoom = maxZoom ?: cameraPosition.zoom,
        )
    } else {
        val fit = DpPadding(padding, padding, padding, padding + (bottomInset ?: 0.dp))
        val cam = cameraForBounds(bounds.box(), CameraFit(fitPadding = fit))
        if (maxZoom != null && cam.zoom > maxZoom) cam.copy(zoom = maxZoom) else cam
    }
    if (animate) {
        animateCamera(CameraUpdate(center = target.center, zoom = target.zoom, bearing = target.bearing, padding = camPadding), DefaultEase)
    } else {
        setCameraPosition(target.copy(padding = camPadding))
    }
}

/** `map.easeTo({ center, zoom })`: [jumpTo], gliding there. */
suspend fun MapState.easeTo(center: LatLon, zoom: Double, bottomInset: Dp? = null) {
    val padding = if (bottomInset == null) cameraPosition.padding else DpPadding(bottom = bottomInset)
    animateCamera(CameraUpdate(center = center.position, zoom = zoom, padding = padding), DefaultEase)
}

/** `map.jumpTo({ center, zoom })`; [bottomInset] as in [fitBounds]. */
fun MapState.jumpTo(center: LatLon, zoom: Double, bottomInset: Dp? = null) {
    val padding = if (bottomInset == null) cameraPosition.padding else DpPadding(bottom = bottomInset)
    setCameraPosition(cameraPosition.copy(center = center.position, zoom = zoom, padding = padding))
}

/** `map.getBounds().contains(p)` against the last rendered viewport (false before one exists). */
fun MapState.boundsContain(p: LatLon): Boolean {
    val b = viewport?.visibleBounds ?: return false
    return p.lon >= b.southwest.longitude && p.lon <= b.northeast.longitude &&
        p.lat >= b.southwest.latitude && p.lat <= b.northeast.latitude
}

/** One round button in [MapControls]. */
data class MapControlButton(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val active: Boolean = false,
)

/** The shared top-right control column (32dp round buttons, gap 6dp). */
@Composable
fun MapControls(buttons: List<MapControlButton>, modifier: Modifier = Modifier) {
    val dark = Vectra.colors.isDark
    Column(modifier.padding(top = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in buttons) {
            val bg = when {
                b.active -> if (dark) Palette.Blue950 else Palette.Blue100
                else -> if (dark) Palette.Zinc800 else Color.White
            }
            val border = when {
                b.active -> Palette.Blue500
                else -> if (dark) Palette.Zinc700 else Palette.Zinc200
            }
            val ink = when {
                b.active -> if (dark) Palette.Blue400 else Palette.Blue600
                else -> if (dark) Palette.Zinc200 else Palette.Zinc700
            }
            Box(
                Modifier
                    .size(32.dp)
                    // Medium drop shadow.
                    .boxShadow(
                        CircleShape,
                        listOf(
                            BoxShadow(y = 4f, blur = 6f, spread = -1f, color = Color.Black.copy(alpha = 0.1f)),
                            BoxShadow(y = 2f, blur = 4f, spread = -2f, color = Color.Black.copy(alpha = 0.1f)),
                        ),
                    )
                    .background(bg, CircleShape)
                    .border(1.dp, border, CircleShape)
                    .tappable(CircleShape, role = Role.Button, onClick = b.onClick)
                    .semantics { contentDescription = b.title },
                contentAlignment = Alignment.Center,
            ) { VIcon(b.icon, tint = ink) }
        }
    }
}
