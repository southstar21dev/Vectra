package dev.southstar21.vectra.features.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SemesterSelectionTest {
    private fun ev(semester: String, end: String) = SemesterTaggedEvent(semester, end)
    private fun at(ymd: String) = Instant.parse("${ymd}T10:00:00Z")

    private val feed = listOf(
        ev("2024/25/2", "2025-05-16"),
        ev("2025/26/1", "2025-12-12"),
        ev("2025/26/1", "2026-01-20"),
        ev("2025/26/2", "2026-05-15"),
        ev("2026/27/1", "2026-12-11"),
    )

    @Test fun picksInSession() {
        assertEquals("2025/26/1", selectSyncSemester(feed, at("2025-10-15")))
        assertEquals("2025/26/2", selectSyncSemester(feed, at("2026-03-10")))
    }

    @Test fun keepsAutumnInJanuary() = assertEquals("2025/26/1", selectSyncSemester(feed, at("2026-01-12")))
    @Test fun movesToSpringLateJanuary() = assertEquals("2025/26/2", selectSyncSemester(feed, at("2026-01-28")))
    @Test fun springInFebruary() = assertEquals("2025/26/2", selectSyncSemester(feed, at("2026-02-02")))
    @Test fun publishedAutumnInJuly() = assertEquals("2026/27/1", selectSyncSemester(feed, at("2026-07-10")))

    @Test fun staysOnEndedCurrentWhenAutumnNotPublished() {
        val f = feed.filter { it.semester != "2026/27/1" }
        assertEquals("2025/26/2", selectSyncSemester(f, at("2026-07-10")))
    }

    @Test fun nearestUpcomingWhenCurrentMissing() {
        val f = listOf(ev("2026/27/1", "2026-12-11"), ev("2027/28/1", "2027-12-10"))
        assertEquals("2026/27/1", selectSyncSemester(f, at("2026-07-10")))
    }

    @Test fun nullForOnlyPast() = assertNull(selectSyncSemester(listOf(ev("2024/25/2", "2025-05-16")), at("2025-10-15")))

    @Test fun neverReplacesStoredWithOlder() {
        assertEquals(
            "2026/27/1",
            selectSyncSemester(feed.filter { it.semester != "2026/27/1" } + ev("2026/27/1", "2026-12-11"), at("2026-07-01"), "2026/27/1"),
        )
        val withoutAutumn = feed.filter { it.semester != "2026/27/1" }
        assertNull(selectSyncSemester(withoutAutumn, at("2026-07-10"), "2026/27/1"))
        assertEquals("2025/26/2", selectSyncSemester(feed, at("2026-02-02"), "2025/26/1"))
    }

    @Test fun newestStoredReadsOnlyNeptun() {
        fun e(sem: String, src: String) = ScheduleEvent(id = sem + src, semester = sem, source = src)
        assertEquals(
            "2025/26/2",
            newestStoredSemester(listOf(e("2025/26/1", "neptun"), e("2026/27/1", "manual"), e("2025/26/2", "neptun"))),
        )
        assertNull(newestStoredSemester(emptyList()))
    }
}
