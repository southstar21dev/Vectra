package dev.southstar21.vectra.features.transit

import kotlin.test.Test
import kotlin.test.assertEquals

class DirectionTabIndexTest {
    private val labels = listOf("Blaha", "Teleki")

    @Test fun nothingPickedIsTheFirstTab() = assertEquals(0, directionTabIndex(labels, null))

    @Test fun followsTheLabelWhenTheOrderChanges() {
        assertEquals(1, directionTabIndex(labels, "Teleki"))
        assertEquals(0, directionTabIndex(labels.reversed(), "Teleki"))
    }

    @Test fun aVanishedDirectionFallsBackToTheFirst() = assertEquals(0, directionTabIndex(labels, "Gone"))
}
