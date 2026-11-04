package dev.southstar21.vectra.features.transit

import dev.southstar21.vectra.core.designsystem.components.ToastAction
import dev.southstar21.vectra.core.designsystem.components.Toaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Deletes [tracker] and offers an Undo toast. Throws if the delete fails. */
internal suspend fun removeTrackerWithUndo(repo: TransitRepository, toaster: Toaster, tracker: TransitTracker) {
    repo.deleteTracker(tracker.id)
    toaster.success(
        "Tracker removed",
        "Moved to the end of your list if you undo.",
        ToastAction("Undo") { restoreTracker(repo, toaster, tracker) },
        durationMs = 8000,
    )
}

/**
 * Best-effort undo of a delete: the row is gone, so this re-creates the tracker from the
 * captured copy — same stops, directions and favourite flag — at the end of the manual
 * order. Runs on the app scope: Undo can be tapped after the screen has gone.
 */
private fun restoreTracker(repo: TransitRepository, toaster: Toaster, tracker: TransitTracker) {
    repo.scope.launch {
        try {
            val stops = getTrackerStops(tracker)
            val restored = repo.addTracker(
                NewTrackerInput(
                    routeId = tracker.routeId,
                    routeShortName = tracker.routeShortName,
                    routeLongName = tracker.routeLongName,
                    routeType = tracker.routeType,
                    routeColor = tracker.routeColor,
                    routeTextColor = tracker.routeTextColor,
                    stops = stops,
                    stopId = tracker.stopId,
                    stopName = tracker.stopName,
                    headsign = tracker.headsign,
                ),
            )
            if (stops.size > 1) repo.updateTrackerStops(restored.id, stops)
            if (tracker.isFavorite) repo.toggleFavorite(restored.id)
            toaster.success("Tracker restored")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            toaster.error("Could not restore tracker", t.message)
        }
    }
}
