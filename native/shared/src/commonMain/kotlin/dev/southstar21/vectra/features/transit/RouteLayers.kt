package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import org.maplibre.compose.expressions.ast.Expression
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.value.ColorValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.VectorSource
import org.maplibre.compose.util.MaplibreComposable

/* Layer stacks the route maps share. Declaration order is draw order. */

/** One look of a line: colour, width and opacity. */
internal class LineStroke(val color: Expression<ColorValue>, val width: Dp, val opacity: Float)

/** A round-capped line over its [casing]. */
@Composable
@MaplibreComposable
internal fun RouteLineLayers(
    casingId: String,
    lineId: String,
    source: VectorSource,
    casing: LineStroke,
    line: LineStroke,
) {
    RouteLine(casingId, source, casing)
    RouteLine(lineId, source, line)
}

@Composable
@MaplibreComposable
private fun RouteLine(id: String, source: VectorSource, stroke: LineStroke) {
    LineLayer(
        id = id,
        source = source,
        cap = const(LineCap.Round),
        join = const(LineJoin.Round),
        color = stroke.color,
        width = const(stroke.width),
        opacity = const(stroke.opacity),
    )
}

/** Stop dots styled per feature by `radius`, `fillColor`, `strokeColor` and `strokeWidth` properties. */
@Composable
@MaplibreComposable
internal fun StopDotsLayer(
    id: String,
    source: VectorSource,
    hitPadding: Dp,
    opacity: Expression<FloatValue> = const(1f),
    onClick: FeaturesClickHandler? = null,
) {
    CircleLayer(
        id = id,
        source = source,
        radius = feature["radius"].asNumber().dp,
        color = feature["fillColor"].convertToColor(),
        opacity = opacity,
        strokeColor = feature["strokeColor"].convertToColor(),
        strokeWidth = feature["strokeWidth"].asNumber().dp,
        hitPadding = hitPadding,
        onClick = onClick,
    )
}
