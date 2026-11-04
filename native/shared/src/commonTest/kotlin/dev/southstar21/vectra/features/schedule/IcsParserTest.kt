package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsParserTest {
    private fun cal(vararg events: List<String>) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + events.flatMap { listOf("BEGIN:VEVENT") + it + "END:VEVENT" } + "END:VCALENDAR").joinToString("\n")

    private fun ev(uid: String, start: String, end: String, summary: String, location: String? = null) =
        listOfNotNull("UID:$uid", "DTSTART:$start", "DTEND:$end", "SUMMARY:$summary", location?.let { "LOCATION:$it" })

    @Test fun includesExamsByDefault() {
        val ics = cal(
            ev("exam-1", "20261001T100000", "20261001T120000", "Accounting (Írásbeli) - Dr. Smith - Vizsga", "A.101"),
            ev("exam-2", "20261015T100000", "20261015T120000", "Accounting (Írásbeli) - Dr. Smith - Vizsga", "A.101"),
            ev("class-1", "20261001T100000", "20261001T120000", "Accounting ( - ACCT101-E01) - Dr. Smith - Tanóra", "A.101"),
        )
        val result = parseIcsContent(ics)
        val exams = result.valid.filter { it.eventType == "exam" }
        val classes = result.valid.filter { it.eventType == "class" }
        assertEquals(2, exams.size)
        assertEquals(listOf("2026-10-01" to "2026-10-01", "2026-10-15" to "2026-10-15"), exams.map { it.semesterStart to it.semesterEnd })
        assertEquals(1, classes.size)
        assertTrue(parseIcsContent(ics, includeExams = false).valid.all { it.eventType != "exam" })
    }

    @Test fun sameDayExamSlotsDistinct() {
        val ics = cal(
            ev("exam-am", "20261001T090000", "20261001T100000", "Accounting (Írásbeli) - Dr. Smith - Vizsga"),
            ev("exam-pm", "20261001T130000", "20261001T140000", "Accounting (Írásbeli) - Dr. Smith - Vizsga"),
        )
        assertEquals(2, parseIcsContent(ics).valid.size)
    }

    private fun inRange(e: ParsedIcsEvent, start: String, end: String): Boolean {
        if (e.semesterStart != null && end < e.semesterStart) return false
        if (e.semesterEnd != null && start > e.semesterEnd) return false
        return true
    }

    @Test fun irregularWeeksSplit() {
        val s = "Organisational interpersonal skills dev ( - 7SO20NGMA01M-G04) - Dr. Smith - Tanóra"
        val ics = cal(
            ev("g04-session-1", "20260915T080000", "20260915T093000", s, "C.101"),
            ev("g04-session-2", "20261013T080000", "20261013T093000", s, "C.101"),
            ev("g04-session-3", "20261103T080000", "20261103T093000", s, "C.101"),
            ev("g04-session-4", "20261201T080000", "20261201T093000", s, "C.101"),
        )
        val g04 = parseIcsContent(ics).valid.filter { it.courseName.lowercase().contains("organisational") }
        assertEquals(4, g04.size)
        assertEquals(
            listOf("2026-09-15", "2026-10-13", "2026-11-03", "2026-12-01").map { it to it },
            g04.map { it.semesterStart to it.semesterEnd },
        )
        assertEquals(0, g04.count { inRange(it, "2026-09-21", "2026-09-27") })
        assertEquals(1, g04.count { inRange(it, "2026-09-14", "2026-09-20") })
    }

    @Test fun continuousWeeklyGrouped() {
        val s = "Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra"
        val ics = cal(*listOf("0908", "0915", "0922", "0929", "1006").mapIndexed { i, d ->
            ev("calc-$i", "2026${d}T080000", "2026${d}T093000", s, "Auditorium")
        }.toTypedArray())
        val calc = parseIcsContent(ics).valid.filter { it.courseName.contains("Calculus") }
        assertEquals(1, calc.size)
        assertEquals("2026-09-08", calc[0].semesterStart)
        assertEquals("2026-10-06", calc[0].semesterEnd)
    }

    @Test fun intensiveBlockCourse() {
        val ics = cal(ev("g10-1", "20260907T080000", "20260907T093000", "Organisational interpersonal skills dev ( - G10) - Dr. Smith - Tanóra", "C.101"))
        val g10 = parseIcsContent(ics).valid.filter { it.courseCode.contains("G10") }
        assertEquals(1, g10.size)
        assertEquals("2026-09-07", g10[0].semesterStart)
        assertEquals("2026-09-07", g10[0].semesterEnd)
    }

    @Test fun codesWithOrWithoutHyphen() {
        val ics = cal(
            ev("evt-1", "20260915T080000", "20260915T093000", "Organisational interpersonal skills dev (G04) - Dr. Smith - Tanóra", "C.101"),
            ev("evt-2", "20260916T080000", "20260916T093000", "Microeconomics ( - ECON101-E02) - Dr. Jones - Tanóra", "C.202"),
        )
        val v = parseIcsContent(ics).valid
        assertEquals("G04", v[0].courseCode)
        assertEquals("Organisational interpersonal skills dev", v[0].courseName)
        assertEquals("Dr. Smith", v[0].instructor)
        assertEquals("ECON101-E02", v[1].courseCode)
        assertEquals("Microeconomics", v[1].courseName)
        assertEquals("Dr. Jones", v[1].instructor)
    }

    @Test fun biWeeklySplit() {
        val s = "Business Ethics ( - ETH101-G01) - Tanóra"
        val ics = cal(
            ev("bi-1", "20260915T100000", "20260915T113000", s, "E.201"),
            ev("bi-2", "20260929T100000", "20260929T113000", s, "E.201"),
            ev("bi-3", "20261013T100000", "20261013T113000", s, "E.201"),
        )
        val bi = parseIcsContent(ics).valid.filter { it.courseName.contains("Business Ethics") }
        assertEquals(listOf("2026-09-15", "2026-09-29", "2026-10-13").map { it to it }, bi.map { it.semesterStart to it.semesterEnd })
    }

    @Test fun englishTitledExams() {
        val ics = cal(
            ev("en-exam", "20261110T095000", "20261110T112000", "Elements of Accounting Midterm Exam - Feladat - Tanóra"),
            ev("oral-exam", "20261112T100000", "20261112T110000", "Legal Studies (Szóbeli) - Dr. Kiss - Vizsga"),
            ev("plain-class", "20261103T080000", "20261103T093000", "Calculus I ( - MATH101-E01) - Prof. Euler - Tanóra"),
        )
        val v = parseIcsContent(ics).valid
        val exam = v.first { it.courseName.contains("Midterm Exam") }
        val oral = v.first { it.courseName.startsWith("Legal Studies") }
        val plain = v.first { it.courseName == "Calculus I" }
        assertEquals("exam", exam.eventType)
        assertEquals("Feladat", exam.instructor)
        assertEquals("2026-11-10" to "2026-11-10", exam.semesterStart to exam.semesterEnd)
        assertEquals("exam", oral.eventType)
        assertEquals("class", plain.eventType)
        assertEquals("MATH101-E01", plain.courseCode)
    }

    @Test fun codeParentheticalWithSubtitle() {
        val ics = cal(ev("sub-1", "20260922T080000", "20260922T093000", "Data Analysis II. (Probability and Statistics) ( - E01) - Takács Tibor - Tanóra", "E.0.IV.ea."))
        val e = parseIcsContent(ics).valid[0]
        assertEquals("E01", e.courseCode)
        assertEquals("Data Analysis II. (Probability and Statistics)", e.courseName)
        assertEquals("Takács Tibor", e.instructor)
        assertEquals("E.0.IV.ea.", e.location)
    }

    // ---- timestamps and text escapes ----

    private fun wrap(lines: List<String>) = (listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT") + lines + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")

    @Test fun floatingAndBudapestTzid() {
        assertEquals("08:00", parseIcsTimestamp("20251006T080000")?.timeStr)
        assertEquals("08:00", parseIcsTimestamp("DTSTART;TZID=Europe/Budapest:20251006T080000")?.timeStr)
        assertEquals(1, parseIcsTimestamp("DTSTART;TZID=Europe/Budapest:20251006T080000")?.dayOfWeek)
    }

    @Test fun otherTzidsAndUtc() {
        assertEquals("08:00", parseIcsTimestamp("DTSTART;TZID=UTC:20251006T060000")?.timeStr)
        assertEquals("08:00", parseIcsTimestamp("20251006T060000Z")?.timeStr)
        assertEquals("08:00", parseIcsTimestamp("DTSTART;TZID=Central Europe Standard Time:20251006T080000")?.timeStr)
    }

    @Test fun keepsTzidAndUnescapes() {
        val r = parseIcsContent(wrap(listOf(
            "DTSTART;TZID=Europe/Budapest:20251006T080000",
            "DTEND;TZID=Europe/Budapest:20251006T093000",
            "SUMMARY:Calculus I ( - MATH101-E01) - Dr. Example - Tanóra",
            "LOCATION:Building C\\, Room 101",
        )))
        assertEquals("08:00", r.valid[0].startTime)
        assertEquals("09:30", r.valid[0].endTime)
        assertEquals("Building C, Room 101", r.valid[0].location)
    }

    // ---- recurrence, durations and edge cases ----

    private fun calendar(vararg events: List<String>) =
        (listOf("BEGIN:VCALENDAR") + events.flatMap { listOf("BEGIN:VEVENT") + it + "END:VEVENT" } + "END:VCALENDAR").joinToString("\r\n")
    private val CLASS = "SUMMARY:Calculus I ( - MATH101-E01) - Dr. Example - Tanóra"

    @Test fun weeklyRruleCount() {
        val r = parseIcsContent(calendar(listOf(
            "UID:calc", "DTSTART;TZID=Europe/Budapest:20250908T080000", "DTEND;TZID=Europe/Budapest:20250908T093000",
            "RRULE:FREQ=WEEKLY;COUNT=12", CLASS,
        )))
        assertEquals(1, r.valid.size)
        val e = r.valid[0]
        assertEquals(1, e.dayOfWeek)
        assertEquals("08:00", e.startTime)
        assertEquals("09:30", e.endTime)
        assertEquals("2025/26/1", e.semester)
        assertEquals("2025-09-08", e.semesterStart)
        assertEquals("2025-11-24", e.semesterEnd)
    }

    @Test fun dstWallClock() {
        val r = parseIcsContent(calendar(listOf(
            "UID:dst", "DTSTART;TZID=Europe/Budapest:20251020T080000", "DTEND;TZID=Europe/Budapest:20251020T093000",
            "RRULE:FREQ=WEEKLY;UNTIL=20251110T235959Z", CLASS,
        )))
        assertEquals(1, r.valid.size)
        assertEquals("08:00", r.valid[0].startTime)
        assertEquals("2025-11-10", r.valid[0].semesterEnd)
    }

    @Test fun untilIntervalExdate() {
        val r = parseIcsContent(calendar(listOf(
            "UID:biweekly", "DTSTART:20250908T080000", "DTEND:20250908T093000",
            "RRULE:FREQ=WEEKLY;INTERVAL=2;UNTIL=20251020", "EXDATE:20250922T080000", CLASS,
        )))
        assertEquals(listOf("2025-09-08", "2025-10-06", "2025-10-20"), r.valid.map { it.semesterStart })
    }

    @Test fun byDayAndDaily() {
        val weekly = parseIcsContent(calendar(listOf(
            "UID:mw", "DTSTART:20250908T100000", "DTEND:20250908T113000", "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4", CLASS,
        )))
        assertEquals(
            listOf(Triple(1, "2025-09-08", "2025-09-15"), Triple(3, "2025-09-10", "2025-09-17")),
            weekly.valid.map { Triple(it.dayOfWeek, it.semesterStart, it.semesterEnd) },
        )
        val daily = parseIcsContent(calendar(listOf(
            "UID:block", "DTSTART:20250908T080000", "DTEND:20250908T093000", "RRULE:FREQ=DAILY;COUNT=5", CLASS,
        )))
        assertEquals(listOf(1, 2, 3, 4, 5), daily.valid.map { it.dayOfWeek }.sorted())
    }

    @Test fun recurrenceIdOverride() {
        val r = parseIcsContent(calendar(
            listOf("UID:s", "DTSTART:20250908T080000", "DTEND:20250908T093000", "RRULE:FREQ=WEEKLY;COUNT=2", CLASS),
            listOf("UID:s", "RECURRENCE-ID:20250915T080000", "DTSTART:20250915T120000", "DTEND:20250915T133000", CLASS),
        ))
        assertEquals(
            listOf("2025-09-08" to "08:00", "2025-09-15" to "12:00"),
            r.valid.map { it.semesterStart!! to it.startTime }.sortedBy { it.first + it.second },
        )
    }

    @Test fun durationWhenNoDtend() {
        assertEquals(90 * 60_000L, parseIcsDurationMs("PT1H30M"))
        assertEquals(86_400_000L, parseIcsDurationMs("P1D"))
        assertNull(parseIcsDurationMs("P"))
        val r = parseIcsContent(calendar(listOf("DTSTART:20250908T080000", "DURATION:PT1H30M", CLASS)))
        assertEquals("09:30", r.valid[0].endTime)
    }

    @Test fun skipsCancelledAndAllDay() {
        val r = parseIcsContent(calendar(
            listOf("DTSTART:20250908T080000", "DTEND:20250908T093000", "STATUS:CANCELLED", CLASS),
            listOf("DTSTART;VALUE=DATE:20250909", "DTEND;VALUE=DATE:20250910", "SUMMARY:Registration week"),
            listOf("DTSTART:20250910", "DTEND:20250911", "SUMMARY:Dean's day"),
        ))
        assertEquals(0, r.valid.size)
        assertEquals(0, r.errors.size)
    }

    @Test fun midnightClamp() {
        val r = parseIcsContent(calendar(listOf("DTSTART:20250908T220000", "DTEND:20250909T000000", CLASS)))
        assertEquals("22:00", r.valid[0].startTime)
        assertEquals("23:59", r.valid[0].endTime)
    }

    @Test fun quotedTzidWithColon() {
        val info = parseIcsTimestamp("DTSTART;TZID=\"Europe/Budapest:legacy\":20251006T080000")
        assertEquals("2025-10-06", info?.dateStr)
        assertEquals("08:00", info?.timeStr)
        val r = parseIcsContent(calendar(listOf("DTSTART;TZID=\"UTC\":20251006T060000", "DTEND;TZID=\"UTC\":20251006T073000", CLASS)))
        assertEquals("08:00", r.valid[0].startTime)
        assertEquals("09:30", r.valid[0].endTime)
    }

    @Test fun ignoresNestedAlarms() {
        val r = parseIcsContent(calendar(listOf(
            "DTSTART:20250908T080000", "DTEND:20250908T093000", CLASS, "LOCATION:C.101",
            "BEGIN:VALARM", "DESCRIPTION:Reminder", "LOCATION:nowhere", "END:VALARM",
        )))
        assertEquals("C.101", r.valid[0].location)
    }
}
