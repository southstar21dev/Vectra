package dev.southstar21.vectra.features.transit

import androidx.compose.ui.graphics.Color
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.maps.MapColors

/* Map colours; themed values come from VectraColors. */

fun withHash(color: String): String = if (color.startsWith("#")) color else "#$color"

data class LiveLineColors(
    val casing: Color,
    val casingSecondary: Color,
    val baseGray: Color,
)

fun liveLineColors(colors: VectraColors): LiveLineColors = LiveLineColors(
    casing = colors.mapLineCasing,
    casingSecondary = colors.mapLineCasingSecondary,
    baseGray = if (!colors.isDark) Color(0xFF9CA3AF) else Palette.Zinc600,
)

data class ExplorerRouteColors(
    val isLight: Boolean,
    val casing: Color,
    val casingWidth: Double,
    val halo: Color,
    val haloWidth: Double,
    val lineWidth: Double,
    val lineBlur: Double,
    val stopFill: Color,
    val stopStroke: Color,
)

fun explorerRouteColors(isLight: Boolean): ExplorerRouteColors = ExplorerRouteColors(
    isLight = isLight,
    casing = if (isLight) Color(0f, 0f, 0f, 0.5f) else Color(1f, 1f, 1f, 0.24f),
    casingWidth = if (isLight) 12.0 else 13.0,
    halo = Color(0xFFFFFFFF),
    haloWidth = if (isLight) 8.0 else 9.5,
    lineWidth = if (isLight) 5.5 else 6.0,
    lineBlur = if (isLight) 1.5 else 2.0,
    // These match RouteMap's regular stop dots exactly.
    stopFill = MapColors.StopFill,
    stopStroke = MapColors.StopStroke,
)
