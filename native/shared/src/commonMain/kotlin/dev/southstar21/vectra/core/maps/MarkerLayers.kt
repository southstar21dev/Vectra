package dev.southstar21.vectra.core.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.features.transit.VehicleFrame
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.dp
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/*
 * Map markers drawn by the map itself, so they stay pinned while it pans (see
 * [composeOverlaysTrackMap]). Same looks as the Compose overlay markers, minus their animations.
 */

private fun pointJson(lat: Double, lon: Double): String = GeoJson.collection(listOf(GeoJson.point(lat, lon)))

/** The blue "you are here" dot: a soft halo and a white-ringed core. */
@Composable
@MaplibreComposable
fun UserLocationLayers(idPrefix: String, location: LatLon) {
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(pointJson(location.lat, location.lon)))
    CircleLayer(id = "$idPrefix-user-halo", source = source, radius = const(10.dp), color = const(Palette.Blue400), opacity = const(0.3f))
    CircleLayer(
        id = "$idPrefix-user-dot", source = source,
        radius = const(7.dp), color = const(MapColors.Location),
        strokeColor = const(Color.White), strokeWidth = const(2.dp),
    )
}

/** The selected stop: a blue beacon with its name above. */
@Composable
@MaplibreComposable
fun SelectedStopLayers(idPrefix: String, location: LatLon, name: String, isLight: Boolean) {
    val json = remember(location, name) {
        GeoJson.collection(listOf(GeoJson.point(location.lat, location.lon, buildJsonObject { put("name", JsonPrimitive(name)) })))
    }
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    CircleLayer(id = "$idPrefix-selected-halo", source = source, radius = const(12.dp), color = const(MapColors.Location), opacity = const(0.3f))
    CircleLayer(
        id = "$idPrefix-selected-dot", source = source,
        radius = const(8.dp), color = const(MapColors.Location),
        strokeColor = const(Color.White), strokeWidth = const(2.5.dp),
    )
    SymbolLayer(
        id = "$idPrefix-selected-label", source = source,
        textField = feature["name"].asString(),
        textFont = const(MapFontStack),
        textSize = const(11.sp),
        textAnchor = const(SymbolAnchor.Bottom),
        textOffset = textOffset(0.em, (-1.4).em),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
        textColor = const(if (isLight) Palette.Zinc700 else Palette.Zinc300),
        textHaloColor = const(if (isLight) Color.White else Color.Black),
        textHaloWidth = const(1.5.dp),
    )
}

/** The vehicle teardrop as a style image (pointing north; the layer rotates it). */
private class TeardropPainter(private val color: Color) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified
    override fun DrawScope.onDraw() = drawVehicleTeardrop(color)
}

/** Live vehicles as teardrops rotated to their heading, one layer per meaning variant. */
@Composable
@MaplibreComposable
fun VehicleLayers(idPrefix: String, frames: List<VehicleFrame>) {
    val json = GeoJson.collection(
        frames.map { f ->
            GeoJson.point(
                f.position.lat, f.position.lon,
                buildJsonObject {
                    put("bearing", JsonPrimitive(f.bearing))
                    put("variant", JsonPrimitive(f.variant.name))
                },
            )
        },
    )
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    for (variant in VehicleVariant.entries) {
        val painter = remember(variant) { TeardropPainter(variant.color()) }
        SymbolLayer(
            id = "$idPrefix-vehicles-${variant.name.lowercase()}", source = source,
            filter = feature["variant"].asString() eq const(variant.name),
            iconImage = image(painter, DpSize(44.dp, 44.dp)),
            iconRotate = feature["bearing"].asNumber(),
            iconRotationAlignment = const(IconRotationAlignment.Map),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
        )
    }
}
