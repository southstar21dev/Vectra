package dev.southstar21.vectra.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRefreshTest {
    private val now = 1_000_000_000L
    private val week = 7 * 24 * 3600L

    @Test fun theFirstOfAProcessAlwaysRefreshes() = assertTrue(shouldRefreshSession(null, now + week, now))

    @Test fun anUnreadableExpiryRefreshes() = assertTrue(shouldRefreshSession(now, null, now))

    @Test fun recentRefreshIsNotRepeated() = assertFalse(shouldRefreshSession(now - 60, now + week, now))

    @Test fun anHourLaterItRefreshesAgain() = assertTrue(shouldRefreshSession(now - 3600, now + week, now))

    @Test fun closeToExpiryIgnoresTheGap() = assertTrue(shouldRefreshSession(now - 60, now + 3600, now))
}
