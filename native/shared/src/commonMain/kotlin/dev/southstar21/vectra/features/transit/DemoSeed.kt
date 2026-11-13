package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One seeding at a time, once per account and app run. */
private val seedLock = Mutex()
private val seeded = HashSet<String>()

/**
 * Debug builds: the first time a demo account opens the dashboard or the Transit tab, its
 * trackers are created. [DemoFeed.DemoEmail] gets one tracker per edge case plus a few real
 * lines (three pinned); [DemoFeed.NoPinEmail] gets two real lines and no pins.
 */
@Composable
internal fun DemoSeedEffect(repo: TransitRepository, trackers: List<TransitTracker>, fetched: Boolean) {
    val email = repo.userEmail?.lowercase()?.takeIf { repo.demoFeed && (it == DemoFeed.DemoEmail || it == DemoFeed.NoPinEmail) } ?: return
    LaunchedEffect(email, fetched) {
        if (!fetched) return@LaunchedEffect
        seedLock.withLock {
            if (!seeded.add(email)) return@withLock
            runSuspendCatching { seedDemo(repo, email, trackers) }
        }
    }
}

private suspend fun seedDemo(repo: TransitRepository, email: String, trackers: List<TransitTracker>) {
    if (email == DemoFeed.DemoEmail) {
        // Whatever is missing: a first sign-in gets all of them, a later build its new cases.
        val have = trackers.mapTo(HashSet()) { it.routeId to it.stopId }
        for (line in DemoLines.filter { (it.routeId to it.stops.first().stopId) !in have }) {
            val t = repo.addTracker(
                NewTrackerInput(
                    routeId = line.routeId,
                    routeShortName = line.shortName,
                    routeLongName = "Demo line",
                    routeType = line.type,
                    routeColor = line.color,
                    routeTextColor = line.textColor,
                    stops = line.stops.map { TrackerStop(it.stopId, it.name, "") },
                ),
            )
            if (line.pinned) repo.toggleFavorite(t.id)
        }
        RealDemoTrackers.filter { (it.routeId to it.stopId) !in have }.forEach { addReal(repo, it) }
    }
    if (email == DemoFeed.NoPinEmail && trackers.isEmpty()) {
        RealDemoTrackers.take(2).forEach { addReal(repo, it) }
    }
}

private suspend fun addReal(repo: TransitRepository, r: RealDemoTracker) {
    repo.addTracker(
        NewTrackerInput(
            routeId = r.routeId,
            routeShortName = r.shortName,
            routeLongName = "",
            routeType = r.type,
            routeColor = r.color,
            routeTextColor = r.textColor,
            stops = listOf(TrackerStop(r.stopId, r.stopName, "")),
        ),
    )
}
