package dev.southstar21.vectra.features.weather

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.CardGrowOverlay
import dev.southstar21.vectra.navigation.AppNavigator

/**
 * The radar over everything ([AppNavigator.radar]): it grows out of the weather card it was opened
 * from (W2) into the full-screen map, and shrinks back into it on close or back.
 */
@Composable
fun WeatherRadarHost(repo: WeatherRepository, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val close = { navigator.radar = null }
    val density = LocalDensity.current
    CardGrowOverlay(navigator.radar, close, modifier) { g ->
        // The orb starts where the card's art was.
        val orbFrom = g.origin?.let { with(density) { Offset(it.right - 44.dp.toPx(), it.top + 36.dp.toPx()) } }
        RadarView(repo, grow = g.grow, opened = g.opened, orbFrom = orbFrom, onClose = close)
    }
}
