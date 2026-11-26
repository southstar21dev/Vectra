package dev.southstar21.vectra.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ExponentialBackoffTest {
    @Test fun doublesFromTheBase() {
        assertEquals(1000L, exponentialBackoffMs(0, 1000))
        assertEquals(2000L, exponentialBackoffMs(1, 1000))
        assertEquals(4000L, exponentialBackoffMs(2, 1000))
    }

    @Test fun neverExceedsTheCap() {
        assertEquals(30_000L, exponentialBackoffMs(5, 1000, 30_000))
        assertEquals(30_000L, exponentialBackoffMs(500, 1000, 30_000))
    }

    @Test fun anAttemptBelowZeroWaitsTheBase() = assertEquals(2000L, exponentialBackoffMs(-3, 2000))
}
