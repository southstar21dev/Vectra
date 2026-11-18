package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.time.ymd
import dev.southstar21.vectra.features.reminders.budapestMs
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The widget pickers' sample: a full day in the week of [today], seen at 10:05 on its Wednesday. */
class WidgetSample(val snapshot: WidgetSnapshot, val nowMs: Long, val today: String)

fun widgetSample(today: LocalDate): WidgetSample {
    val monday = mondayOf(today)
    val wednesday = monday.plus(DatePeriod(days = 2))
    fun at(day: LocalDate, time: String) = budapestMs(day, time) ?: 0L
    fun s(title: String, room: String, kind: WidgetKind, day: LocalDate, from: String, to: String, teacher: String = "", group: String = "") =
        WidgetSession(title, room, kind, at(day, from), at(day, to), day.ymd(), teacher = teacher, group = group)
    val d = { n: Int -> monday.plus(DatePeriod(days = n)) }
    val sessions = listOf(
        s("Calculus II", "B 012", WidgetKind.Lecture, d(0), "08:00", "09:30"),
        s("Microeconomics", "E 201", WidgetKind.Seminar, d(0), "12:00", "13:30", group = "G04"),
        s("Data Structures", "A 105", WidgetKind.Lecture, d(1), "09:30", "11:00"),
        s("Statistics Lab", "QB 112", WidgetKind.Lab, d(1), "13:00", "15:00"),
        s("Calculus II", "B 012", WidgetKind.Lecture, wednesday, "08:00", "09:30", "Dr. Tóth Anna"),
        s("Data Structures", "A 105", WidgetKind.Lecture, wednesday, "09:30", "11:00", "Dr. Kovács Péter"),
        s("Microeconomics", "E 201", WidgetKind.Seminar, wednesday, "12:30", "14:00", "Szabó Eszter", "G04"),
        s("Statistics Lab", "QB 112", WidgetKind.Lab, wednesday, "14:15", "15:45", "Nagy Gábor", "L2"),
        s("Problem set 3", "", WidgetKind.Task, wednesday, "23:58", "23:59"),
        s("Algorithms", "B 012", WidgetKind.Lecture, d(3), "10:00", "11:30"),
        s("Microeconomics", "E 201", WidgetKind.Seminar, d(3), "14:00", "15:30"),
        s("Linear Algebra", "Aula", WidgetKind.Exam, d(4), "09:00", "11:00"),
        s("Data Structures", "A 105", WidgetKind.Lecture, d(4), "13:00", "14:30"),
    )
    check(wednesday.dayOfWeek == DayOfWeek.WEDNESDAY)
    return WidgetSample(WidgetSnapshot(generatedAtMs = at(wednesday, "07:00"), signedIn = true, sessions = sessions), at(wednesday, "10:05"), wednesday.ymd())
}
