package dev.southstar21.vectra.features.transit.demo

import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.transit.data.NewTrackerInput
import dev.southstar21.vectra.features.transit.data.TrackerStop
import dev.southstar21.vectra.features.transit.data.TransitRepository
import dev.southstar21.vectra.features.transit.data.TransitTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debug builds: the first time a demo account signs in during a run, its trackers are created.
 * [DemoFeed.DemoEmail] gets one tracker per edge case plus a few real lines (three pinned);
 * [DemoFeed.NoPinEmail] gets two real lines and no pins. Started once from `AppStartup`, so no
 * screen has to be open for it, and it does nothing when [enabled] is false (release builds).
 */
class DemoSeeder(
    private val auth: AuthRepository,
    private val transit: TransitRepository,
    private val scope: CoroutineScope,
    val enabled: Boolean,
) {
    /** One seeding at a time, once per account and app run. */
    private val lock = Mutex()
    private val seeded = HashSet<String>()

    fun start() {
        if (!enabled) return
        scope.launch {
            auth.session.map { it?.email?.lowercase() }.distinctUntilChanged().collect { email ->
                if (email == DemoFeed.DemoEmail || email == DemoFeed.NoPinEmail) seed(email)
            }
        }
    }

    /** Creates what [email]'s demo account is missing, once per run. A failure is left for the next run. */
    suspend fun seed(email: String) = lock.withLock {
        if (!seeded.add(email)) return@withLock
        runSuspendCatching { seedDemo(transit, email, transit.trackersQuery.fetch(Unit)) }
            .onFailure { seeded.remove(email) }
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
                    stops = line.stops.map { TrackerStop(it.stopId, it.name, it.picked.orEmpty()) },
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
