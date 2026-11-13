package dev.southstar21.vectra.features.transit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

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
