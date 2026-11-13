package dev.southstar21.vectra.core.designsystem

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.southstar21.vectra.core.designsystem.components.bottomEdgePadding
import dev.southstar21.vectra.core.designsystem.theme.pressesAfter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesignSystemDetailsTest {
    @Test
    fun onlyPressesMoveTheHighlight() {
        val press = PressInteraction.Press(Offset.Zero)
        assertEquals(1, pressesAfter(0, press))
        assertEquals(0, pressesAfter(1, PressInteraction.Release(press)))
        assertEquals(0, pressesAfter(0, PressInteraction.Cancel(press)))
        assertNull(pressesAfter(0, HoverInteraction.Enter()))
        assertNull(pressesAfter(0, FocusInteraction.Focus()))
    }

    @Test
    fun bottomBarClearsTheNavigationBarOnlyOnce() {
        // No keyboard: the navigation bar plus the bar's own 12dp.
        assertEquals(36.dp, bottomEdgePadding(navigationBar = 24.dp, keyboard = 0.dp))
        // Keyboard up: ScreenColumn already sits above it, and it covers the navigation bar.
        assertEquals(12.dp, bottomEdgePadding(navigationBar = 24.dp, keyboard = 300.dp))
        // Keyboard sliding in, still shorter than the navigation bar.
        assertEquals(22.dp, bottomEdgePadding(navigationBar = 24.dp, keyboard = 14.dp))
    }
}
