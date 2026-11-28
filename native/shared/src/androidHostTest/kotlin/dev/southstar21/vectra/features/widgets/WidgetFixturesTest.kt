package dev.southstar21.vectra.features.widgets

import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.time.nowInBudapest
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Runs the cases in iosApp/VectraWidgetsTests/Fixtures/widget-cases.json, the same file the iOS
 * widget tests decode, so the Kotlin and Swift rules are held to one set of expectations.
 */
class WidgetFixturesTest {
    @Serializable
    private class Fixture(val cases: List<Case>)

    @Serializable
    private class Case(val name: String, val nowMs: Long, val snapshot: WidgetSnapshot, val expect: Expect)

    @Serializable
    private class Expect(
        val nextUp: String,
        val stale: Boolean,
        val weekClassCount: Int,
        val title: String? = null,
        val examThisWeek: String? = null,
        val hadToday: Boolean? = null,
    )

    private fun fixture(): Fixture {
        // Gradle runs host tests with the module directory (native/shared) as the working directory.
        val file = File("../iosApp/VectraWidgetsTests/Fixtures/widget-cases.json")
        assertTrue(file.exists(), "missing ${file.absolutePath}")
        return VectraJson.decodeFromString(Fixture.serializer(), file.readText())
    }

    private fun NextUp.label() = when (this) {
        NextUp.NoData -> "noData"
        NextUp.Stale -> "stale"
        is NextUp.InClass -> "inClass"
        is NextUp.Upcoming -> "upcoming"
        is NextUp.ExamSoon -> "examSoon"
        is NextUp.DayDone -> "dayDone"
    }

    @Test
    fun matchesTheSharedExpectations() {
        val cases = fixture().cases
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val today = nowInBudapest(Instant.fromEpochMilliseconds(c.nowMs)).ymd
            val snap = c.snapshot
            val up = snap.nextUp(c.nowMs, today)
            assertEquals(c.expect.nextUp, up.label(), "${c.name}: nextUp")
            assertEquals(c.expect.title, up.featured?.title, "${c.name}: featured title")
            assertEquals(c.expect.stale, snap.isStale(today), "${c.name}: stale")
            assertEquals(c.expect.weekClassCount, snap.week(today).classCount(), "${c.name}: classes this week")
            assertEquals(c.expect.examThisWeek, snap.examThisWeek(today)?.title, "${c.name}: exam this week")
            if (c.expect.hadToday != null) assertEquals(c.expect.hadToday, (up as NextUp.DayDone).hadToday, "${c.name}: hadToday")
        }
    }
}
