package dev.southstar21.vectra.features.transit.live

/** Longest direction name that still fits a two-up tab; a longer one gets a stacked option group instead. */
private const val TwoUpTabMaxChars = 11

/** Whether the directions' names are too long for tabs side by side. */
internal fun directionTabsStacked(labels: List<String>): Boolean = labels.any { it.length > TwoUpTabMaxChars }
