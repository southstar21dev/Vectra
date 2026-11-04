package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.southstar21.vectra.core.auth.SessionListener

/**
 * The tracker a pushed screen shows, looked up by id in the trackers query. Once the list
 * has loaded without it (deleted here or on another device) [onMissing] runs. The last
 * copy seen is kept, so a screen leaving after a delete still has something to draw.
 */
@Composable
internal fun rememberTracker(repo: TransitRepository, trackerId: String, onMissing: () -> Unit): TransitTracker? {
    val trackers = repo.trackersQuery.collect(Unit).data
    val found = trackers?.firstOrNull { it.id == trackerId }
    val last = remember(trackerId) { arrayOfNulls<TransitTracker>(1) }
    if (found != null) last[0] = found
    val missing = trackers != null && found == null
    val latestOnMissing by rememberUpdatedState(onMissing)
    LaunchedEffect(missing) { if (missing) latestOnMissing() }
    return found ?: last[0]
}

/**
 * A stop picked on the tracker map, handed back to the live screen under it: the map
 * screen writes it, the live screen takes it once it is on top again.
 */
class TrackerStopPicks : SessionListener {
    private val picks = mutableStateMapOf<String, String>()

    override suspend fun onSessionCleared() = picks.clear()

    fun put(trackerId: String, stopId: String) {
        picks[trackerId] = stopId
    }

    fun peek(trackerId: String): String? = picks[trackerId]

    fun take(trackerId: String): String? = picks.remove(trackerId)
}
