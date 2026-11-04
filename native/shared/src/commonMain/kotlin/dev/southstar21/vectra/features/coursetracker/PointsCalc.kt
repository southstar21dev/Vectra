package dev.southstar21.vectra.features.coursetracker

import dev.southstar21.vectra.core.time.parseIsoDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------
// Points
// ---------------------------------------------------------------------------

data class PointsSummary(
    val totalEarned: Double,
    val totalMax: Double,
    val scoredMax: Double,
    /** Points earned on the scored (completed) assessments only. */
    val scoredEarned: Double,
    val remainingMax: Double,
    /** Success rate on the work graded so far: scored earned / scored max. */
    val currentPercentage: Double,
    /** Share of the COURSE earned so far — the number the grade is estimated from. */
    val overallPercentage: Double,
    val estimatedGrade: Int,
    val hasFailedMustPass: Boolean,
    val failedMustPassItems: List<AssessmentItem>,
)

fun estimateGrade(percentage: Double, scale: List<GradingScaleTier> = DefaultGradingScale): Int {
    for (tier in scale.sortedByDescending { it.minPercentage }) {
        if (percentage >= tier.minPercentage) return tier.grade
    }
    return 1
}

/** Explicitly completed, or (when unset) any points earned. */
val AssessmentItem.isScored: Boolean get() = isCompleted ?: (earnedPoints > 0)

fun calculatePointsSummary(
    assessments: List<AssessmentItem>,
    useWeightedCategories: Boolean = false,
    gradingScale: List<GradingScaleTier>? = null,
    totalPoints: Double? = null,
): PointsSummary {
    val scale = gradingScale ?: DefaultGradingScale
    val courseTotal = if (totalPoints != null && totalPoints > 0) totalPoints else DefaultCourseTotalPoints
    var totalEarned = 0.0
    var totalMax = 0.0
    var scoredMax = 0.0
    var scoredEarned = 0.0
    val failed = mutableListOf<AssessmentItem>()
    for (item in assessments) {
        totalEarned += item.earnedPoints
        totalMax += item.maxPoints
        val scored = item.isScored
        if (scored) {
            scoredMax += item.maxPoints
            scoredEarned += item.earnedPoints
        }
        if (item.isMustPass == true && scored) {
            val minRequired = item.minPassPoints ?: (item.maxPoints * 0.5)
            if (item.earnedPoints < minRequired) failed += item
        }
    }
    val remainingMax = max(0.0, totalMax - scoredMax)
    // Numerator and denominator cover the same items, so typed-but-incomplete
    // points never push the rate past 100%.
    val currentPercentage = if (scoredMax > 0) scoredEarned / scoredMax * 100 else 0.0
    // Share of the whole course: one 18/20 quiz on a 100-point course is 18%.
    val overall = if (useWeightedCategories) {
        assessments.filter { it.maxPoints > 0 && it.weightPercent != null }
            .sumOf { it.earnedPoints / it.maxPoints * it.weightPercent!! }
    } else if (courseTotal > 0) totalEarned / courseTotal * 100 else 0.0
    val hasFailed = failed.isNotEmpty()
    return PointsSummary(
        totalEarned, totalMax, scoredMax, scoredEarned, remainingMax, currentPercentage, overall,
        if (hasFailed) 1 else estimateGrade(overall, scale),
        hasFailed, failed,
    )
}

fun calculatePointsToTarget(totalEarned: Double, totalMax: Double, targetGrade: Int, gradingScale: List<GradingScaleTier>? = null): Int? {
    val tier = (gradingScale ?: DefaultGradingScale).firstOrNull { it.grade == targetGrade } ?: return null
    val needed = tier.minPercentage / 100 * totalMax - totalEarned
    return max(0.0, ceil(needed)).toInt()
}

// ---------------------------------------------------------------------------
// Grades
// ---------------------------------------------------------------------------

/** What each grade means on the Hungarian five-point scale. */
val GradeNames: Map<Int, String> = mapOf(5 to "Excellent", 4 to "Good", 3 to "Satisfactory", 2 to "Pass", 1 to "Fail")

/** The grade a percentage earns on [scale] (1 when it clears no cutoff). */
fun gradeForPercent(percent: Double, scale: List<GradingScaleTier>): Int =
    scale.filter { it.grade >= 2 && percent >= it.minPercentage }.fold(1) { best, t -> if (t.grade > best) t.grade else best }

// ---------------------------------------------------------------------------
// Numeric input
// ---------------------------------------------------------------------------

/**
 * Hungarian keyboards type decimal commas ("12,5"): treat comma and dot alike,
 * keep one decimal point, and leave clamping to the commit step.
 */
fun normalizeDecimalInput(raw: String): String {
    val cleaned = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
    val parts = cleaned.split('.')
    return if (parts.size > 2) "${parts[0]}.${parts.drop(1).joinToString("")}" else cleaned
}

/** Null for empty input, a lone separator, or anything non-finite. */
fun parseFiniteNumber(raw: String): Double? {
    val normalized = normalizeDecimalInput(raw).trim()
    if (normalized.isEmpty() || normalized == ".") return null
    // Only digits and one point remain, so "12." and ".5" parse as 12.0 and 0.5.
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

fun clampNumber(value: Double, min: Double, max: Double): Double = min(max, max(min, value))

/**
 * Grade cutoffs must be monotonic (5 ≥ 4 ≥ 3 ≥ 2 ≥ 1); an out-of-order scale
 * silently misgrades every estimate, so the write is blocked with a reason.
 */
fun validateScaleOrder(scale: List<GradingScaleTier>): String? {
    fun minOf(grade: Int) = scale.firstOrNull { it.grade == grade }?.minPercentage ?: 0.0
    for ((higher, lower) in listOf(5 to 4, 4 to 3, 3 to 2, 2 to 1)) {
        if (minOf(higher) < minOf(lower)) {
            return "Grade $higher cutoff must be at least the Grade $lower cutoff (${formatNumber(minOf(lower))}%)."
        }
    }
    return null
}

/** Whole numbers without a decimal point: `12`, `12.5`. */
fun formatNumber(value: Double): String =
    if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

// ---------------------------------------------------------------------------
// Session dates
// ---------------------------------------------------------------------------

private val WeekdaysShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MonthsShort = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Today" stays literal; everything else reads day-month like a timetable. */
fun formatSessionDate(iso: String, todayIso: String): String = if (iso == todayIso) "Today" else formatSessionDateFull(iso)

/** en-GB `Tue 15 Sept`-style reading: weekday, day, short month. */
fun formatSessionDateFull(iso: String): String {
    val d = parseIsoDate(iso) ?: return iso
    // en-GB spells September "Sept".
    val month = if (d.month.ordinal == 8) "Sept" else MonthsShort[d.month.ordinal]
    return "${WeekdaysShort[d.dayOfWeek.ordinal]} ${d.day} $month"
}

/** Month/day/weekday parts of a date tile (month always three letters, upper-case). */
data class TileParts(val month: String, val day: String, val weekday: String)

fun tileParts(iso: String?): TileParts? {
    val d = parseIsoDate(iso ?: return null) ?: return null
    return TileParts(MonthsShort[d.month.ordinal].uppercase(), d.day.toString(), WeekdaysShort[d.dayOfWeek.ordinal])
}
