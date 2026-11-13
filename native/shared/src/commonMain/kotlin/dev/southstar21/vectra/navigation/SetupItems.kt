package dev.southstar21.vectra.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.launchCatching
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.platform.PermissionKind
import dev.southstar21.vectra.core.platform.rememberPermissionRequester
import dev.southstar21.vectra.features.reminders.rememberEnabler
import dev.southstar21.vectra.features.settings.SettingsAnchor
import dev.southstar21.vectra.features.settings.SetupItem
import dev.southstar21.vectra.features.transit.collect
import dev.southstar21.vectra.features.weather.WeatherLocationMode

/**
 * The setup card's features, each with whether it's on and the tap that turns it on. They span
 * every feature, so they're put together here rather than in Settings. [scrollTo] brings a
 * section into view for the ones set up in place (commute lines, passkeys).
 */
@Composable
fun rememberSetupItems(graph: AppGraph, scrollTo: (SettingsAnchor) -> Unit): List<SetupItem> {
    val c = Vectra.colors
    val navigator = LocalNavigator.current
    val profile by graph.auth.profile.collectAsStateWithLifecycle()
    val prefs by graph.reminders.prefs.collectAsStateWithLifecycle()
    val location by graph.weatherRepository.location.collectAsStateWithLifecycle()
    val passkeys by graph.passkeyRepository.passkeys.collectAsStateWithLifecycle()
    val trackers = graph.transitRepository.trackersQuery.collect(Unit).data
    val enableReminders = rememberEnabler(graph.notifier)
    val locationRequester = rememberPermissionRequester(PermissionKind.Location)
    val notifications = rememberNotificationsSetup(graph)
    return listOfNotNull(
        SetupItem(
            RemixIcons.CalendarLine, c.tints.blue.text, "Link your Neptun timetable", "Your classes, kept in sync",
            done = profile?.let { !it.neptunIcsUrl.isNullOrBlank() }, actionLabel = "Link",
        ) { navigator.push(Screen.NeptunImport) },
        SetupItem(
            RemixIcons.TimeLine, c.tints.sky.text, "Class reminders", "A nudge before each class, with the room",
            done = prefs.beforeClass || prefs.nextClass, actionLabel = "Turn on",
        ) { enableReminders { graph.reminders.update { it.copy(beforeClass = true) } } },
        notifications,
        SetupItem(
            RemixIcons.MapPinLine, c.tints.amber.text, "Use your location", "Local weather and the stops near you",
            done = location?.let { it.mode == WeatherLocationMode.Auto }, actionLabel = "Allow",
        ) { locationRequester.request { graph.weatherRepository.setMode(WeatherLocationMode.Auto) } },
        SetupItem(
            RemixIcons.BusLine, c.tints.emerald.text, "Track a stop", "Live departures on your dashboard",
            done = trackers?.isNotEmpty(), actionLabel = "Add",
        ) { navigator.push(Screen.AddTracker) },
        SetupItem(
            RemixIcons.RouteLine, c.tints.violet.text, "Commute lines", "Departures home and to uni in your reminders",
            done = prefs.toUni.isNotEmpty() || prefs.backHome.isNotEmpty(), actionLabel = "Pick",
        ) { scrollTo(SettingsAnchor.Commute) },
        SetupItem(
            RemixIcons.FingerprintLine, c.tints.purple.text, "Passkey sign-in", "Your fingerprint or face instead of a password",
            done = passkeys?.isNotEmpty(), actionLabel = "Set up",
        ) { scrollTo(SettingsAnchor.Account) }.takeIf { graph.passkeyRepository.isSupported },
    )
}

/** Push notifications (or, without push, the system permission), and turning them on from the card. */
@Composable
private fun rememberNotificationsSetup(graph: AppGraph): SetupItem {
    val c = Vectra.colors
    val repo = graph.notificationsRepository
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val subscribed by repo.subscribed.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(repo.permissionGranted()) }
    LifecycleResumeEffect(repo) {
        granted = repo.permissionGranted()
        onPauseOrDispose { }
    }
    val requester = rememberPermissionRequester(PermissionKind.Notifications)
    return SetupItem(
        RemixIcons.Notification3Line, c.tints.purple.text, "Notifications", "So reminders and departure alerts reach you",
        done = if (repo.pushAvailable) subscribed else granted, actionLabel = "Allow",
    ) {
        requester.request { ok ->
            granted = ok
            when {
                !ok -> toaster.error("Notifications are blocked. Allow them for Vectra in system settings.")
                repo.pushAvailable -> scope.launchCatching({ repo.subscribe(); toaster.success("Push notifications enabled") }) {
                    toaster.error(it.message ?: "Couldn't turn on push notifications")
                }
            }
        }
    }
}
