package dev.southstar21.vectra.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.southstar21.vectra.core.designsystem.components.HairlineDivider
import dev.southstar21.vectra.core.designsystem.components.PostageStamp
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.theme.Radius

enum class StampTilt(val degrees: Float) { Left(-3f), Right(2f) }

/**
 * One glass panel of rows separated by hairlines, titled with a postage stamp stuck over its top
 * edge. [danger]: the stamp is red (the Danger zone, where every destructive action lives).
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    tilt: StampTilt = StampTilt.Left,
    danger: Boolean = false,
    rows: List<@Composable () -> Unit>,
    /** A quiet link under the last row ("Send a test reminder"), with no hairline above it. */
    footer: (@Composable () -> Unit)? = null,
) {
    val c = Vectra.colors
    Box(modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column(Modifier.fillMaxWidth().glass(Radius.Card).clip(Radius.Card).padding(top = 16.dp)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HairlineDivider()
                row()
            }
            footer?.invoke()
        }
        PostageStamp(
            Modifier.padding(start = 16.dp).offset(y = (-20).dp).rotate(tilt.degrees).zIndex(1f),
            innerModifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            ink = if (danger) c.tints.red.text else c.accentPrimary,
        ) {
            VText(title, style = vtext(TypeScale.Body, Weight.Extrabold, Tracking.Tight, lineHeight = 14.sp), color = if (danger) c.tints.red.text else c.textPrimary)
        }
    }
}

/** SETTINGS_BLOCK_CLASS: padding for a non-row block inside a section. */
val SettingsBlockPadding = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
