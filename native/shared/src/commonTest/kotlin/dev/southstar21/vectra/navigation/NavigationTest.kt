package dev.southstar21.vectra.navigation

import dev.southstar21.vectra.navigation.NavGestures.Point
import dev.southstar21.vectra.navigation.NavGestures.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tap/swipe classification and navigation semantics. */
class NavigationTest {
    @Test
    fun tapSlop() {
        assertTrue(NavGestures.isTap(Point(100f, 50f), Point(102f, 51f)))
        assertFalse(NavGestures.isTap(Point(100f, 50f), Point(130f, 55f)))
    }

    @Test
    fun swipeSteps() {
        assertEquals(1, NavGestures.swipeStep(Point(100f, 50f), Point(200f, 52f)))
        assertEquals(-1, NavGestures.swipeStep(Point(200f, 50f), Point(100f, 50f)))
        assertEquals(0, NavGestures.swipeStep(Point(100f, 50f), Point(130f, 55f)))
        assertEquals(0, NavGestures.swipeStep(Point(100f, 50f), Point(200f, 170f)))
    }

    @Test
    fun resolvesTapsAndWrappingSwipes() {
        val start = Point(100f, 50f)
        assertEquals(Result.Tap(2), NavGestures.resolve(start, Point(101f, 50f), 2, 0, 4))
        assertEquals(Result.Tap(null), NavGestures.resolve(start, Point(101f, 50f), null, 0, 4))
        assertEquals(Result.Swipe(2), NavGestures.resolve(start, Point(200f, 50f), null, 1, 4))
        assertEquals(Result.Swipe(0), NavGestures.resolve(start, Point(20f, 50f), null, 1, 4))
        assertEquals(Result.Swipe(3), NavGestures.resolve(start, Point(20f, 50f), null, 0, 4))
        assertEquals(Result.Swipe(0), NavGestures.resolve(start, Point(200f, 50f), null, 3, 4))
        assertEquals(Result.None, NavGestures.resolve(start, Point(200f, 170f), null, 1, 4))
    }

    @Test
    fun backStackHoldsHomePlusOne() {
        val nav = AppNavigator()
        nav.navigate(Route.Schedule)
        assertEquals(listOf(Route.Dashboard, Route.Schedule), nav.backStack.toList())
        nav.navigate(Route.Transit)
        nav.navigate(Route.Settings)
        assertEquals(listOf(Route.Dashboard, Route.Settings), nav.backStack.toList())
        assertTrue(nav.back())
        assertEquals(Route.Dashboard, nav.current)
        assertFalse(nav.back())
    }

    @Test
    fun navigatingHomePops() {
        val nav = AppNavigator(Route.Transit)
        assertEquals(listOf(Route.Dashboard, Route.Transit), nav.backStack.toList())
        nav.navigate(Route.Dashboard)
        assertEquals(listOf<Route>(Route.Dashboard), nav.backStack.toList())
    }

    @Test
    fun backPopsScreensBeforeTabs() {
        val nav = AppNavigator(Route.Transit)
        nav.push(Screen.WeatherRadar)
        nav.push(Screen.WeatherRadar)
        assertEquals(listOf(Screen.Tabs, Screen.WeatherRadar), nav.screens.toList())
        assertTrue(nav.back())
        assertEquals(listOf<Screen>(Screen.Tabs), nav.screens.toList())
        assertEquals(Route.Transit, nav.current)
        assertTrue(nav.back())
        assertEquals(Route.Dashboard, nav.current)
        assertFalse(nav.back())
    }

    @Test
    fun switchingTabsClosesScreensAndReselectBumps() {
        val nav = AppNavigator()
        nav.push(Screen.WeatherRadar)
        nav.navigate(Route.Dashboard)
        assertEquals(listOf<Screen>(Screen.Tabs), nav.screens.toList())
        assertEquals(1, nav.reselections)
    }
}
