package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.Tint
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.vtext

/** What a [Banner] says: a mistake, a warning, a notice, a success or a plain note. */
internal enum class BannerTone { Red, Amber, Blue, Green, Neutral }

@Composable
private fun BannerTone.tint(): Tint {
    val c = Vectra.colors
    return when (this) {
        BannerTone.Red -> c.tints.red
        BannerTone.Amber -> c.tints.amber
        BannerTone.Blue -> c.tints.blue
        BannerTone.Green -> c.tints.emerald
        BannerTone.Neutral -> Tint(c.surfaceOverlay, c.borderPrimary, c.textPrimary)
    }
}

/**
 * The one inline message: tint background, tint border and tint ink at the Panel radius, a bold
 * first line (bold only when a body follows) and an optional body. [trailing] is a chevron ([BannerChevron]) or a small button;
 * [onClick] makes the whole banner tappable.
 */
@Composable
internal fun Banner(
    tone: BannerTone,
    icon: ImageVector?,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tint = tone.tint()
    val shape = Radius.Panel
    Row(
        modifier
            .fillMaxWidth()
            .background(tint.bg, shape)
            .border(1.dp, tint.border, shape)
            .then(if (onClick != null) Modifier.tappable(shape, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) VIcon(icon, tint = tint.text)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VText(title, style = vtext(TypeScale.Caption, if (body != null) Weight.Bold else Weight.Normal), color = tint.text)
            if (body != null) VText(body, style = vtext(TypeScale.Caption), color = tint.text)
        }
        trailing?.invoke()
    }
}

/** The trailing chevron of a tappable [Banner], in the banner's ink. */
@Composable
internal fun BannerChevron(tone: BannerTone, modifier: Modifier = Modifier) {
    VIcon(RemixIcons.ArrowRightSLine, modifier.padding(start = 2.dp), tint = tone.tint().text)
}
