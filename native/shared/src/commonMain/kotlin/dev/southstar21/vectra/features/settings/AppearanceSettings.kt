package dev.southstar21.vectra.features.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VTabs
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.components.drawMesh
import dev.southstar21.vectra.core.designsystem.components.ellipticalGlow
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.AccentPresets
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.mixTransparent
import dev.southstar21.vectra.core.designsystem.theme.pressScale
import dev.southstar21.vectra.core.designsystem.theme.VectraSprings
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.preferences.BackgroundKey
import dev.southstar21.vectra.core.preferences.ThemeMode
import dev.southstar21.vectra.core.preferences.TimeFormat
import dev.southstar21.vectra.core.preferences.isDark
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

@Composable
private fun SectionLabel(text: String) =
    VText(text, Modifier.padding(bottom = 8.dp), vtext(11.sp to 16.sp, Weight.Semibold), Vectra.colors.textMuted)

/** The "Look" section. Every control applies instantly. */
@Composable
fun AppearanceSettings(repo: AppearanceRepository) {
    val prefs by repo.prefs.collectAsStateWithLifecycle()
    val c = Vectra.colors
    SettingsSection("Look", tilt = StampTilt.Left, rows = listOf {
        Column(SettingsBlockPadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column {
                SectionLabel("Mode")
                VTabs(prefs.theme, ThemeMode.entries, { m -> repo.update { it.copy(theme = m) } }, Modifier.fillMaxWidth(), triggerHeight = 36.dp, pillSpec = VectraSprings.snappy(), pillHonorsReduceMotion = false) { m, _ ->
                    VIcon(
                        when (m) {
                            ThemeMode.Light -> RemixIcons.SunFill
                            ThemeMode.Dark -> RemixIcons.MoonFill
                            ThemeMode.System -> RemixIcons.ComputerFill
                        },
                        contentDescription = "${m.key} mode",
                    )
                }
            }
            Column {
                SectionLabel("Accent")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    AccentPresets.forEach { preset ->
                        val active = prefs.accent == preset.key
                        val interaction = remember { MutableInteractionSource() }
                        // transition-colors: the ring's border colour eases in (150ms); its glow snaps.
                        val ring by animateColorAsState(if (active) c.accentPrimary else c.accentPrimary.copy(alpha = 0f), tween(150), label = "accentRing")
                        Box(
                            Modifier.size(38.dp)
                                .then(if (active) Modifier.boxShadow(CircleShape, listOf(BoxShadow(blur = 12f, color = c.accentPrimary.mixTransparent(40f)))) else Modifier)
                                .border(2.dp, ring, CircleShape)
                                .padding(3.dp)
                                .pressScale(interaction)
                                .boxShadow(CircleShape, listOf(BoxShadow(y = 1f, blur = 3f, color = Color.Black.copy(alpha = 0.1f))))
                                .background(if (c.isDark) preset.dark else preset.light, CircleShape)
                                .tappable(role = null, highlight = false, interactionSource = interaction) { repo.update { it.copy(accent = preset.key) } }
                                .semantics { contentDescription = preset.name },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (active) VIcon(RemixIcons.CheckFill, tint = Color.White)
                        }
                    }
                }
            }
            Column {
                SectionLabel("Background")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BackgroundKey.entries.forEach { key ->
                        val active = prefs.background == key
                        val interaction = remember { MutableInteractionSource() }
                        val shape = Radius.Panel
                        // Border and ring ease together over 150ms.
                        val lit by animateFloatAsState(if (active) 1f else 0f, tween(150), label = "bgTile")
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .then(if (lit > 0f) Modifier.border(4.dp, c.accentPrimary.copy(alpha = 0.4f * lit), shape) else Modifier)
                                .pressScale(interaction)
                                .clip(shape)
                                .border(1.dp, lerp(c.borderSubtle, c.accentPrimary, lit), shape)
                                .tappable(role = null, highlight = false, interactionSource = interaction) { repo.update { it.copy(background = key) } }
                                .semantics { contentDescription = key.label },
                        ) { BackgroundPreview(key, c) }
                    }
                }
            }
            Column {
                SectionLabel("Time Format")
                VTabs(prefs.timeFormat, TimeFormat.entries, { f -> repo.update { it.copy(timeFormat = f) } }, Modifier.fillMaxWidth(), triggerHeight = 36.dp, pillSpec = VectraSprings.snappy(), pillHonorsReduceMotion = false) { f, _ ->
                    VIcon(RemixIcons.TimeFill, size = 14.dp)
                    VText(if (f == TimeFormat.H24) "24-hour" else "12-hour", style = vtext(TypeScale.Caption, Weight.Medium))
                }
            }
        }
    })
}

/** Background preview thumbnails. */
@Composable
private fun BackgroundPreview(key: BackgroundKey, c: VectraColors) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(c.surfaceSecondary)
        when (key) {
            BackgroundKey.Mesh -> drawMesh(c.borderSubtle, 8.dp.toPx())
            BackgroundKey.Flat -> Unit
            BackgroundKey.Aurora -> {
                ellipticalGlow(Offset(size.width * 0.2f, size.height * 0.2f), size.width * 1.2f, size.height * 0.9f, c.accentPrimary.mixTransparent(60f), 0.55f)
                ellipticalGlow(Offset(size.width * 0.85f, size.height * 0.8f), size.width * 1.2f, size.height * 0.9f, VectraColors.AccentSecondary.mixTransparent(55f), 0.55f)
            }
        }
    }
}
