package dev.southstar21.vectra.core.maps

import dev.southstar21.vectra.core.designsystem.theme.Palette

/** Fixed colours drawn on maps. They are map data, so they don't follow the theme or the accent. */
object MapColors {
    /** You and the selected stop. */
    val Location = Palette.Blue500

    /** A route whose own colour is missing or unreadable. */
    val RouteFallback = Palette.Blue500

    val StopFill = Palette.Zinc600
    val StopStroke = Palette.Zinc500
}
