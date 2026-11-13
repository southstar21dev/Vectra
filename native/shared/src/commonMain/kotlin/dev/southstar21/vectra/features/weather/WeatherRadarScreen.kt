package dev.southstar21.vectra.features.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.vtext

/**
 * The radar as a pushed screen (a link straight to it, a restored back stack): the same view as
 * the one the weather card grows into, without the grow.
 */
@Composable
fun WeatherRadarScreen(repo: WeatherRepository, onBack: () -> Unit) {
    RadarView(repo, grow = { 1f }, opened = true, orbFrom = null, onClose = onBack)
}

@Composable
internal fun RadarMessage(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = Vectra.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VIcon(RemixIcons.CloudyLine, size = 40.dp, tint = c.textMuted)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            VText(title, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary, align = TextAlign.Center)
            VText(body, Modifier.padding(top = 4.dp).widthIn(max = 320.dp), vtext(TypeScale.Caption), c.textSecondary, align = TextAlign.Center)
        }
        action?.invoke()
    }
}
