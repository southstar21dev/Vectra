package dev.southstar21.vectra.features.schedule

/*
 * Exam classification shared by the ICS importer and the UI.
 * Neptun marks Hungarian exams with a "- Vizsga" suffix, but English titles name
 * themselves ("Elements of Accounting Midterm Exam"); a suffix-only test drew a
 * CLASS badge and offered check-in for a one-off exam.
 */

private val LeadingDash = Regex("""^-\s*""")
private val VizsgaSuffix = Regex("""-\s*Vizsga\s*$""", RegexOption.IGNORE_CASE)
private val Parenthetical = Regex("""\(\s*(?:-\s*)?([^)]+)\)""")
private val ExamWord = Regex("""(?<![A-Za-z0-9_])exam(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)

/** Exam-type parenthetical Neptun puts where a class would carry a course code. */
private val ExamTypeParenthetical = Regex("^(írásbeli|szóbeli|irasbeli|szobeli|written|oral)$", RegexOption.IGNORE_CASE)

/** Whether a parenthetical is an exam type ("Írásbeli") rather than a course code. */
fun isExamTypeParenthetical(value: String): Boolean =
    ExamTypeParenthetical.matches(value.trim().replace(LeadingDash, "").trim())

/**
 * Hungarian midterm markers: "zárthelyi" and its abbreviation "ZH" (whole token
 * only). The looser "évközi" also names a grading form, so it is not a marker.
 */
private val HungarianMidterm = listOf(
    Regex("z[aá]rthelyi", RegexOption.IGNORE_CASE),
    Regex("""(?<![A-Za-z0-9_])zh\d*(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE),
    Regex("""p[oó]t\s*-?\s*zh\d*(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE),
)

/** Whether a Neptun summary describes an exam rather than a recurring class. */
fun isExamSummary(summary: String?): Boolean {
    if (summary.isNullOrEmpty()) return false
    if (VizsgaSuffix.containsMatchIn(summary)) return true
    val parenthetical = Parenthetical.find(summary)
    if (parenthetical != null && ExamTypeParenthetical.matches(parenthetical.groupValues[1].trim())) return true
    if (HungarianMidterm.any { it.containsMatchIn(summary) }) return true
    return ExamWord.containsMatchIn(summary)
}

/**
 * Whether a stored event is an exam. Rows imported before the English-title
 * check still say 'class', so the title is the fallback; an explicit non-class
 * type ('other') is never overridden.
 */
fun isExamEvent(eventType: String?, courseName: String?): Boolean {
    val type = eventType.orEmpty().lowercase()
    if (type == "exam") return true
    if (type.isNotEmpty() && type != "class") return false
    return isExamSummary(courseName.orEmpty())
}

fun ScheduleEvent.isExam(): Boolean = isExamEvent(eventType, courseName)
