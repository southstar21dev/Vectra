package dev.southstar21.vectra.navigation

import androidx.compose.runtime.Composable
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.features.dashboard.DashboardScreen
import dev.southstar21.vectra.features.schedule.NeptunSettings
import dev.southstar21.vectra.features.schedule.ScheduleScreen
import dev.southstar21.vectra.features.settings.SettingsScreen
import dev.southstar21.vectra.features.transit.AddTrackerScreen
import dev.southstar21.vectra.features.transit.EditTrackerScreen
import dev.southstar21.vectra.features.transit.LiveTrackerScreen
import dev.southstar21.vectra.features.transit.ManageTrackersScreen
import dev.southstar21.vectra.features.transit.NearbyExplorerScreen
import dev.southstar21.vectra.features.transit.TrackerMapScreen
import dev.southstar21.vectra.features.transit.TransitScreen
import dev.southstar21.vectra.features.weather.LocationSettingRow
import dev.southstar21.vectra.features.weather.WeatherRadarScreen
// Schedule & course hub
import dev.southstar21.vectra.features.coursetracker.CourseHubScreen
import dev.southstar21.vectra.features.schedule.ClassEditorScreen
import dev.southstar21.vectra.features.schedule.ItemEditorScreen
import dev.southstar21.vectra.features.schedule.NeptunImportScreen

/** The page shown for each tab. */
@Composable
fun TabPage(graph: AppGraph, route: Route) {
    when (route) {
        Route.Dashboard -> DashboardScreen(graph)
        Route.Schedule -> ScheduleScreen(graph.scheduleRepository, graph.courseTrackerHooks)
        Route.Transit -> TransitScreen(graph.transitRepository, graph.deviceLocation, graph.kv)
        Route.Settings -> SettingsScreen(
            graph,
            neptunSection = { NeptunSettings(graph.scheduleRepository, graph.auth) },
            locationRow = { LocationSettingRow(graph.weatherRepository) },
        )
    }
}

/** The content of each pushed screen. */
@Composable
fun PushedScreen(graph: AppGraph, navigator: AppNavigator, screen: Screen) {
    val back = { navigator.pop(); Unit }
    when (screen) {
        Screen.Tabs -> Unit
        Screen.WeatherRadar -> WeatherRadarScreen(graph.weatherRepository, onBack = back)

        // Transit. A screen whose tracker was deleted pops itself; only while it is still
        // on top, so a screen animating out can never pop the one under it.
        Screen.AddTracker -> AddTrackerScreen(graph.transitRepository, onBack = back)
        Screen.ManageTrackers -> ManageTrackersScreen(graph.transitRepository, onBack = back)
        is Screen.EditTracker -> EditTrackerScreen(graph.transitRepository, screen.trackerId, onBack = { popIfTop(navigator, screen) })
        is Screen.LiveTracker -> LiveTrackerScreen(graph.transitRepository, graph.trackerStopPicks, screen.trackerId, screen.stopId, onBack = { popIfTop(navigator, screen) })
        is Screen.TrackerMap -> TrackerMapScreen(graph.transitRepository, graph.trackerStopPicks, screen.trackerId, screen.stopId, onBack = { popIfTop(navigator, screen) })
        Screen.NearbyExplorer -> NearbyExplorerScreen(graph.transitRepository, graph.deviceLocation, graph.auth, onBack = back)
        // Schedule & course hub
        is Screen.ClassEditor -> ClassEditorScreen(graph.scheduleRepository, graph.courseTrackerHooks, screen.eventId, onBack = back)
        is Screen.ItemEditor -> ItemEditorScreen(graph.scheduleRepository, screen.itemId, screen.kind, screen.reopen, onBack = back)
        Screen.NeptunImport -> NeptunImportScreen(graph.scheduleRepository, graph.auth, onBack = back)
        is Screen.CourseHub -> CourseHubScreen(graph.courseTrackerRepository, graph.scheduleRepository, graph.connectivity, screen.courseKey, onBack = back)
    }
}

private fun popIfTop(navigator: AppNavigator, screen: Screen) {
    if (navigator.screens.lastOrNull() == screen) navigator.pop()
}
