package dev.southstar21.vectra

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.southstar21.vectra.core.maps.configureMapNetworking
import dev.southstar21.vectra.core.util.runSuspendCatching

/** Startup facts that outlive the Activity, so a recreate neither restores nor animates again. */
class ProcessStartup {
    var restored by mutableStateOf(false)
    var splashShown = false
}

/** What has to happen once per process before the app can show the session. */
object AppStartup {
    suspend fun run(graph: AppGraph) {
        val startup = graph.startup
        if (startup.restored) return
        configureMapNetworking(graph.http)
        runSuspendCatching { graph.kv.moveLargeValuesToBlobs() }
        graph.appearanceRepository.loadStored()
        graph.auth.restore()
        startup.restored = true
        graph.watchdog.trigger()
        // Neptun auto-sync starts from the repository's init, so it can't wait for a screen to ask.
        graph.scheduleRepository
    }
}
