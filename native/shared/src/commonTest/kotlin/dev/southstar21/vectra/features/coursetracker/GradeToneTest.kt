package dev.southstar21.vectra.features.coursetracker

import androidx.compose.ui.graphics.Color
import dev.southstar21.vectra.core.preferences.AccentKey
import dev.southstar21.vectra.core.designsystem.theme.VectraColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GradeToneTest {
    private val dark = VectraColors.dark(AccentKey.Blue)
    private val light = VectraColors.light(AccentKey.Blue)

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

    @Test fun zonesAreALightTintOfTheInk() {
        val tone = gradeTone(4, dark)
        assertEquals(tone.text.copy(alpha = 0.6f), tone.zone)
    }
}
