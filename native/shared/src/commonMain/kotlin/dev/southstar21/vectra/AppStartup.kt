package dev.southstar21.vectra

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.southstar21.vectra.core.maps.configureMapNetworking
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.weather.RadarMapScheme

/** Startup facts that outlive the Activity, so a recreate neither restores nor animates again. */
class ProcessStartup {
    var restored by mutableStateOf(false)
    var splashShown = false
}

/** What has to happen once per process before the app can show the session. */
internal object AppStartup {
    suspend fun run(graph: AppGraph) {
        val startup = graph.startup
        if (startup.restored) return
        configureMapNetworking(graph.http, listOf(RadarMapScheme(graph.http)))
        runSuspendCatching { graph.kv.moveLargeValuesToBlobs() }
        // Neither may crash the launch: unreadable appearance falls back to defaults, an unreadable
        // session to signed out.
        runSuspendCatching { graph.appearanceRepository.loadStored() }
        runSuspendCatching { graph.auth.restore() }
        // A build the server has refused stays locked, offline too, until it is updated.
        runSuspendCatching { graph.updateGate.loadStored() }
        startup.restored = true
        graph.watchdog.trigger()
        // Debug builds only: gives the demo accounts their trackers whichever screen opens first.
        graph.demoSeeder.start()
        // Neptun auto-sync starts from the repository's init, so it can't wait for a screen to ask.
        // A process started in the background (alarm, widget) never got here, so the timetable
        // refetch and the auto-sync it held back start now.
        graph.scheduleRepository.onForeground()
    }
}
