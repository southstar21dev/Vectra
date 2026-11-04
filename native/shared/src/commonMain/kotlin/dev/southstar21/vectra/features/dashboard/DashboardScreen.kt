package dev.southstar21.vectra.features.dashboard

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.southstar21.vectra.AppGraph
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.OfflineBanner
import dev.southstar21.vectra.core.designsystem.components.PostageStamp
import dev.southstar21.vectra.core.designsystem.components.Reveal
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.time.getDayName
import dev.southstar21.vectra.core.time.monthName
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.util.runSuspendCatching
import dev.southstar21.vectra.features.coursetracker.resolveCourseKey
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.features.holidays.HolidayCard
import dev.southstar21.vectra.features.schedule.ScheduleConflict
import dev.southstar21.vectra.features.schedule.ScheduleDayTimeline
import dev.southstar21.vectra.features.schedule.ScheduleItemConflictException
import dev.southstar21.vectra.features.schedule.ScheduleCourses
import dev.southstar21.vectra.features.schedule.getScheduleItemOccurrencesForDate
import dev.southstar21.vectra.features.schedule.isExamEvent
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
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** The home tab: weather, holidays, today's timeline and favourite trackers. */
@Composable
fun DashboardScreen(graph: AppGraph) {
    val c = Vectra.colors
    val navigator = LocalNavigator.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val schedule = graph.scheduleRepository
    val tracker = graph.courseTrackerRepository
    val events by schedule.events.collectAsStateWithLifecycle()
    val itemsState by schedule.itemsState.collectAsStateWithLifecycle()
    val conflicts by schedule.conflicts.collectAsStateWithLifecycle()
    val online by schedule.isOnline.collectAsStateWithLifecycle()
    val trackerData by tracker.data.collectAsStateWithLifecycle()
    val items = itemsState.items

    val now = nowInBudapest(Clock.System.now())
    val todayStr = now.ymd
    val intensive = remember(events) { ScheduleCourses.intensiveCourseKeys(events) }
    val eventsToday = remember(events, todayStr) { scheduleTodayEvents(events) }
    val todayEvents = remember(eventsToday, todayStr, trackerData) {
        eventsToday.filter { !tracker.isSessionHoliday(it.courseName, it.courseCode, it.semester, todayStr, it.startTime) }
    }
    val plainItems = remember(items) { items.map { it.toItem() } }
    val todayItems = remember(plainItems, todayStr) { getScheduleItemOccurrencesForDate(plainItems, todayStr) }
    LaunchedEffect(todayStr) { schedule.setConflictDate(todayStr) }
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
        Reveal(0) { WeatherCard(graph.weatherRepository) }
        Reveal(1) { HolidayCard() }

        // Today's classes: the date as a postage stamp stuck over the card's top-right corner.
        Reveal(2) {
            // A 40dp gap above, less the 24dp the holiday card already leaves below itself.
            Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)) {
                Column(Modifier.fillMaxWidth().glass(Radius.Card).padding(16.dp)) {
                    ScheduleDayTimeline(
                        events = todayEvents,
                        tracker = graph.courseTrackerHooks,
                        items = todayItems,
                        dateStr = todayStr,
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
                        emptyPlaceholder = { NoScheduleToday() },
                    )
                }
                PostageStamp(
                    Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = (-36).dp).rotate(6f).zIndex(1f),
                    innerModifier = Modifier.size(56.dp, 62.dp),
                ) {
                    VText(monthName(now.month).take(3).uppercase(), style = vtext(11.sp to 11.sp, Weight.Extrabold, 0.08.em), color = c.accentPrimary)
                    VText(now.day.toString(), style = vtext(22.sp to 22.sp, Weight.Extrabold).tnum(), color = c.textPrimary)
                    VText(getDayName(now.dayOfWeek).take(3), style = vtext(11.sp to 11.sp, Weight.Semibold), color = c.textSecondary)
                }
            }
        }
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
    }

}

/** The ☕ placeholder card shown when today has nothing on it. */
@Composable
private fun NoScheduleToday() {
    val c = Vectra.colors
    val shape = Radius.Card
    Column(
        Modifier.fillMaxWidth()
            .boxShadow(shape, listOf(BoxShadow(y = 4f, blur = 6f, spread = -1f, color = Color.Black.copy(alpha = 0.1f))))
            .background(c.surfaceSecondary.copy(alpha = 0.8f), shape)
            .border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(c.tints.amber.bg, Radius.Panel), contentAlignment = Alignment.Center) {
            VText("☕", style = vtext(TypeScale.Title))
        }
        VText("No schedule items today", style = vtext(TypeScale.BodyLarge, Weight.Bold), color = c.textPrimary, align = TextAlign.Center)
    }
}
