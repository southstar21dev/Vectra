package dev.southstar21.vectra.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParseHexColorTest {
    @Test
    fun sixAndThreeDigitValuesAreOpaqueInBothModes() {
        assertEquals(Color(0xFF38BDF8), parseHexColor("#38bdf8"))
        assertEquals(Color(0xFF38BDF8), parseHexColor("38BDF8", opaque = true))
        assertEquals(Color(0xFFAABBCC), parseHexColor("#abc"))
        assertEquals(Color(0xFFAABBCC), parseHexColor(" #abc ", opaque = true))
    }

    @Test
    fun anEightDigitValueKeepsItsAlphaUnlessOpaqueIsAsked() {
        assertEquals(Color(0x8038BDF8), parseHexColor("#38bdf880"))
        assertEquals(Color(0xFF38BDF8), parseHexColor("#38bdf880", opaque = true))
    }

    @Test
    fun unreadableValuesAreNullSoCallersPickTheirFallback() {
        assertNull(parseHexColor(null))
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("zzzzzz", opaque = true))
    }
}
