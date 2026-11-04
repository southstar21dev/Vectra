package dev.southstar21.vectra.core.maps

import org.maplibre.compose.map.MapUiOptions

/**
 * Draw the map as part of the page rather than on its own surface. A separate
 * surface is positioned wherever its placeholder was last drawn, so the navbar
 * lens (which redraws the page shifted, to refract it) dragged maps along, and
 * stretch overscroll or fades could not reach it.
 */
expect fun MapUiOptions.Builder.drawInPage()
