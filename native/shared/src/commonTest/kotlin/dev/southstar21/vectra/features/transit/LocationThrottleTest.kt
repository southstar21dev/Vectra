package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.maps.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocationThrottleTest {
    private val here = LatLon(47.4979, 19.0402)
    private val nearby = LatLon(47.49795, 19.0402) // about 6 m north
    private val farther = LatLon(47.4995, 19.0402) // about 180 m north

    @Test fun theFirstFixPersists() = assertTrue(shouldPersistFix(0, null, 1_000, here))

    @Test fun aStillPhoneWaitsAMinute() {
        assertFalse(shouldPersistFix(0, here, 59_999, nearby))
        assertTrue(shouldPersistFix(0, here, 60_000, nearby))
    }

    @Test fun aRealMoveDoesNotWait() = assertTrue(shouldPersistFix(0, here, 5_000, farther))

    @Test fun jitterStaysInOneAnchorCell() {
        val base = anchorCell(47.49800)
        assertEquals(base, anchorCell(47.49801))
        assertNotEquals(base, anchorCell(47.49900))
    }
}
