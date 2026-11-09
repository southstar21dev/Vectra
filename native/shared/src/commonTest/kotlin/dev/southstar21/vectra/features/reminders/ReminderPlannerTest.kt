package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.platform.ReminderChannel
import dev.southstar21.vectra.core.time.formatBudapestHm
import dev.southstar21.vectra.features.schedule.ScheduleEvent
import dev.southstar21.vectra.features.schedule.scheduleEvent
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderPlannerTest {
    /** Monday 9 November 2026. */
    private val monday = LocalDate(2026, 11, 9)
    private fun at(time: String) = budapestMs(monday, time)!!

    private val tram6 = CommuteLeg("t1", "BKK_F01", "Blaha Lujza tér", "BKK_3060", "6", "TRAM", "FFD800", "000000", "Móricz Zsigmond körtér")
    private val m4 = CommuteLeg("t2", "BKK_F02", "Móricz Zsigmond körtér", "BKK_5400", "M4", "SUBWAY", "4CA22F", "FFFFFF", "Keleti pályaudvar")
    private val everything = ReminderPrefs(beforeClass = true, nextClass = true, toUni = listOf(tram6), backHome = listOf(m4))

    /** The canvas mockup's Monday: a lecture, two back-to-back seminars, a break, then a lab. */
    private val day = listOf(
        scheduleEvent(id = "ds", courseName = "Data Structures", startTime = "10:00", endTime = "11:30", attendanceType = "lecture").copy(location = "B-204"),
        scheduleEvent(id = "alg", courseName = "Algorithms", startTime = "11:45", endTime = "13:15", attendanceType = "seminar").copy(location = "A-101"),
        scheduleEvent(id = "la", courseName = "Linear Algebra", startTime = "13:15", endTime = "14:45", attendanceType = "seminar").copy(location = "C-312"),
        scheduleEvent(id = "db", courseName = "Databases", startTime = "16:00", endTime = "17:30", attendanceType = "lab").copy(location = "E-05"),
    )

    private fun plan(events: List<ScheduleEvent>, prefs: ReminderPrefs, now: Long = at("06:00")) =
        planReminders(expandSessions(events, emptyList(), false, monday, 1), prefs, now)

    @Test fun mockupDay() {
        val out = plan(day, everything)
        assertEquals(
            listOf("09:00" to "rem:before:ds", "11:25" to "rem:next:alg", "13:10" to "rem:next:la", "14:40" to "rem:next:db", "17:30" to "rem:home:db"),
            out.map { formatBudapestHm(it.atMs) to it.id.substringBefore('@') },
        )
        val before = out.first()
        assertEquals("Data Structures in 1 hour", before.title)
        assertEquals("Room B-204 · Lecture · 10:00", before.body)
        assertEquals(ReminderChannel.Commute, before.channel)
        assertEquals(listOf("Tram 4 / 6"), before.departures.map { it.lineLabel })
        assertEquals("Next: Linear Algebra at 13:15", out[2].title)
        assertEquals("Room C-312 · Seminar · right after", out[2].body)
        assertEquals("Room E-05 · Lab · 1 h 15 min break", out[3].body)
        assertEquals("Done for today, heading home?", out[4].title)
        assertEquals(listOf("M4"), out[4].departures.map { it.lineLabel })
    }

    @Test fun longBreakIsATripHomeAndBack() {
        val events = listOf(
            scheduleEvent(id = "a", courseName = "Morning", startTime = "08:00", endTime = "09:30", attendanceType = "lecture"),
            scheduleEvent(id = "b", courseName = "Afternoon", startTime = "14:00", endTime = "15:30", attendanceType = "lecture"),
        )
        val out = plan(events, everything, now = at("05:00"))
        assertEquals(listOf("rem:before:a", "rem:home:a", "rem:before:b", "rem:home:b"), out.map { it.id.substringBefore('@') })
        assertEquals("Break until 14:00, heading home?", out[1].title)
        assertEquals(at("13:00"), out[2].atMs)
    }

    @Test fun switchedOffTypesGetNoClassReminders() {
        val out = plan(day, everything.copy(seminars = false))
        val ids = out.map { it.id.substringBefore('@') }
        assertTrue("rem:next:alg" !in ids && "rem:next:la" !in ids)
        // Without the seminars the lab is a trip of its own: home after the lecture, back for the lab.
        assertEquals(listOf("rem:before:ds", "rem:home:ds", "rem:before:db", "rem:home:db"), ids)
    }

    @Test fun switchedOffTypesGetNoCommuteRemindersEither() {
        val lecturesOnly = day.filter { it.id == "ds" }
        assertTrue(plan(lecturesOnly, everything.copy(lectures = false)).isEmpty())
        assertTrue(plan(lecturesOnly, ReminderPrefs(lectures = false, toUni = listOf(tram6), backHome = listOf(m4))).isEmpty())
        val home = plan(day, everything.copy(labs = false)).last()
        assertEquals("rem:home:la", home.id.substringBefore('@'))
        assertEquals("Linear Algebra ended at 14:45", home.body)
    }

    @Test fun commuteOnlyStillSaysWhenToLeave() {
        val out = plan(day, ReminderPrefs(toUni = listOf(tram6)))
        assertEquals(1, out.size)
        assertEquals("Leave for Data Structures", out.single().title)
        assertEquals(1, out.single().departures.size)
    }

    @Test fun cancelledClassesAndPastRemindersAreDropped() {
        val sessions = expandSessions(day, emptyList(), false, monday, 1) { e, _ -> e.id == "ds" }
        val out = planReminders(sessions, everything, at("12:00"))
        assertNull(out.firstOrNull { it.id.startsWith("rem:before:ds") })
        assertTrue(out.all { it.atMs > at("12:00") })
    }

    @Test fun labels() {
        assertEquals("in 30 min", leadLabel(30))
        assertEquals("in 1 hour", leadLabel(60))
        assertEquals("1 h 15 min", durationLabel(75))
        assertEquals("M4", m4.lineLabel)
        assertEquals("Tram 4 / 6", tram6.lineLabel)
        assertEquals("H5", tram6.copy(routeId = "BKK_H5", routeShortName = "H5", routeType = "SUBURBAN_RAILWAY").lineLabel)
        assertEquals("Bus 7", tram6.copy(routeId = "BKK_0070", routeShortName = "7", routeType = "BUS").lineLabel)
    }

    @Test fun everyPickedLineRidesAlong() {
        val m3 = CommuteLeg("t3", "BKK_F03", "Kálvin tér", "BKK_5300", "M3", "SUBWAY", "005CA5", "FFFFFF", "Újpest-központ")
        val out = plan(day, ReminderPrefs(toUni = listOf(tram6, m4), backHome = listOf(m4, m3)))
        assertEquals(listOf("Tram 4 / 6", "M4"), out.first().departures.map { it.lineLabel })
        assertEquals(listOf("M4", "M3"), out.last().departures.map { it.lineLabel })
    }

    @Test fun departuresOffDropsEveryLine() {
        val out = plan(day, everything.copy(departures = false))
        assertTrue(out.all { it.departures.isEmpty() })
        assertTrue(out.none { it.id.startsWith("rem:home:") })
    }

    @Test fun pairedLinesReadAsOne() {
        val t28 = CommuteLeg("t4", "BKK_F04", "Teleki László tér", "BKK_3280", "28", "TRAM", "FFD800", "000000", "Blaha Lujza tér M")
        val t28a = t28.copy(trackerId = "t5", routeId = "BKK_3281", routeShortName = "28A")
        assertEquals("Tram 28 / 28A", t28a.lineLabel)
        assertEquals(t28.pickKey, t28a.pickKey)
        val out = plan(day, ReminderPrefs(toUni = listOf(t28, t28a)))
        assertEquals(listOf("Tram 28 / 28A"), out.single().departures.map { it.lineLabel })
    }
}
