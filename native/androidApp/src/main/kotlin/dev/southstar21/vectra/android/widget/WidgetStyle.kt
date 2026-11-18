package dev.southstar21.vectra.android.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.southstar21.vectra.android.MainActivity
import dev.southstar21.vectra.android.R
import dev.southstar21.vectra.features.widgets.WidgetKind
import dev.southstar21.vectra.platform.AndroidNotifier
import androidx.glance.color.ColorProvider as DayNight

/** How one placed widget looks, picked on its style screen. */
enum class WidgetLook(val label: String, val detail: String) {
    System("System", "Dark at night, light by day"),
    Dark("Dark", "Vectra's dark glass"),
    Light("Light", "Always light"),
    Clear("Clear", "See-through glass, your wallpaper shows"),
    ;

    companion object {
        val Key = stringPreferencesKey("look")

        fun of(prefs: Preferences?): WidgetLook = entries.firstOrNull { it.name == prefs?.get(Key) } ?: System
    }
}

/** The app's tokens (VectraColors) for one look. */
internal class WPalette(
    val look: WidgetLook,
    val text: ColorProvider,
    val secondary: ColorProvider,
    val muted: ColorProvider,
    val soft: ColorProvider,
    val hairline: ColorProvider,
    val line: ColorProvider,
    val track: ColorProvider,
    val pill: ColorProvider,
    val onPill: ColorProvider,
    val check: ColorProvider,
    val onCheck: ColorProvider,
    private val tones: Map<WidgetKind, ColorProvider>,
    @param:DrawableRes val surface: Int,
) {
    val accent: ColorProvider get() = tone(WidgetKind.Lecture)

    fun tone(kind: WidgetKind): ColorProvider = tones.getValue(kind)

    companion object {
        private class Pair2(val day: Long, val night: Long)

        private val Text = Pair2(0xFF0F172A, 0xFFF8FAFC)
        private val Secondary = Pair2(0xFF334155, 0xFF94A3B8)
        private val Muted = Pair2(0xFF64748B, 0xFF7C8AA0)
        private val Soft = Pair2(0xFF334155, 0xFFCBD5E1)
        private val Hairline = Pair2(0x140F172A, 0x14FFFFFF)
        private val Line = Pair2(0x240F172A, 0x29FFFFFF)
        private val Track = Pair2(0x140F172A, 0x14FFFFFF)
        private val Pill = Pair2(0xFF0274B3, 0xFF38BDF8)
        private val OnPill = Pair2(0xFFFFFFFF, 0xFF04121B)
        private val Check = Pair2(0xFF059669, 0xFF34D399)
        private val OnCheck = Pair2(0xFFFFFFFF, 0xFF052E1F)
        private val Tones = mapOf(
            WidgetKind.Lecture to Pair2(0xFF0284C7, 0xFF38BDF8),
            WidgetKind.Other to Pair2(0xFF0284C7, 0xFF38BDF8),
            WidgetKind.Seminar to Pair2(0xFF059669, 0xFF34D399),
            WidgetKind.Lab to Pair2(0xFF7C3AED, 0xFFC084FC),
            WidgetKind.Shift to Pair2(0xFF7C3AED, 0xFFC084FC),
            WidgetKind.Exam to Pair2(0xFF6D28D9, 0xFFC4B5FD),
            WidgetKind.Task to Pair2(0xFFB45309, 0xFFFBBF24),
        )

        fun of(look: WidgetLook): WPalette {
            return when (look) {
                WidgetLook.Clear -> clear()
                else -> {
                    // System follows the launcher's day/night; Dark and Light are fixed.
                    val pick: (Pair2) -> ColorProvider = when (look) {
                        WidgetLook.System -> { p -> DayNight(day = Color(p.day), night = Color(p.night)) }
                        WidgetLook.Dark -> { p -> ColorProvider(Color(p.night)) }
                        else -> { p -> ColorProvider(Color(p.day)) }
                    }
                    WPalette(
                        look, pick(Text), pick(Secondary), pick(Muted), pick(Soft), pick(Hairline), pick(Line), pick(Track),
                        pick(Pill), pick(OnPill), pick(Check), pick(OnCheck), Tones.mapValues { pick(it.value) },
                        when (look) {
                            WidgetLook.Dark -> R.drawable.widget_plate_dark
                            WidgetLook.Light -> R.drawable.widget_plate_light
                            else -> R.drawable.widget_plate
                        },
                    )
                }
            }
        }

        /** Clear: white type on smoked see-through glass; the quiet greys stay bright so they read on any wallpaper. */
        private fun clear(): WPalette {
            fun c(v: Long) = ColorProvider(Color(v))
            return WPalette(
                WidgetLook.Clear, c(0xFFFFFFFF), c(0xD9FFFFFF), c(0xB8FFFFFF), c(0xEBFFFFFF), c(0x33FFFFFF), c(0x4DFFFFFF), c(0x38FFFFFF),
                c(0xFFFFFFFF), c(0xFF0B1220), c(0xFFFFFFFF), c(0xFF0B1220), Tones.mapValues { c(0xFFFFFFFF) }, R.drawable.widget_plate_clear,
            )
        }
    }
}

/**
 * The placed widget's look. Every piece of every widget reads it, like the app's own colours, so a
 * local beats threading a palette through each one.
 */
@Suppress("CompositionLocalAllowlist")
internal val LocalWPalette = staticCompositionLocalOf<WPalette> { error("WidgetTheme missing") }

/** The palette for this widget's look, from its saved state. */
@Composable
internal fun WidgetTheme(content: @Composable () -> Unit) {
    val look = WidgetLook.of(currentState<Preferences>())
    CompositionLocalProvider(LocalWPalette provides WPalette.of(look)) { content() }
}

/**
 * A widget picker preview (no saved state): the System look, at a typical size (previews don't get
 * a real one, and the widgets fit their rows to it).
 */
@Composable
internal fun PreviewTheme(width: Dp, height: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWPalette provides WPalette.of(WidgetLook.System), LocalSize provides DpSize(width, height)) { content() }
}

internal fun style(
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    color: ColorProvider,
    mono: Boolean = false,
) = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = if (mono) FontFamily.Monospace else null)

internal val Caption = 12.sp

/** The whole widget opens the Schedule tab. */
internal fun GlanceModifier.opensSchedule(context: Context): GlanceModifier = clickable(
    actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AndroidNotifier.EXTRA_URL, "/schedule"),
    ),
)

/** The widget itself is the one glass card. */
@Composable
internal fun WidgetSurface(context: Context, padding: Dp = 13.dp, content: @Composable () -> Unit) {
    val p = LocalWPalette.current
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(p.surface)).opensSchedule(context).padding(padding)) { content() }
}

@Composable
internal fun Tinted(@DrawableRes res: Int, tint: ColorProvider, size: Dp) {
    Image(ImageProvider(res), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(size))
}
