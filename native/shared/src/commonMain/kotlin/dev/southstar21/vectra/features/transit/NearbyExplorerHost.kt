package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.CardGrowOverlay
import dev.southstar21.vectra.core.maps.composeOverlaysTrackMap
import dev.southstar21.vectra.navigation.AppNavigator

/**
 * The nearby map over everything ([AppNavigator.nearbyMap]): it grows out of the Transit tab's map
 * card into the explorer, and shrinks back into it on close or back.
 */
@Composable
fun NearbyExplorerHost(repo: TransitRepository, deviceLocation: DeviceLocation, auth: AuthRepository, navigator: AppNavigator, modifier: Modifier = Modifier) {
    val close = { navigator.nearbyMap = null }
    CardGrowOverlay(navigator.nearbyMap, close, modifier) { g ->
        // A native map (iOS) can't be clipped to the shrinking card: it leaves as the shrink starts,
        // or its own view would flash over the card's map on the way down.
        NearbyExplorerScreen(repo, deviceLocation, auth, onBack = close, opened = g.opened, showMap = !(g.closing && !composeOverlaysTrackMap))
    }
}
