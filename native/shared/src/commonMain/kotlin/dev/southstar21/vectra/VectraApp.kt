package dev.southstar21.vectra

import dev.southstar21.vectra.features.coursetracker.CourseHubSheetHost
import dev.southstar21.vectra.features.transit.LiveTrackerSheetHost
import dev.southstar21.vectra.features.weather.WeatherRadarHost
import dev.southstar21.vectra.features.transit.NearbyExplorerHost
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.southstar21.vectra.core.designsystem.components.AppBackground
import dev.southstar21.vectra.core.designsystem.components.LocalAppPresented
import dev.southstar21.vectra.core.designsystem.components.LocalOverlayHost
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.OverlayHost
import dev.southstar21.vectra.core.designsystem.components.OverlayRoot
import dev.southstar21.vectra.core.designsystem.components.Toaster
import dev.southstar21.vectra.core.designsystem.components.ToasterHost
import dev.southstar21.vectra.core.designsystem.theme.VectraTheme
import dev.southstar21.vectra.core.maps.LocalMapEnvironment
import dev.southstar21.vectra.core.platform.rememberSystemReduceMotion
import dev.southstar21.vectra.features.auth.LoginScreen
import dev.southstar21.vectra.features.shell.AppShell
import dev.southstar21.vectra.features.splash.AppSplash
import dev.southstar21.vectra.navigation.AppNavigator
import dev.southstar21.vectra.navigation.DeepLinks
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.PushedScreen
import dev.southstar21.vectra.navigation.TabPage
import kotlinx.coroutines.delay

/** How long the splash may wait for cached content once the session is known. */
private const val CachedContentWaitMs = 1500L

@Composable
fun VectraApp(graph: AppGraph) {
    val startup = graph.startup
    val restored = startup.restored
    val showSplash = remember(graph) { !startup.splashShown }
    var presented by remember { mutableStateOf(!showSplash) }
    var cachedContentWaitOver by remember { mutableStateOf(false) }
    LaunchedEffect(graph) { AppStartup.run(graph) }
    LaunchedEffect(restored) {
        if (restored) {
            delay(CachedContentWaitMs)
            cachedContentWaitOver = true
        }
    }
    // Return to foreground → refresh the session; leaving flushes what is still debounced.
    LifecycleResumeEffect(graph) {
        graph.watchdog.trigger()
        onPauseOrDispose { graph.courseTrackerRepository.flushPendingUpload() }
    }
    LifecycleStartEffect(graph) {
        graph.watchdog.startBackstop()
        onStopOrDispose { graph.watchdog.stopBackstop() }
    }

    val session by graph.auth.session.collectAsStateWithLifecycle()
    val appearance by graph.appearanceRepository.prefs.collectAsStateWithLifecycle()
    val cachedContentReady = if (restored && session != null) {
        graph.scheduleRepository.localReady.collectAsStateWithLifecycle().value
    } else {
        true
    }
    val overlays = remember { OverlayHost() }
    val toaster = remember { Toaster() }

    VectraTheme(appearance, systemDark = isSystemInDarkTheme(), reduceMotion = rememberSystemReduceMotion()) {
        CompositionLocalProvider(
            LocalMapEnvironment provides graph.mapEnvironment,
            LocalOverlayHost provides overlays,
            LocalToaster provides toaster,
            LocalAppPresented provides presented,
        ) {
            OverlayRoot(overlays, topLayer = {
                ToasterHost(toaster)
                if (showSplash) {
                    val appReady = restored && (cachedContentReady || cachedContentWaitOver)
                    AppSplash(appReady, graph.config.appVersion) {
                        startup.splashShown = true
                        presented = true
                    }
                }
            }) {
                if (!restored) {
                    AppBackground()
                } else if (session == null) {
                    AppBackground()
                    LoginScreen(graph.auth, graph.passkeyRepository)
                } else {
                    val navigator = rememberSaveable(session?.userId, saver = AppNavigator.Saver) { AppNavigator() }
                    val link by DeepLinks.pending.collectAsStateWithLifecycle()
                    LaunchedEffect(link) {
                        val route = link?.let(DeepLinks::routeFor) ?: return@LaunchedEffect
                        navigator.navigate(route)
                        DeepLinks.pending.value = null
                    }
                    CompositionLocalProvider(LocalNavigator provides navigator) {
                        AppShell(
                            navigator,
                            page = { TabPage(graph, it) },
                            screen = { PushedScreen(graph, navigator, it) },
                        )
                        LiveTrackerSheetHost(graph.transitRepository, graph.deviceLocation, graph.kv, navigator)
                        CourseHubSheetHost(graph.courseTrackerRepository, graph.scheduleRepository, graph.connectivity, navigator)
                        WeatherRadarHost(graph.weatherRepository, navigator)
                        NearbyExplorerHost(graph.transitRepository, graph.deviceLocation, graph.auth, navigator)
                    }
                }
            }
        }
    }
}
