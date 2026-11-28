package dev.southstar21.vectra.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserKeysTest {
    @Test fun storedKeyNamesAreStable() {
        // These names are on users' disks; renaming one orphans the data (and skips the wipe).
        assertEquals(
            listOf(
                "vectra_schedule_cache_events",
                "vectra_schedule_items",
                "vectra_transit_trackers_cache",
                "vectra_course_tracker_cache",
                "vectra_trip_plans_cache",
                "vectra_neptun",
                "vectra.weather.snapshot",
                "vectra.deviceLocation",
            ),
            UserKeys.all.map { it.base },
        )
    }

    @Test fun offlineQueueAndLargeKeysAreSubsetsOfTheScopedKeys() {
        assertEquals(setOf("vectra_schedule_items", "vectra_course_tracker_cache"), OfflineQueueKeyPrefixes.toSet())
        assertTrue(LargeValueKeyPrefixes.all { it in UserScopedKeyPrefixes })
        assertEquals(UserKeys.all.size, UserKeys.all.map { it.base }.toSet().size)
    }
}
