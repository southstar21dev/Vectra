package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIAction
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UISegmentedControl
import platform.UIKit.UIUserInterfaceStyle

actual val hasNativeSegmentedControl: Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NativeSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val latest by rememberUpdatedState(onSelect)
    val c = Vectra.colors
    val dark = c.isDark
    // UIKit views sit under the Compose canvas, seen through a hole in it; the control's track is
    // translucent, so it gets an opaque backing in the surface colour or the views below show through.
    val backing = c.surfaceSecondary
    UIKitView(
        factory = {
            SegmentedHost(UISegmentedControl(items = labels).apply {
                selectedSegmentIndex = selectedIndex.toLong()
                addAction(
                    UIAction.actionWithHandler { action ->
                        val index = (action?.sender as? UISegmentedControl)?.selectedSegmentIndex ?: return@actionWithHandler
                        latest(index.toInt())
                    },
                    forControlEvents = UIControlEventValueChanged,
                )
            })
        },
        update = { host ->
            host.backgroundColor = backing.toUIColor()
            val control = host.control
            if (control.selectedSegmentIndex != selectedIndex.toLong()) control.selectedSegmentIndex = selectedIndex.toLong()
            host.overrideUserInterfaceStyle =
                if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
        },
        modifier = modifier,
        properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
    )
}

/** The control on an opaque backing, filling it. */
@OptIn(ExperimentalForeignApi::class)
private class SegmentedHost(val control: UISegmentedControl) : UIView(frame = CGRectZero.readValue()) {
    init {
        control.setFrame(bounds)
        control.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        addSubview(control)
    }
}

private fun Color.toUIColor(): UIColor =
    UIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())
