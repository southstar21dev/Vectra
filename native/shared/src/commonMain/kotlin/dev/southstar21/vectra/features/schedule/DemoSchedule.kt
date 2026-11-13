package dev.southstar21.vectra.features.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.southstar21.vectra.core.time.nowInBudapest
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The demo account that gets a made-up week of classes (debug builds only). */
private const val DemoScheduleEmail = "transit-demo@test.com"

private val seedLock = Mutex()
private var seeded = false

/** One class, every day of the week: lecture, lab, seminar, a clash, an exam, a long name. */
private class DemoClass(val name: String, val code: String, val teacher: String, val room: String, val start: String, val end: String, val type: String, val color: String)

private val DemoDay = listOf(
    DemoClass("Calculus II", "MATH201", "Dr. Kovács Anna", "Building A 105", "08:00", "09:30", "lecture", "#38BDF8"),
    DemoClass("Data Structures", "INF210", "Nagy Péter", "Lab 3.12", "09:45", "11:15", "lab", "#A78BFA"),
    DemoClass("Microeconomics", "ECO120", "Szabó Eszter", "Room E 201", "11:15", "12:45", "seminar", "#34D399"),
    // Overlaps Microeconomics on purpose: the clash state.
    DemoClass("Statistics", "STA110", "Tóth Gábor", "Room B 014", "12:30", "14:00", "seminar", "#F472B6"),
    DemoClass("Physics midterm exam", "PHY101", "Dr. Varga Lili", "Aula Magna", "14:15", "15:45", "exam", "#FBBF24"),
    DemoClass("Linear Algebra", "MATH150", "Horváth Dániel", "Building A 210", "16:00", "17:30", "lecture", "#60A5FA"),
    DemoClass(
        "Introduction to Machine Learning and Data-Driven Decision Making", "INF420", "Dr. Kiss-Molnár Katalin Erzsébet",
        "Building Q, Room QB-2.27 (computer lab)", "18:10", "20:40", "lab", "#C084FC",
    ),
)

/**
 * Debug builds: the first time [DemoScheduleEmail] opens the dashboard in a run, it gets the same
 * full day of classes on every weekday (so any day and time shows past, current and upcoming
 * classes, breaks and a clash), plus an evening shift and a morning task for the week. Whatever
 * is already there (after a fresh fetch) is left alone.
 */
@Composable
internal fun DemoScheduleSeedEffect(schedule: ScheduleRepository, email: String?, debug: Boolean) {
    if (!debug || email?.lowercase() != DemoScheduleEmail) return
    // One key only: importing the classes changes the events, which must not cancel the seeding.
    LaunchedEffect(schedule) {
        schedule.localReady.first { it }
        seedLock.withLock {
            if (seeded) return@withLock
            seeded = true
            runSuspendCatching { seedDemoSchedule(schedule) }
        }
    }
}

private suspend fun seedDemoSchedule(schedule: ScheduleRepository) {
    schedule.refreshEvents(force = true)
    if (schedule.events.value.isEmpty()) {
        val classes = (0..6).flatMap { day ->
            DemoDay.map { c ->
                NeptunSyncEvent(c.name, c.code, c.teacher, c.room, day, c.start, c.end, c.color, "2026/27/1", "2026-09-01", "2027-06-30", c.type)
            }
        }
        schedule.importNeptunEvents(classes)
    }
    schedule.refreshItems(force = true)
    val have = schedule.itemsState.value.items.mapTo(HashSet()) { it.id }
    val today = LocalDate.parse(nowInBudapest().ymd)
    for (i in 0 until 7) {
        val date = today.plus(DatePeriod(days = i)).toString()
        val shift = ScheduleItem(demoItemId(1, date), ScheduleItem.TYPE_SHIFT, "Café shift", date, "21:00", "23:30", location = "Café Lumen")
        val task = ScheduleItem(demoItemId(2, date), ScheduleItem.TYPE_TASK, "Hand in the lab report", date, "07:30", "07:45", completed = false)
        for (item in listOf(shift, task)) if (item.id !in have) runSuspendCatching { schedule.saveItem(item) }
    }
}

/** A fixed UUID per demo item and day (items need UUID ids), so a second run finds the first's items. */
private fun demoItemId(kind: Int, date: String): String =
    "00000000-0000-4000-8000-000$kind${date.replace("-", "")}"
