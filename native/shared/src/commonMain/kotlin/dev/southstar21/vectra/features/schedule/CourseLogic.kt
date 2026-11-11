package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.designsystem.components.StatusTone
import dev.southstar21.vectra.core.network.VectraJson
import dev.southstar21.vectra.core.time.isYmdString
import dev.southstar21.vectra.core.time.parseIsoDate
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Class-type and course-identity rules, shared by the schedule and the course tracker.
 * Anything that reads or writes tracker data goes through the course tracker's hooks.
 */

/** How often a class meets: every week, or only on even or odd weeks. */
enum class ScheduleFrequency(val key: String) { Weekly("weekly"), Even("even"), Odd("odd") }

/**
 * Read the odd/even-week frequency filter stored in csv_metadata. With [acceptJsonString], metadata
 * stored as a JSON-encoded string counts as well; the schedule itself treats that as weekly.
 */
fun getEventFrequency(meta: JsonElement?, acceptJsonString: Boolean = false): ScheduleFrequency {
    val obj = when {
        meta is JsonObject -> meta
        acceptJsonString && meta is JsonPrimitive ->
            meta.contentOrNull?.let { runCatching { VectraJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        else -> null
    }
    return when ((obj?.get("frequency") as? JsonPrimitive)?.contentOrNull) {
        "even" -> ScheduleFrequency.Even
        "odd" -> ScheduleFrequency.Odd
        else -> ScheduleFrequency.Weekly
    }
}

/** Merge a frequency into csv_metadata (weekly removes the key). */
fun setFrequencyInMetadata(meta: JsonElement?, frequency: ScheduleFrequency?): JsonObject {
    val next = (meta as? JsonObject)?.toMutableMap() ?: mutableMapOf()
    if (frequency == ScheduleFrequency.Even || frequency == ScheduleFrequency.Odd) next["frequency"] = JsonPrimitive(frequency.key)
    else next.remove("frequency")
    return JsonObject(next)
}

private const val AsciiWordChar = "[A-Za-z0-9_]"
private const val AsciiWordBoundary = "(?:(?<=$AsciiWordChar)(?!$AsciiWordChar)|(?<!$AsciiWordChar)(?=$AsciiWordChar))"

/** [pattern] with each `\\b` replaced by an ASCII-only word boundary. */
private fun asciiWordRegex(pattern: String, ignoreCase: Boolean): Regex =
    Regex(pattern.replace("\\b", AsciiWordBoundary), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

/** `\\b` as the platform's regex engine defines it, which varies with accented letters. */
private fun platformWordRegex(pattern: String, ignoreCase: Boolean): Regex =
    Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())

private val IntensiveKeyword = Regex("INTENSIVE|INTENZ[IÍ]V|BLOCK|BLOKK")
private val LabKeyword = Regex("LAB|LABOR|LABORATÓRIUM|LABORATORIUM")
private val CodeSeparators = Regex("""[-_/.\s]+""")
private val LectureToken = Regex("""^E\d*$""")
private val SeminarToken = Regex("""^G\d*$""")
private val LabToken = Regex("""^L\d*$""")
private val LeadingTypeLetter = Regex("""^([GLE])\d+""")
private val TrailingTypeLetter = Regex("""([GLE])\d*$""")
private val GroupToken = Regex("""^([GLE])(\d{1,3})$""")
private val GroupAnywhere = Regex("""([GLE])(\d{1,3})\b""")
private val NonSlugChars = Regex("[^a-z0-9]+")
private val EdgeDashes = Regex("^-+|-+$")
private val Brackets = Regex("""[()\[\]{}]+""")
// Case-sensitive on purpose: Kotlin/Native's IGNORE_CASE puts `é` outside this negated class.
private val NonTitleChars = Regex("""[^A-Za-z0-9À-ɏ]+""")
private val Whitespace = Regex("""\s+""")
private val CodeGroupSuffix = Regex("""[-_/\s]+(e|g|l)\d*$""", RegexOption.IGNORE_CASE)
private val LooseTime = Regex("""^(\d{1,2}):(\d{1,2})""")

/** An intensive keyword in the name, code or type. */
fun isIntensiveClass(courseName: String?, courseCode: String?, eventType: String? = null): Boolean {
    val upper = "${courseName.orEmpty()} ${courseCode.orEmpty()} ${eventType.orEmpty()}".uppercase()
    return IntensiveKeyword.containsMatchIn(upper)
}

enum class ClassKind(val label: String) { Intensive("Intensive"), Lecture("Lecture"), Seminar("Seminar"), Lab("Lab") }

data class ClassType(val label: String, val tone: StatusTone)

/** Seminar/Lab/Lecture/Intensive from a course code or event type, with the schedule's badge colour. */
fun getClassType(code: String?): ClassType? = ScheduleCourses.classKind(code)?.let { kind ->
    val tone = when (kind) {
        ClassKind.Intensive -> StatusTone.Amber
        ClassKind.Lecture -> StatusTone.Sky
        ClassKind.Seminar -> StatusTone.Emerald
        ClassKind.Lab -> StatusTone.Purple
    }
    ClassType(kind.label, tone)
}

/** The class group from a course code: "7SO20NGMA01M-G01" → "G01". */
fun extractGroupCode(code: String?): String? {
    if (code.isNullOrEmpty()) return null
    val upper = code.trim().uppercase()
    for (token in upper.split(CodeSeparators)) {
        val m = GroupToken.find(token)
        if (m != null) return m.groupValues[1] + m.groupValues[2].padStart(2, '0')
    }
    val m = GroupAnywhere.find(upper) ?: return null
    return m.groupValues[1] + m.groupValues[2].padStart(2, '0')
}

data class WeekRange(val startStr: String, val endStr: String)

/** The Monday–Sunday week right before the anchor's week. */
fun getWeekBeforeStart(anchorDate: String?): WeekRange? {
    val anchor = anchorDate?.takeIf { isYmdString(it) }?.let(::parseIsoDate) ?: return null
    val monDiff = 1 - anchor.dayOfWeek.isoDayNumber
    return WeekRange(anchor.plus(DatePeriod(days = monDiff - 7)).toString(), anchor.plus(DatePeriod(days = monDiff - 1)).toString())
}

/** Whether a course is intensive: a keyword, or the whole course inside the pre-start week. */
fun isIntensiveCourseEvents(events: List<ScheduleEvent>, officialStart: String?): Boolean {
    if (events.isEmpty()) return false
    if (events.any { isIntensiveClass(it.courseName, it.courseCode, it.eventType) }) return true
    val preWeek = getWeekBeforeStart(officialStart) ?: return false
    val starts = events.mapNotNull { it.semesterStart?.takeIf(::isYmdString) }
    if (starts.isEmpty()) return false
    val minStart = starts.min()
    val ends = events.mapNotNull { it.semesterEnd?.takeIf(::isYmdString) }
    val maxEnd = if (ends.isNotEmpty()) ends.max() else minStart
    return minStart >= preWeek.startStr && maxEnd <= preWeek.endStr
}

/** Slug of a course code or name, suffixed with the slugged semester when given. */
fun normalizeCourseKey(courseCodeOrName: String, semester: String? = null): String {
    val sanitized = courseCodeOrName.trim().lowercase().replace(NonSlugChars, "-").replace(EdgeDashes, "")
    val sem = semester.orEmpty().trim().lowercase().replace(NonSlugChars, "-")
    return if (sem.isNotEmpty()) "${sanitized}_$sem" else sanitized
}

/** A course code without its group suffix, alphanumerics only. */
fun getBaseCourseCode(code: String?): String {
    if (code.isNullOrEmpty()) return ""
    return code.lowercase()
        .replace(CodeGroupSuffix, "")
        .replace(NonSlugChars, "")
        .trim()
}

/** `8:00` → `08:00`. */
fun normalizeTime(time: String?): String? {
    if (time.isNullOrEmpty()) return null
    val m = LooseTime.find(time.trim()) ?: return null
    val h = m.groupValues[1].padStart(2, '0')
    val mm = m.groupValues[2].padStart(2, '0')
    if (h.toInt() > 23 || mm.toInt() > 59) return null
    return "$h:$mm"
}

/**
 * The rules that differ between the schedule and the course tracker. Each has its own
 * instance, [ScheduleCourses] and [TrackerCourses]:
 * - word boundaries: tracker keys are stored, so they use ASCII boundaries that don't move
 *   with the platform's regex engine; keys can differ for titles with accented letters;
 * - a course whose dates can't be read: the tracker leaves it out of the official start,
 *   the schedule counts it as lasting no time.
 */
class CourseRules internal constructor(
    wordRegex: (pattern: String, ignoreCase: Boolean) -> Regex,
    private val skipUnreadableDates: Boolean,
) {
    private val lectureKeyword = wordRegex("LECTURE|ELŐADÁS|ELOADAS|\\bEA\\b", false)
    private val seminarKeyword = wordRegex("SEMINAR|SZEMINÁRIUM|SZEMINARIUM|GYAKORLAT|GYAK|\\bGY\\b|PRACTICE", false)
    private val titleGroupSuffix = wordRegex("[-_/\\s]+(e|g|l)\\d*\\b", true)
    private val sessionWords = wordRegex(
        "\\b(lecture|seminar|lab|laboratory|labor|practice|consultation|exam|előadás|eloadas|gyakorlat|gyak|laboratórium|laboratorium|ea|gy)\\b",
        true,
    )

    /** The kind a course/group code or type string names, else null. */
    fun classKind(code: String?): ClassKind? {
        if (code.isNullOrEmpty()) return null
        val upper = code.trim().uppercase()
        if (IntensiveKeyword.containsMatchIn(upper)) return ClassKind.Intensive
        if (lectureKeyword.containsMatchIn(upper)) return ClassKind.Lecture
        if (seminarKeyword.containsMatchIn(upper)) return ClassKind.Seminar
        if (LabKeyword.containsMatchIn(upper)) return ClassKind.Lab
        for (token in upper.split(CodeSeparators)) {
            if (LectureToken.matches(token)) return ClassKind.Lecture
            if (SeminarToken.matches(token)) return ClassKind.Seminar
            if (LabToken.matches(token)) return ClassKind.Lab
        }
        LeadingTypeLetter.find(upper)?.let { return kindForLetter(it.groupValues[1]) }
        TrailingTypeLetter.find(upper)?.let { return kindForLetter(it.groupValues[1]) }
        return null
    }

    private fun kindForLetter(letter: String): ClassKind = when (letter) {
        "G" -> ClassKind.Seminar
        "E" -> ClassKind.Lecture
        else -> ClassKind.Lab
    }

    /** A course name without session words (lecture, seminar, …) or group suffixes. */
    fun baseTitle(name: String?): String {
        if (name.isNullOrEmpty()) return ""
        return name.lowercase()
            .replace(sessionWords, "")
            .replace(titleGroupSuffix, "")
            .replace(Brackets, " ")
            .replace(NonTitleChars, " ")
            .trim()
            .replace(Whitespace, " ")
    }

    /** The base code, or the base title for short or absent codes, plus the semester. */
    fun courseKey(courseCode: String?, courseName: String?, semester: String?): String {
        val baseCode = getBaseCourseCode(courseCode)
        val baseTitle = baseTitle(courseName)
        // Short codes like "E01" are shared across courses; use the title then.
        val base = if (baseCode.length >= 4) baseCode
        else baseTitle.ifEmpty { baseCode.ifEmpty { courseCode?.ifEmpty { null } ?: courseName?.ifEmpty { null } ?: "unknown" } }
        return normalizeCourseKey(base, semester)
    }

    internal fun names(name: String?, code: String?) = CourseNames(baseTitle(name), name, code)

    /** Same course by exact name, base title, or a matching base code with compatible titles. */
    fun isSameCourse(nameA: String?, codeA: String?, nameB: String?, codeB: String?): Boolean =
        names(nameA, codeA).sameCourse(names(nameB, codeB))

    private fun groupEventsByCourse(events: List<ScheduleEvent>): Map<String, List<ScheduleEvent>> {
        val out = LinkedHashMap<String, MutableList<ScheduleEvent>>()
        for (e in events) out.getOrPut(courseKey(e.courseCode, e.courseName, e.semester)) { mutableListOf() } += e
        return out
    }

    /**
     * The official semester start: the earliest start among courses spanning more than a week
     * (an intensive week before classes must not define Week 1), else the earliest overall.
     */
    fun officialSemesterStart(events: List<ScheduleEvent>): String? {
        val byCourse = groupEventsByCourse(events)
        if (byCourse.isEmpty()) return null
        val windows = mutableListOf<Pair<String, Int>>()
        var overallMin: String? = null
        for (courseEvents in byCourse.values) {
            val starts = courseEvents.mapNotNull { it.semesterStart?.takeIf(::isYmdString) }
            if (starts.isEmpty()) continue
            val minStart = starts.min()
            val maxEnd = courseEvents.mapNotNull { it.semesterEnd?.takeIf(::isYmdString) }.maxOrNull() ?: minStart
            val first = parseIsoDate(minStart)
            val last = parseIsoDate(maxEnd)
            if ((first == null || last == null) && skipUnreadableDates) continue
            windows += minStart to if (first != null && last != null) first.daysUntil(last) else 0
            if (overallMin == null || minStart < overallMin) overallMin = minStart
        }
        return windows.filter { it.second > 7 }.minOfOrNull { it.first } ?: overallMin
    }

    /** Keys of the intensive courses, each semester judged against its own start. */
    fun intensiveCourseKeys(events: List<ScheduleEvent>): Set<String> {
        val keys = LinkedHashSet<String>()
        for (semesterEvents in events.groupBy { it.semester.trim() }.values) {
            val officialStart = officialSemesterStart(semesterEvents)
            for ((key, courseEvents) in groupEventsByCourse(semesterEvents)) {
                if (isIntensiveCourseEvents(courseEvents, officialStart)) keys += key
            }
        }
        return keys
    }
}

/** What the schedule groups and labels its own events by. */
val ScheduleCourses = CourseRules(::platformWordRegex, skipUnreadableDates = false)

/** What the course tracker keys and matches its trackers by. */
val TrackerCourses = CourseRules(::asciiWordRegex, skipUnreadableDates = true)

fun ScheduleEvent.scheduleCourseKey(): String = ScheduleCourses.courseKey(courseCode, courseName, semester)

/** A course's comparison forms, worked out once so one list can be matched against itself cheaply. */
internal class CourseNames(private val title: String, name: String?, code: String?) {
    private val raw = name.orEmpty().trim().lowercase()
    private val baseCode = getBaseCourseCode(code)

    fun sameCourse(other: CourseNames): Boolean {
        if (raw.isNotEmpty() && raw == other.raw) return true
        if (title.isNotEmpty() && title == other.title) return true
        if (baseCode.length >= 4 && baseCode == other.baseCode) {
            if (title.isEmpty() || other.title.isEmpty() || title.contains(other.title) || other.title.contains(title)) return true
        }
        return false
    }
}
