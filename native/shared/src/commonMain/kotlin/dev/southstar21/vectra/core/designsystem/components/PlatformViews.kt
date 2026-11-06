package dev.southstar21.vectra.core.designsystem.components

/**
 * True where embedded native views (maps, the iOS segmented control) sit *under* the Compose
 * canvas and show through a hole Compose clears for them (iOS). An offscreen layer around such a
 * view only clears its own buffer, which is then drawn over whatever is opaque below, so the view
 * vanishes: there, effects avoid `CompositingStrategy.Offscreen` around content that may hold one.
 */
expect val nativeViewsUnderCanvas: Boolean
