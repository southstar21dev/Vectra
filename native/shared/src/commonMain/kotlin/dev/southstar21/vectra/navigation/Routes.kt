package dev.southstar21.vectra.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The four tabs of the signed-in app. */
@Serializable
sealed interface Route : NavKey {
    @Serializable data object Dashboard : Route
    @Serializable data object Schedule : Route
    @Serializable data object Transit : Route
    @Serializable data object Settings : Route
}

/**
 * Screens pushed over the tabs. [Tabs] is the root of the stack and stands for the
 * tab shell underneath; everything above it slides in from the side.
 */
@Serializable
sealed interface Screen : NavKey {
    @Serializable data object Tabs : Screen
    @Serializable data object WeatherRadar : Screen

    // Transit
    @Serializable data object AddTracker : Screen
    @Serializable data object ManageTrackers : Screen
    @Serializable data class EditTracker(val trackerId: String) : Screen
    /** [stopId] picks which of a multi-stop tracker's stops opens first. */
    @Serializable data class LiveTracker(val trackerId: String, val stopId: String? = null) : Screen
    @Serializable data class TrackerMap(val trackerId: String, val stopId: String? = null) : Screen
    @Serializable data object NearbyExplorer : Screen
    // Schedule & course hub
    /** Add a manual class ([eventId] null) or edit one. */
    @Serializable data class ClassEditor(val eventId: String? = null) : Screen
    /**
     * Add ([itemId] null) or edit a work shift or task; [kind] is a `ScheduleItem.TYPE_*`.
     * [reopen] edits a completed task as not done (un-ticking it hit a clash to acknowledge).
     */
    @Serializable data class ItemEditor(val itemId: String? = null, val kind: String, val reopen: Boolean = false) : Screen
    @Serializable data object NeptunImport : Screen
    /** The Course Hub of the course stored under [courseKey]. */
    @Serializable data class CourseHub(val courseKey: String) : Screen
}

data class NavItem(val route: Route, val label: String, val icon: ImageVector, val activeIcon: ImageVector)

val PrimaryNavItems: List<NavItem> = listOf(
    NavItem(Route.Dashboard, "Dashboard", RemixIcons.DashboardLine, RemixIcons.DashboardFill),
    NavItem(Route.Schedule, "Schedule", RemixIcons.Calendar2Line, RemixIcons.Calendar2Fill),
    NavItem(Route.Transit, "Transit", RemixIcons.TrainLine, RemixIcons.TrainFill),
    NavItem(Route.Settings, "Settings", RemixIcons.Settings3Line, RemixIcons.Settings3Fill),
)

val PrimaryRoutes: List<Route> = PrimaryNavItems.map { it.route }

/** Tabs whose navbar shows a + actions button. */
val ActionRoutes: Set<Route> = setOf(Route.Transit, Route.Schedule)

/**
 * Two levels of navigation:
 * - Tabs: the tab stack holds at most Home plus one tab. Switching between non-home tabs
 *   replaces the top; going Home pops; back from a tab goes Home, then leaves the app.
 * - Screens: a stack pushed over the tabs ([screens], rooted at [Screen.Tabs]).
 */
@Stable
class AppNavigator(start: Route = Route.Dashboard, screens: List<Screen> = listOf(Screen.Tabs)) {
    val backStack = mutableStateListOf<Route>().apply {
        add(Route.Dashboard)
        if (start != Route.Dashboard) add(start)
    }

    val screens = mutableStateListOf<Screen>().apply { addAll(screens.ifEmpty { listOf(Screen.Tabs) }) }

    val current: Route get() = backStack.last()

    /** Bumped when the current tab is selected again: its page scrolls back to the top. */
    var reselections by mutableIntStateOf(0)
        private set

    fun navigate(to: Route) {
        popToTabs()
        if (to == current) {
            reselections++
            return
        }
        when {
            to == Route.Dashboard -> while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            backStack.size == 1 -> backStack.add(to)
            else -> backStack[backStack.lastIndex] = to
        }
    }

    fun push(screen: Screen) {
        if (screens.last() != screen) screens.add(screen)
    }

    /** Pops the top screen; false when only the tabs are left. */
    fun pop(): Boolean {
        if (screens.size <= 1) return false
        screens.removeAt(screens.lastIndex)
        return true
    }

    /** Replaces the top screen (a flow moving on to its next step). */
    fun replace(screen: Screen) {
        if (screens.size <= 1) push(screen) else screens[screens.lastIndex] = screen
    }

    fun popToTabs() {
        while (screens.size > 1) screens.removeAt(screens.lastIndex)
    }

    /** True when handled in-app; false means the system should leave the app. */
    fun back(): Boolean {
        if (pop()) return true
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val screensSerializer = ListSerializer(Screen.serializer())

        /** Survives process death: the tab and the pushed screens come back as they were. */
        val Saver: Saver<AppNavigator, List<String>> = Saver(
            save = { listOf(json.encodeToString(Route.serializer(), it.current), json.encodeToString(screensSerializer, it.screens.toList())) },
            restore = { saved ->
                runCatching {
                    AppNavigator(json.decodeFromString(Route.serializer(), saved[0]), json.decodeFromString(screensSerializer, saved[1]))
                }.getOrElse { AppNavigator() }
            },
        )
    }
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> { error("AppNavigator missing") }
