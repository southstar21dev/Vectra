package dev.southstar21.vectra.features.schedule

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The schedule and the course tracker judge courses by nearly the same rules. This pins
 * where they agree, and the three places they deliberately don't.
 */
class CourseRulesTest {
    private class Variant(val rules: CourseRules, val frequencyFromString: Boolean) {
        fun classLabel(code: String?) = rules.classKind(code)?.label
        fun title(name: String?) = rules.baseTitle(name)
        fun code(code: String?) = getBaseCourseCode(code)
        fun key(e: ScheduleEvent) = rules.courseKey(e.courseCode, e.courseName, e.semester)
        fun same(a: String?, b: String?, c: String?, d: String?) = rules.isSameCourse(a, b, c, d)
        fun official(events: List<ScheduleEvent>) = rules.officialSemesterStart(events)
        fun intensive(events: List<ScheduleEvent>) = rules.intensiveCourseKeys(events)
        fun intensiveCourse(events: List<ScheduleEvent>, start: String?) = isIntensiveCourseEvents(events, start)
        fun frequency(meta: JsonElement?) = getEventFrequency(meta, frequencyFromString).key
        fun weekBefore(anchor: String?) = getWeekBeforeStart(anchor)?.let { it.startStr to it.endStr }
    }

    private val Schedule = Variant(ScheduleCourses, frequencyFromString = false)
    private val Tracker = Variant(TrackerCourses, frequencyFromString = true)

    private fun event(
        code: String = "",
        name: String = "",
        semester: String = "2026/27/1",
        start: String? = null,
        end: String? = null,
        type: String = "",
    ) = ScheduleEvent(id = "$code$name$start", courseCode = code, courseName = name, semester = semester, semesterStart = start, semesterEnd = end, eventType = type)

    /** Whether this platform's own `\b` treats accented letters as word characters. */
    private val unicodeWordBoundary = !Regex("\\bgy\\b").containsMatchIn("légy")

    @Test fun intensiveKeywordsAgree() {
        assertTrue(isIntensiveClass("Intenzív matek", null, null))
        assertTrue(isIntensiveClass("Intenzív matek", null, null))
        assertTrue(isIntensiveClass(null, null, "BLOKK"))
        assertTrue(isIntensiveClass(null, null, "BLOKK"))
        assertFalse(isIntensiveClass("Matek", "ABC", "lecture"))
        assertFalse(isIntensiveClass("Matek", "ABC", "lecture"))
    }

    @Test fun theWeekBeforeTheStartAgrees() {
        for (anchor in listOf("2026-02-09", "2026-02-15", "2026-03-01")) {
            assertEquals(Schedule.weekBefore(anchor), Tracker.weekBefore(anchor))
        }
        assertEquals("2026-02-02" to "2026-02-08", Schedule.weekBefore("2026-02-15"))
        for (bad in listOf(null, "", "soon", "2026-02-31")) {
            assertNull(Schedule.weekBefore(bad))
            assertNull(Tracker.weekBefore(bad))
        }
    }

    @Test fun classTypesAgreeOnPlainCodes() {
        val cases = mapOf(
            "E01" to "Lecture", "G02" to "Seminar", "L03" to "Lab", "7SO20NGMA01M-G01" to "Seminar",
            "INTENSIVE" to "Intensive", "ELŐADÁS" to "Lecture", "GYAKORLAT" to "Seminar", "EA" to "Lecture", "GY" to "Seminar",
            "LABOR" to "Lab", "ABC-E" to "Lecture", "G12X" to "Seminar", "XYZ" to null, "" to null,
        )
        for ((code, label) in cases) {
            assertEquals(label, Schedule.classLabel(code), code)
            assertEquals(label, Tracker.classLabel(code), code)
        }
        assertNull(Schedule.classLabel(null))
        assertNull(Tracker.classLabel(null))
    }

    @Test fun titlesCodesAndKeysAgreeOnUnaccentedNames() {
        val names = listOf("Linear Algebra Lecture", "Linear Algebra - G01", "Physics (seminar)", "Eloadas", "", "   ", "Analízis gyakorlat")
        for (n in names) assertEquals(Schedule.title(n), Tracker.title(n), n)
        assertEquals("linear algebra", Schedule.title("Linear Algebra Lecture"))
        assertEquals("", Schedule.title(null))
        assertEquals("", Tracker.title(null))
        for (c in listOf("7SO20NGMA01M-G01", "E01", "ABC_L2", "", "a.b-c/E")) assertEquals(Schedule.code(c), Tracker.code(c), c)

        val events = listOf(
            event("7SO20NGMA01M-G01", "Algebra Seminar"),
            event("E01", "Algebra Lecture"),
            event("", "Physics"),
            event("", ""),
            event("AB", ""),
        )
        for (e in events) assertEquals(Schedule.key(e), Tracker.key(e))
        assertEquals("7so20ngma01m_2026-27-1", Schedule.key(events[0]))
        assertEquals("algebra_2026-27-1", Schedule.key(events[1]))
        assertEquals("unknown_2026-27-1", Schedule.key(events[3]))
        assertEquals(normalizeCourseKey(" A b ", "2026/1"), normalizeCourseKey(" A b ", "2026/1"))
    }

    @Test fun sameCourseAgrees() {
        val pairs = listOf(
            listOf("Algebra", "ABCD-E01", "algebra", null) to true,
            listOf("Algebra Lecture", null, "Algebra Seminar", null) to true,
            listOf("Algebra", "ABCD-E01", "Algebra II", "ABCD-G02") to true,
            listOf("Physics", "ABCD-E01", "Algebra", "ABCD-G02") to false,
            listOf("", "E01", "", "E01") to false,
            listOf(null, null, null, null) to false,
        )
        for ((p, want) in pairs) {
            assertEquals(want, Schedule.same(p[0], p[1], p[2], p[3]), p.toString())
            assertEquals(want, Tracker.same(p[0], p[1], p[2], p[3]), p.toString())
        }
    }

    @Test fun timesAgree() {
        for ((given, want) in listOf("8:00" to "08:00", "08:5" to "08:05", "24:00" to null, "x" to null, "" to null, null to null)) {
            assertEquals(want, normalizeTime(given))
            assertEquals(want, normalizeTime(given))
        }
    }

    @Test fun theOfficialStartAndIntensiveCoursesAgreeOnCleanData() {
        val events = listOf(
            event("BLOCKED", "Pre week", start = "2026-02-02", end = "2026-02-06"),
            event("ABCD-E01", "Algebra", start = "2026-02-09", end = "2026-05-15"),
            event("ABCD-G01", "Algebra", start = "2026-02-10", end = "2026-05-16"),
        )
        assertEquals("2026-02-09", Schedule.official(events))
        assertEquals("2026-02-09", Tracker.official(events))
        assertEquals(setOf("blocked_2026-27-1"), Schedule.intensive(events))
        assertEquals(setOf("blocked_2026-27-1"), Tracker.intensive(events))
        assertTrue(Schedule.intensiveCourse(events.take(1), "2026-02-09"))
        assertTrue(Tracker.intensiveCourse(events.take(1), "2026-02-09"))
        assertFalse(Schedule.intensiveCourse(events.drop(1), "2026-02-09"))
        assertFalse(Tracker.intensiveCourse(events.drop(1), "2026-02-09"))
        assertNull(Schedule.official(emptyList()))
        assertNull(Tracker.official(emptyList()))
    }

    @Test fun onlyTheTrackerUsesAsciiWordBoundaries() {
        // `gy` sits next to `é`. Tracker keys must not depend on the platform's regex engine.
        assertEquals("lé", Tracker.title("Légy"))
        assertEquals(if (unicodeWordBoundary) "légy" else "lé", Schedule.title("Légy"))
        assertEquals("Lecture", Tracker.classLabel("ÁEA"))
        assertEquals(if (unicodeWordBoundary) null else "Lecture", Schedule.classLabel("ÁEA"))

        val accented = event("", "Légy")
        assertEquals("l_2026-27-1", Tracker.key(accented))
        assertEquals(if (unicodeWordBoundary) "l-gy_2026-27-1" else "l_2026-27-1", Schedule.key(accented))
        if (unicodeWordBoundary) assertNotEquals(Schedule.key(accented), Tracker.key(accented))
    }

    @Test fun onlyTheTrackerReadsFrequencyFromAJsonString() {
        val asObject = JsonObject(mapOf("frequency" to JsonPrimitive("even")))
        assertEquals("even", Schedule.frequency(asObject))
        assertEquals("even", Tracker.frequency(asObject))
        val asString = JsonPrimitive("""{"frequency":"odd"}""")
        assertEquals("weekly", Schedule.frequency(asString))
        assertEquals("odd", Tracker.frequency(asString))
        assertEquals("weekly", Schedule.frequency(null))
        assertEquals("weekly", Tracker.frequency(null))
    }

    @Test fun onlyTheTrackerDropsACourseWithAnUnreadableDate() {
        val events = listOf(event("ABCD", "Algebra", start = "2026-02-31"))
        assertEquals("2026-02-31", Schedule.official(events))
        assertNull(Tracker.official(events))
    }
}
