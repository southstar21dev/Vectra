package dev.southstar21.vectra.core.data

/**
 * A per-user storage key (or key family `<base>_<userId>`). Every entry is removed on sign-out;
 * [large] values are stored one file each instead of in DataStore; [keepOnInvoluntarySignOut]
 * keeps the key so the same account can replay writes this device has not uploaded yet.
 */
internal class UserKey(val base: String, val large: Boolean = false, val keepOnInvoluntarySignOut: Boolean = false)

/**
 * The single registry of per-user keys. `core` cannot import features, so the base names live here
 * as constants and the features refer to them (e.g. `WeatherSnapshotKey = UserKeys.WEATHER_SNAPSHOT`).
 * A new per-user snapshot is added here once and the sign-out wipe and blob storage follow.
 */
internal object UserKeys {
    const val SCHEDULE_EVENTS = "vectra_schedule_cache_events"
    const val SCHEDULE_ITEMS = "vectra_schedule_items"
    const val TRANSIT_TRACKERS = "vectra_transit_trackers_cache"
    const val COURSE_TRACKER = "vectra_course_tracker_cache"
    const val TRIP_PLANS = "vectra_trip_plans_cache"
    const val NEPTUN = "vectra_neptun"
    const val WEATHER_SNAPSHOT = "vectra.weather.snapshot"
    const val DEVICE_LOCATION = "vectra.deviceLocation"

    val all: List<UserKey> = listOf(
        UserKey(SCHEDULE_EVENTS, large = true),
        UserKey(SCHEDULE_ITEMS, large = true, keepOnInvoluntarySignOut = true),
        UserKey(TRANSIT_TRACKERS, large = true),
        UserKey(COURSE_TRACKER, large = true, keepOnInvoluntarySignOut = true),
        UserKey(TRIP_PLANS, large = true),
        UserKey(NEPTUN),
        UserKey(WEATHER_SNAPSHOT, large = true),
        UserKey(DEVICE_LOCATION, large = true),
    )
}
