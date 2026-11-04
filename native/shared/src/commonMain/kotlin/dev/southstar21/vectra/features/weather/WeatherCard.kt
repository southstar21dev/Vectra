package dev.southstar21.vectra.features.weather

import androidx.compose.ui.graphics.vector.ImageVector
import dev.southstar21.vectra.core.util.roundHalfUpToInt
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Screen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.southstar21.vectra.core.designsystem.components.StatusLabel
import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.pulse
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.vtext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/**
 * A linear gradient at [angleDeg] painted inside [shape]: the gradient line runs
 * through the centre at that angle (0° = up, clockwise) and is long enough to
 * reach the corners.
 */
internal fun Modifier.angledLinearGradient(angleDeg: Float, from: Color, to: Color, shape: Shape): Modifier = drawBehind {
    val rad = angleDeg * PI.toFloat() / 180f
    val dx = sin(rad)
    val dy = -cos(rad)
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val brush = Brush.linearGradient(listOf(from, to), start = c - Offset(dx * half, dy * half), end = c + Offset(dx * half, dy * half))
    drawOutline(shape.createOutline(size, layoutDirection, this), brush)
}

/** Sky gradient stops. */
private fun skyStops(sky: WeatherSky, c: VectraColors): Pair<Color, Color> = when (sky) {
    WeatherSky.Clear, WeatherSky.Partly -> c.tints.sky.bg to c.tints.amber.bg
    WeatherSky.Cloudy, WeatherSky.Fog -> c.surfaceOverlay to c.tints.sky.bg
    WeatherSky.Rain -> c.tints.blue.bg to c.tints.sky.bg
    WeatherSky.Snow -> c.tints.sky.bg to c.surfaceOverlay
    WeatherSky.Storm -> c.tints.violet.bg to c.tints.blue.bg
}

private fun skyArt(sky: WeatherSky) = when (sky) {
    WeatherSky.Clear -> RemixIcons.SunFill
    WeatherSky.Partly -> RemixIcons.SunCloudyFill
    WeatherSky.Cloudy -> RemixIcons.CloudyFill
    WeatherSky.Fog -> RemixIcons.FoggyFill
    WeatherSky.Rain -> RemixIcons.RainyFill
    WeatherSky.Snow -> RemixIcons.SnowyFill
    WeatherSky.Storm -> RemixIcons.ThunderstormsFill
}

/** SKY_TONE: ink and opacity of the corner artwork. */
private fun skyTone(sky: WeatherSky, c: VectraColors): Pair<Color, Float> = when (sky) {
    WeatherSky.Clear, WeatherSky.Partly -> Palette.Amber400 to 1f
    WeatherSky.Cloudy, WeatherSky.Fog -> c.textMuted to 0.6f
    WeatherSky.Rain -> c.tints.sky.text to 1f
    WeatherSky.Snow -> c.tints.sky.text to 0.8f
    WeatherSky.Storm -> c.tints.violet.text to 1f
}

private fun alertIcon(key: WeatherAlertKey) = when (key) {
    WeatherAlertKey.HighWind -> RemixIcons.WindyLine
    WeatherAlertKey.Heat -> RemixIcons.TempHotLine
    WeatherAlertKey.Cold -> RemixIcons.SnowflakeLine
    WeatherAlertKey.Rain -> RemixIcons.RainyLine
}

private val Tnum = "tnum"

/**
 * The dashboard's slim weather strip; tapping it opens the
 * live radar. Hides itself when the first fetch fails with nothing to show.
 */
@Composable
fun WeatherCard(repo: WeatherRepository, modifier: Modifier = Modifier) {
    val location by repo.location.collectAsStateWithLifecycle()
    val weather by repo.weather.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    // Window focus: re-check the location permission and refresh stale weather.
    LifecycleResumeEffect(repo) {
        repo.onForeground()
        onPauseOrDispose { }
    }

    val loc = location
    val coords = loc?.coords ?: BudapestCoords
    val data = weather.data
    val isPlaceholder = data != null && weather.dataCoords?.let { coordsMatch(it, coords) } != true

    val liveTitle = loc?.let(::weatherLocationTitle) ?: "Budapest"
    // While a location change is in flight the card keeps the previous place's
    // weather, so the header keeps naming that place until the new fetch lands.
    var settledTitle by remember { mutableStateOf(liveTitle) }
    if (data != null && !isPlaceholder && settledTitle != liveTitle) settledTitle = liveTitle
    val title = if (isPlaceholder) settledTitle else liveTitle

    if (weather.error != null && data == null) return
    if (loc == null || data == null) {
        WeatherCardSkeleton(modifier)
        return
    }

    val meta = weatherCodeMeta(data.weatherCode)
    val alerts = weatherAlertItems(data)
    val c = Vectra.colors
    val shape = Radius.Card
    val (skyFrom, skyTo) = skyStops(meta.sky, c)
    val (artTint, artAlpha) = skyTone(meta.sky, c)
    val interaction = remember { MutableInteractionSource() }

    Box(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .pressScale(interaction, 0.995f)
                .glass(shape)
                .angledLinearGradient(160f, skyFrom, skyTo, shape)
                .clip(shape)
                .tappable(role = Role.Button, highlight = false, interactionSource = interaction) { navigator.push(Screen.WeatherRadar) }
                .semantics {
                    contentDescription = "Weather for $title: ${roundHalfUpToInt(data.temperature)} degrees, ${meta.label}. Tap to open live weather radar."
                },
        ) {
            // Condition artwork spilling off the top-right corner.
            VIcon(
                skyArt(meta.sky),
                Modifier.align(Alignment.TopEnd).offset(x = 20.dp, y = (-24).dp).alpha(artAlpha),
                size = 104.dp,
                tint = artTint,
            )
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    VIcon(RemixIcons.MapPin2Fill, size = 14.dp, tint = c.textSecondary)
                    VText(title, Modifier.weight(1f, fill = false), vtext(TypeScale.Caption, Weight.Semibold), c.textSecondary, maxLines = 1)
                    Row(
                        Modifier.padding(start = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VIcon(RemixIcons.RadarLine, size = 12.dp, tint = c.textMuted)
                        VText("Radar", style = vtext(TypeScale.Caption, Weight.Semibold), color = c.textMuted, maxLines = 1)
                    }
                }
                VText(
                    "${roundHalfUpToInt(data.temperature)}°",
                    Modifier.padding(top = 8.dp),
                    vtext(54.sp to 54.sp, Weight.Bold, Tracking.Tighter).copy(fontFeatureSettings = Tnum),
                    c.textPrimary,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VText(
                        buildAnnotatedString {
                            append(meta.label)
                            withStyle(SpanStyle(fontWeight = Weight.Medium, color = c.textSecondary)) {
                                append(" · H ${roundHalfUpToInt(data.high)}° L ${roundHalfUpToInt(data.low)}°")
                            }
                        },
                        Modifier.weight(1f),
                        vtext(TypeScale.Body, Weight.Semibold),
                        c.textPrimary,
                        maxLines = 1,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Reading(RemixIcons.TempHotLine, "${roundHalfUpToInt(data.feelsLike)}°", "Feels like")
                        Reading(RemixIcons.DropLine, "${roundHalfUpToInt(data.rainProbability)}%", "Rain")
                        Reading(RemixIcons.WindyLine, "${roundHalfUpToInt(data.windSpeed)} km/h", "Wind")
                    }
                }
                if (alerts.isNotEmpty()) {
                    val line = c.borderSubtle
                    FlowRow(
                        Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .drawBehind { drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        alerts.forEach { StatusLabel(it.label, tone = StatusTone.Amber, icon = alertIcon(it.key)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Reading(icon: ImageVector, value: String, label: String) {
    val c = Vectra.colors
    Row(
        Modifier.semantics { contentDescription = "$label $value" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIcon(icon, size = 14.dp, tint = c.textMuted)
        VText(value, style = vtext(13.sp to 18.sp, Weight.Bold).copy(fontFeatureSettings = Tnum), color = c.textPrimary, maxLines = 1)
    }
}

/** Slim pulse strip while the first fetch is in flight — no layout jump. */
@Composable
private fun WeatherCardSkeleton(modifier: Modifier) {
    val c = Vectra.colors
    val shape = Radius.Card
    Box(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .boxShadow(shape, c.glassShadow)
                .background(c.glassBg, shape)
                .insetShadow(shape, c.glassShadow)
                .border(1.dp, c.borderSubtle, shape)
                .padding(24.dp)
                .pulse(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(c.surfaceOverlay, Radius.Control))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(144.dp, 12.dp).background(c.surfaceOverlay, Radius.Hairline))
                Box(Modifier.size(96.dp, 12.dp).background(c.surfaceOverlay, Radius.Hairline))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(64.dp).height(36.dp).background(c.surfaceOverlay, Radius.Hairline))
        }
    }
}
