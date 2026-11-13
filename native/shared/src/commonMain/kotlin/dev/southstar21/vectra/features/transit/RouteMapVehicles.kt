package dev.southstar21.vectra.features.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.maps.GeoJson
import dev.southstar21.vectra.core.maps.MapFontStack
import dev.southstar21.vectra.core.maps.VehicleToken
import dev.southstar21.vectra.core.maps.VehicleTokenBodyPainter
import dev.southstar21.vectra.core.maps.VehicleTokenPointPainter
import dev.southstar21.vectra.core.maps.VehicleVariant
import dev.southstar21.vectra.core.maps.position
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
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.overlay.MapOverlayScope
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable

/*
 * The route map's vehicles: round tokens in the line's colour, a white point for the way each
 * is heading, and its countdown to your stop in a small glass label above it. Drawn as Compose
 * markers where they track the map, and as map layers where they don't (iOS).
 */

/** The token's fill and the vehicle drawn on it. */
@Immutable
internal data class TokenStyle(val fill: Color, val ink: Color)

/** A vehicle's countdown label; [soon] (due now or in a minute) is green. */
@Immutable
internal data class VehicleCallout(val label: String, val soon: Boolean)

/** Estimated positions (the metro, from the timetable) are drawn a little faded. */
private const val ApproximateAlpha = 0.7f

/** Every vehicle's callout, by vehicle id. */
internal fun calloutsFor(vehicles: List<RouteMapVehicle>): Map<String, VehicleCallout> =
    vehicles.mapNotNull { v -> v.label?.let { v.vehicleId to VehicleCallout(it, v.labelSoon) } }.toMap()

@Composable
internal fun MapOverlayScope.RoundVehicleMarkers(
    frames: List<VehicleFrame>,
    mapBearing: () -> Double,
    style: TokenStyle,
    callouts: Map<String, VehicleCallout>,
) {
    for (f in frames) {
        key(f.id) {
            val rotation = rememberMarkerRotation(f.bearing, mapBearing)
            // The token sits at the box's centre; the label clears its heading point above.
            Box(Modifier.placedAt(f.position.position, Alignment.Center).size(72.dp, 96.dp)) {
                callouts[f.id]?.let { CalloutPill(it, Modifier.align(Alignment.TopCenter)) }
                VehicleToken(
                    style.fill, style.ink,
                    headingDeg = { rotation.value },
                    modifier = Modifier.align(Alignment.Center),
                    alpha = if (f.variant == VehicleVariant.Approximate) ApproximateAlpha else 1f,
                )
            }
        }
    }
}

@Composable
private fun CalloutPill(callout: VehicleCallout, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    Box(
        modifier
            .height(26.dp)
            .boxShadow(CircleShape, listOf(BoxShadow(y = 3f, blur = 8f, color = Color.Black.copy(alpha = 0.4f))))
            .background(c.glassBgElevated.copy(alpha = 0.94f), CircleShape)
            .border(1.dp, c.glassBorder, CircleShape)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        VText(
            callout.label,
            style = vtext(14.sp to 18.sp, Weight.Extrabold, mono = true).tnum(),
            color = if (callout.soon) c.tints.emerald.text else Vectra.monitor.live,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The same vehicles as map layers: body (never turns), heading point (turns with the bearing), countdown. */
@Composable
@MaplibreComposable
internal fun RoundVehicleLayers(idPrefix: String, frames: List<VehicleFrame>, style: TokenStyle, callouts: Map<String, VehicleCallout>) {
    val c = Vectra.colors
    val json = GeoJson.collection(
        frames.map { f ->
            GeoJson.point(
                f.position.lat, f.position.lon,
                buildJsonObject {
                    put("bearing", JsonPrimitive(f.bearing))
                    put("kind", JsonPrimitive(if (f.variant == VehicleVariant.Approximate) "approx" else "live"))
                    callouts[f.id]?.let {
                        put("label", JsonPrimitive(it.label))
                        put("tone", JsonPrimitive(if (it.soon) "soon" else "later"))
                    }
                },
            )
        },
    )
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(json))
    val box = DpSize(42.dp, 42.dp)
    val point = remember { VehicleTokenPointPainter(1f) }
    SymbolLayer(
        id = "$idPrefix-vehicle-point", source = source,
        iconImage = image(point, box),
        iconRotate = feature["bearing"].asNumber(),
        iconRotationAlignment = const(IconRotationAlignment.Map),
        iconAllowOverlap = const(true),
        iconIgnorePlacement = const(true),
    )
    for ((kind, alpha) in listOf("live" to 1f, "approx" to ApproximateAlpha)) {
        val body = remember(style, alpha) { VehicleTokenBodyPainter(style.fill, style.ink, alpha) }
        SymbolLayer(
            id = "$idPrefix-vehicle-body-$kind", source = source,
            filter = feature["kind"].asString() eq const(kind),
            iconImage = image(body, box),
            iconAllowOverlap = const(true),
            iconIgnorePlacement = const(true),
        )
    }
    for ((tone, ink) in listOf("soon" to c.tints.emerald.text, "later" to Vectra.monitor.live)) {
        SymbolLayer(
            id = "$idPrefix-vehicle-label-$tone", source = source,
            filter = feature["tone"].asString() eq const(tone),
            textField = feature["label"].asString(),
            textFont = const(MapFontStack),
            textSize = const(14.sp),
            textAnchor = const(SymbolAnchor.Bottom),
            textOffset = textOffset(0.em, (-1.5).em),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
            textColor = const(ink),
            textHaloColor = const(c.glassBgElevated),
            textHaloWidth = const(3.dp),
        )
    }
}
