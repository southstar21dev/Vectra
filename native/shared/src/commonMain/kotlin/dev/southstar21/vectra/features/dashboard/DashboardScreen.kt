package dev.southstar21.vectra.features.dashboard

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.OfflineBanner
import dev.southstar21.vectra.core.designsystem.components.PileCard
import dev.southstar21.vectra.core.designsystem.components.ScrollPile
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.formatTime
import dev.southstar21.vectra.core.time.getDayName
import dev.southstar21.vectra.core.time.monthName
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.coursetracker.resolveCourseKey
import dev.southstar21.vectra.features.holidays.HolidayCard
import dev.southstar21.vectra.features.schedule.DayListHeader
import dev.southstar21.vectra.features.schedule.DayListLoading
import dev.southstar21.vectra.features.schedule.NothingScheduledCard
import dev.southstar21.vectra.features.schedule.ScheduleConflict
import dev.southstar21.vectra.features.schedule.ScheduleDayList
import dev.southstar21.vectra.features.schedule.ScheduleItemConflictException
import dev.southstar21.vectra.features.schedule.ScheduleCourses
import dev.southstar21.vectra.features.schedule.getScheduleItemOccurrencesForDate
import dev.southstar21.vectra.features.schedule.isExamEvent
import dev.southstar21.vectra.features.schedule.nextScheduledStart
import dev.southstar21.vectra.features.schedule.scheduleTodayEvents
import dev.southstar21.vectra.features.shell.VectraPage
import dev.southstar21.vectra.features.weather.WeatherCard
import dev.southstar21.vectra.features.transit.TransitDashboardWidget
import dev.southstar21.vectra.features.transit.rememberFavoriteTrackers
import dev.southstar21.vectra.navigation.LocalNavigator
import dev.southstar21.vectra.navigation.Route
import dev.southstar21.vectra.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.time.Clock

/** The home tab: weather, holidays, today's timeline and favourite trackers. */
@Composable
fun DashboardScreen(graph: AppGraph) {
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val schedule = graph.scheduleRepository
    val tracker = graph.courseTrackerRepository
    val events by schedule.events.collectAsStateWithLifecycle()
    val itemsState by schedule.itemsState.collectAsStateWithLifecycle()
    val conflicts by schedule.conflicts.collectAsStateWithLifecycle()
    val online by schedule.isOnline.collectAsStateWithLifecycle()
    val localReady by schedule.localReady.collectAsStateWithLifecycle()
    val eventsLoading by schedule.eventsLoading.collectAsStateWithLifecycle()
    val items = itemsState.items

    val now = nowInBudapest(Clock.System.now())
    val todayStr = now.ymd
    val intensive = remember(events) { ScheduleCourses.intensiveCourseKeys(events) }
    // Cancelled classes stay in the list (faded, struck through) so the day still makes sense.
    val todayEvents = remember(events, todayStr) { scheduleTodayEvents(events) }
    val plainItems = remember(items) { items.map { it.toItem() } }
    val todayItems = remember(plainItems, todayStr) { getScheduleItemOccurrencesForDate(plainItems, todayStr) }
    val needsAck = remember(conflicts) { conflicts.filter { it.needsAcknowledgment }.flatMap { listOfNotNull(it.itemId, it.otherItemId) }.toSet() }
    val syncIds = remember(items) { items.filter { it.syncConflict != null }.map { it.id }.toSet() }

    val favorites = rememberFavoriteTrackers(graph.transitRepository)

    VectraPage(
        spacing = 0,
        onRefresh = {
            coroutineScope {
                launch { graph.weatherRepository.refreshNow() }
                launch { schedule.refreshEvents(force = true) }
                launch { schedule.refreshItems(force = true) }
                launch { runSuspendCatching { tracker.refresh() } }
                launch { graph.transitRepository.refresh() }
            }
        },
    ) {
        if (!online) OfflineBanner(message = "No internet connection — Dashboard operating in cached offline mode")
        // The cards pile up under each other as the page scrolls (see ScrollPile).
        ScrollPile(
            cards = listOf(
                PileCard("weather", "weather", bottomInset = 24.dp) { Reveal(0) { WeatherCard(graph.weatherRepository) } },
                PileCard("holidays", "holidays", bottomInset = 24.dp) { Reveal(1) { HolidayCard() } },
                // Today's classes: separate cards straight on the page, finished ones stacked on top.
                PileCard("today", "today's schedule", bottomInset = 24.dp, flat = true) {
                    Reveal(2) {
                        // A 40dp gap above, less the 24dp the holiday card already leaves below itself.
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DayListHeader("Today", "${getDayName(now.dayOfWeek)} ${now.day} ${monthName(now.month).take(3)}")
                            if (!localReady || (eventsLoading && events.isEmpty() && items.isEmpty())) {
                                DayListLoading()
                            } else {
                                ScheduleDayList(
                                    events = todayEvents,
                                    tracker = graph.courseTrackerHooks,
                                    dateStr = todayStr,
                                    items = todayItems,
                                    onEventClick = { e -> if (!isExamEvent(e.eventType, e.courseName)) navigator.push(Screen.CourseHub(resolveCourseKey(e))) },
                                    onItemClick = { item -> items.firstOrNull { it.id == item.id }?.let { navigator.push(Screen.ItemEditor(it.id, it.type)) } },
                                    onToggleTaskCompleted = { item ->
                                        scope.launch {
                                            try {
                                                schedule.toggleTaskCompleted(item)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: ScheduleItemConflictException) {
                                                navigator.push(Screen.ItemEditor(item.id, item.type, reopen = true))
                                            } catch (e: Throwable) {
                                                toaster.error(e.message ?: "Could not update task.")
                                            }
                                        }
                                    },
                                    onResolveSyncConflict = { id, res -> schedule.resolveSyncConflict(id, res) },
                                    syncConflictIds = syncIds,
                                    needsAcknowledgmentIds = needsAck,
                                    itemConflictsById = remember(conflicts) {
                                        buildMap<String, MutableList<ScheduleConflict>> {
                                            for (cf in conflicts) for (id in listOfNotNull(cf.itemId, cf.otherItemId)) getOrPut("${cf.date}:$id") { mutableListOf() } += cf
                                        }
                                    },
                                    classConflictsByEventId = remember(conflicts) { conflicts.filter { it.classId != null }.groupBy { "${it.date}:${it.classId}" } },
                                    intensiveCourses = intensive,
                                    collapseFinished = true,
                                    offline = !online,
                                    emptyPlaceholder = {
                                        val next = remember(events, plainItems, todayStr) { nextScheduledStart(events, plainItems, todayStr) }
                                        NothingScheduledCard(next?.first, next?.second?.let { formatTime(it, Vectra.appearance.timeFormat) })
                                    },
                                )
                            }
                        }
                    }
                },
                PileCard("transit", "transit", bottomInset = 24.dp) {
                    Reveal(3) {
                        TransitDashboardWidget(
                            graph.transitRepository,
                            graph.deviceLocation,
                            graph.auth,
                            favorites = favorites,
                            onOpenTracker = { navigator.push(Screen.LiveTracker(it.id, it.stopId)) },
                            onOpenTransit = { navigator.navigate(Route.Transit) },
                            modifier = Modifier.padding(bottom = 24.dp),
                        )
                    }
                },
            ),
        )
    }

}
