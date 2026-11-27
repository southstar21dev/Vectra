package dev.southstar21.vectra.features.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.HairlineDivider
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.VectraLogo
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.cardDepthShadows
import dev.southstar21.vectra.core.designsystem.theme.insetShadow
import dev.southstar21.vectra.core.designsystem.theme.rgba
import dev.southstar21.vectra.core.designsystem.theme.vtext

/** The card stays card-sized on wide windows, like the sign-in card. */
private val CardWidth = 448.dp

/**
 * Shown in place of the whole app once the server refuses this build (layout U1: the sign-in
 * screen's owl above one card). There is no way past it except updating.
 */
@Composable
internal fun UpdateRequiredScreen(required: UpdateRequired, installedVersion: String, modifier: Modifier = Modifier) {
    val target = remember { updateTarget() }
    val uri = LocalUriHandler.current
    Box(
        modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Reveal(0, Modifier.widthIn(max = CardWidth).fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Owl()
                UpdateCard(required, installedVersion, target.label) { runCatching { uri.openUri(target.url) } }
            }
        }
    }
}

/** The brand owl with its purple glow, as on the sign-in screen; dark in light mode. */
@Composable
private fun Owl() {
    val c = Vectra.colors
    Box(
        Modifier.size(112.dp).drawBehind {
            drawCircle(
                Brush.radialGradient(0f to rgba(168, 85, 247, 0.45f), 1f to Color.Transparent, radius = size.minDimension * 0.55f),
                radius = size.minDimension * 0.55f,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        VIcon(VectraLogo, size = 112.dp, tint = if (c.isDark) Color.White else Color.Black)
    }
}

@Composable
private fun UpdateCard(required: UpdateRequired, installedVersion: String, buttonLabel: String, onUpdate: () -> Unit) {
    val c = Vectra.colors
    val shape = Radius.Card
    val depth = cardDepthShadows(c.isDark)
    Column(
        Modifier
            .fillMaxWidth()
            .boxShadow(shape, depth)
            .background(c.surfaceSecondary, shape)
            .background(
                Brush.linearGradient(
                    0f to rgba(168, 85, 247, if (c.isDark) 0.10f else 0.08f),
                    0.45f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(600f, 1000f),
                ),
                shape,
            )
            .insetShadow(shape, depth)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VText("Update Vectra to continue", style = vtext(TypeScale.Headline, Weight.Bold, Tracking.Heading), color = c.textPrimary)
        VText(
            "This version of Vectra is no longer supported. Install the latest one to keep using the app.",
            style = vtext(TypeScale.BodyLarge),
            color = c.textSecondary,
        )
        Column {
            VersionRow("Installed", installedVersion, c.textPrimary)
            required.minVersion?.let {
                HairlineDivider()
                VersionRow("Needed", "$it or newer", c.tints.amber.text)
            }
        }
        VButton(buttonLabel, onUpdate, Modifier.fillMaxWidth(), size = ButtonSize.Lg)
    }
}

@Composable
private fun VersionRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        VText(label, Modifier.weight(1f), vtext(TypeScale.BodyLarge), Vectra.colors.textSecondary, maxLines = 1)
        VText(value, style = vtext(TypeScale.BodyLarge, Weight.Semibold, mono = true), color = valueColor, maxLines = 1)
    }
}
