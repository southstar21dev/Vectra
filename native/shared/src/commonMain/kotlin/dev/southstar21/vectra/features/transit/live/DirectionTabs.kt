package dev.southstar21.vectra.features.transit.live

/**
 * Whether the directions' names are too wide for tabs side by side: any one that does not fit in
 * [maxWidth] (as measured by [widthOf]) on a single line. Most stop names fit, so tabs stay two-up.
 */
internal fun directionTabsStacked(labels: List<String>, maxWidth: Float, widthOf: (String) -> Float): Boolean =
    labels.any { widthOf(it) > maxWidth }
