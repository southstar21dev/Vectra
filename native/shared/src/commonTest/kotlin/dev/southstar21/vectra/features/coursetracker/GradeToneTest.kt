package dev.southstar21.vectra.features.coursetracker

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import dev.southstar21.vectra.core.preferences.AccentKey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GradeToneTest {
    private val dark = VectraColors.dark(AccentKey.Blue)
    private val light = VectraColors.light(AccentKey.Blue)

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun hue(c: Color): Float {
        val mx = maxOf(c.red, c.green, c.blue)
        val d = mx - minOf(c.red, c.green, c.blue)
        val h = when (mx) {
            c.red -> ((c.green - c.blue) / d) % 6
            c.green -> (c.blue - c.red) / d + 2
            else -> (c.red - c.green) / d + 4
        }
        return (h * 60f + 360f) % 360f
    }

    @Test fun everyGradeHasItsOwnInk() {
        for (c in listOf(dark, light)) {
            val inks = (1..5).map { gradeTone(it, c).text }
            assertEquals(5, inks.toSet().size)
        }
    }

    @Test fun gradeTwoIsOrangeNotAmber() {
        assertEquals(Color(0xFFFB923C), gradeTone(2, dark).text)
        assertEquals(Color(0xFFC2410C), gradeTone(2, light).text)
        assertNotEquals(gradeTone(3, dark).text, gradeTone(2, dark).text)
    }

    @Test fun lightInksOfTwoAndThreeAreReadableOnWhite() {
        for (g in 2..3) assertTrue(contrast(gradeTone(g, light).text, Color.White) >= 4.5f, "grade $g")
    }

    @Test fun gradesTwoAndThreeDifferInHueAndLightnessInLight() {
        val two = gradeTone(2, light)
        val three = gradeTone(3, light)
        assertTrue(abs(hue(two.text) - hue(three.text)) >= 20f)
        assertTrue(abs(two.text.luminance() - three.text.luminance()) >= 0.03f)
        assertTrue(abs(hue(two.zone) - hue(three.zone)) >= 12f)
    }

    @Test fun darkZonesAreNearlyOpaqueInk() {
        val tone = gradeTone(4, dark)
        assertEquals(tone.text.copy(alpha = 0.9f), tone.zone)
    }
}
