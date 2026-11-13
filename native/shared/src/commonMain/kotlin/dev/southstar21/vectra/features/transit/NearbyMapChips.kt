package dev.southstar21.vectra.features.transit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.maps.LatLon
import dev.southstar21.vectra.core.maps.position
import kotlinx.coroutines.flow.filterIsInstance
import org.maplibre.compose.map.MapEvent
import org.maplibre.compose.camera.CameraAnimation
import org.maplibre.compose.camera.CameraUpdate
import androidx.compose.ui.unit.DpOffset
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope

/** Where the map's chrome sits, so chips keep clear of it: the island band at the top, the control column, the sheet. */
internal class ChipChrome(val top: Dp, val controlsBottom: Dp, val bottom: Dp)

/** The areas (dp) of a [w]×[h] map the chips must leave clear. */
private fun chromeRects(chrome: ChipChrome, w: Float, h: Float): List<Rect> = listOf(
    Rect(0f, 0f, w, chrome.top.value),
    Rect(w - 68f, 0f, w, chrome.controlsBottom.value),
    Rect(0f, h - chrome.bottom.value, w, h),
)

/**
 * The chips to show for the map's current view: worked out again whenever the camera settles or
 * the chips or chrome change, so nothing pops in or out mid-gesture.
 */
@Composable
internal fun rememberThinnedChips(map: MapState?, loaded: Boolean, chips: List<NearbyChip>, chrome: ChipChrome): Set<String> {
    var shown by remember { mutableStateOf(emptySet<String>()) }
    val latestChips by rememberUpdatedState(chips)
    val latestChrome by rememberUpdatedState(chrome)
    fun compute(m: MapState) {
        val size = m.viewport?.size ?: return
        val w = size.width.value
        val h = size.height.value
        if (w <= 0f || h <= 0f) return
        shown = thinChips(
            latestChips,
            project = { c -> m.screenLocationFromPosition(LatLon(c.lat, c.lon).position)?.let { Offset(it.x.value, it.y.value) } },
            w = ChipWidth.value,
            h = ChipHeight.value,
            gap = ChipGap.value,
            bounds = Rect(0f, 0f, w, h),
            blocked = chromeRects(latestChrome, w, h),
        )
    }
    LaunchedEffect(map, loaded, chips, chrome.top, chrome.bottom) {
        val m = map ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        runCatching { compute(m) }
        m.events.filterIsInstance<MapEvent.CameraMoveEnded>().collect { runCatching { compute(m) } }
    }
    return shown
}

/**
 * The chips over their stops, each hanging just above its dot. The picked stop's chip steps aside
 * while its board is up (pinned under the island, see ExplorerChrome). [dimmed] fades the chips
 * while a line is being followed.
 */
@Composable
internal fun MapOverlayScope.NearbyChipOverlay(
    chips: List<NearbyChip>,
    shown: Set<String>,
    focusedId: String?,
    dimmed: Boolean,
    onTap: (NearbyChip) -> Unit,
) {
    for (chip in chips) {
        if (chip.stopId !in shown || chip.stopId == focusedId) continue
        key(chip.stopId) {
            Box(Modifier.placedAt(LatLon(chip.lat, chip.lon).position, Alignment.BottomCenter)) {
                PoppingChip(chip, dimmed) { onTap(chip) }
            }
        }
    }
}

/** A chip that springs up out of its stop the first time it shows. */
@Composable
private fun PoppingChip(chip: NearbyChip, dimmed: Boolean, onClick: () -> Unit) {
    val reduced = Vectra.reduceMotion
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
    DepartureChip(
        chip,
        onClick,
        Modifier
            .offset(y = -ChipGap)
            .graphicsLayer {
                val p = pop.value
                scaleX = 0.5f + 0.5f * p
                scaleY = 0.5f + 0.5f * p
                alpha = p.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
            },
        dimmed = dimmed,
    )
}

/** Room the board needs above its stop (its height and the gap under the island). */
private val BoardRoom = 210.dp

/**
 * When a stop is picked, glides the map so its board has room: the stop moves to just under where
 * the board's top clears the island, centred across, and never under the sheet.
 */
@Composable
internal fun PanToBoard(map: MapState?, loaded: Boolean, chips: List<NearbyChip>, focusedId: String?, top: Dp, bottomInset: Dp) {
    val latestChips by rememberUpdatedState(chips)
    LaunchedEffect(map, loaded, focusedId) {
        val m = map ?: return@LaunchedEffect
        val id = focusedId ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val chip = latestChips.firstOrNull { it.stopId == id } ?: return@LaunchedEffect
        runCatching {
            val size = m.viewport?.size ?: return@runCatching
            val at = m.screenLocationFromPosition(LatLon(chip.lat, chip.lon).position) ?: return@runCatching
            val w = size.width.value
            val h = size.height.value
            val wantY = (top + BoardRoom).value.coerceAtMost(h - bottomInset.value - 48f)
            val dx = at.x.value - w / 2
            val dy = at.y.value - wantY
            // Already where it should be: leave the map alone.
            if (abs(dx) < 24f && abs(dy) < 24f) return@runCatching
            val center = m.positionFromScreenLocation(DpOffset((w / 2 + dx).dp, (h / 2 + dy).dp)) ?: return@runCatching
            m.animateCamera(
                CameraUpdate(center = center),
                CameraAnimation.Ease { duration = 450.milliseconds },
            )
        }
    }
}
