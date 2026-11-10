package dev.southstar21.vectra.features.schedule

import dev.southstar21.vectra.core.time.Budapest
import dev.southstar21.vectra.core.time.daysSinceSunday
import dev.southstar21.vectra.core.time.pad2
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * Neptun Corvinus iCalendar parser and semester categoriser.
 * Times come out as Budapest wall clock regardless of the device zone.
 */

/** The marked Neptun code form: "( - MATH101-E01)" — unambiguous, so it wins. */
private val MarkedCodeParenthetical = Regex("""\(\s*-\s*([^)]*)\)""")

/** The bare form "(G04)": a single unbroken token, so "(Probability and Statistics)" never reads as a code. */
private val BareCodeParenthetical = Regex("""\(\s*([A-Za-z0-9][\w./-]*)\s*\)""")

data class ParsedIcsEvent(
    val courseName: String,
    val courseCode: String,
    val instructor: String,
    val location: String,
    /** 0 = Sun … 6 = Sat */
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String,
    val color: String,
    val semester: String,
    val semesterStart: String?,
    val semesterEnd: String?,
    /** 'class' | 'exam' | 'other' */
    val eventType: String = "class",
)

data class IcsParseError(val row: Int, val message: String)

data class ParseIcsResult(
    val valid: List<ParsedIcsEvent>,
    val errors: List<IcsParseError>,
    val semesters: List<String>,
    val eventsBySemester: Map<String, List<ParsedIcsEvent>>,
)

data class IcsProperty(val name: String, val params: Map<String, String>, val value: String)

/**
 * Splits a content line at the first colon outside double quotes — a quoted
 * parameter may itself contain one (`DTSTART;TZID="GMT+01:00 Budapest":2025…`).
 */
fun splitIcsProperty(line: String): IcsProperty? {
    var inQuotes = false
    var colon = -1
    for (i in line.indices) {
        val ch = line[i]
        if (ch == '"') inQuotes = !inQuotes
        else if (ch == ':' && !inQuotes) {
            colon = i
            break
        }
    }
    if (colon == -1) return null
    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)

    val segments = mutableListOf<String>()
    val current = StringBuilder()
    inQuotes = false
    for (ch in head) {
        if (ch == '"') inQuotes = !inQuotes
        if (ch == ';' && !inQuotes) {
            segments += current.toString()
            current.clear()
        } else current.append(ch)
    }
    segments += current.toString()

    val params = LinkedHashMap<String, String>()
    for (segment in segments.drop(1)) {
        val eq = segment.indexOf('=')
        if (eq == -1) continue
        val name = segment.substring(0, eq).trim().uppercase()
        val v = segment.substring(eq + 1).trim().replace(Regex("^\"(.*)\"$"), "$1")
        params[name] = v
    }
    return IcsProperty(segments[0].trim().uppercase(), params, value)
}

data class IcsTimestampInfo(
    val instant: Instant,
    val dateStr: String,
    val timeStr: String,
    val dayOfWeek: Int,
    /** A date-only value (`VALUE=DATE` / `YYYYMMDD`): an all-day event. */
    val allDay: Boolean,
)

private val StampRe = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?""")
private val BareDatePrefix = Regex("""^\d{8}""")
private val NestedBegin = Regex("^BEGIN:", RegexOption.IGNORE_CASE)
private val NestedEnd = Regex("^END:", RegexOption.IGNORE_CASE)

/**
 * Parse "20250919T075000Z", "20250919T075000" or "20250919" (or a whole
 * property line) into Budapest wall-clock parts. Floating and TZID stamps are
 * wall-clock in that zone (floating = Budapest) — never the device's zone.
 */
fun parseIcsTimestamp(dtStr: String): IcsTimestampInfo? {
    if (dtStr.isEmpty()) return null
    val input = dtStr.trim()
    val property = if (BareDatePrefix.containsMatchIn(input)) null else splitIcsProperty(input)
    val tzid = property?.params?.get("TZID")?.ifEmpty { null }
    val clean = property?.value?.trim() ?: input
    val m = StampRe.find(clean) ?: return null
    val year = m.groupValues[1].toInt()
    val month = m.groupValues[2].toInt()
    val day = m.groupValues[3].toInt()
    val hour = m.groups[4]?.value
    val min = m.groups[5]?.value ?: "00"
    val sec = m.groups[6]?.value ?: "00"
    val isUtc = m.groups[7] != null
    val allDay = hour == null || property?.params?.get("VALUE")?.uppercase() == "DATE"
    if (allDay) {
        // A calendar date, not an instant: no zone conversion may move it.
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return null
        val instant = LocalDateTime(date.year, date.month, date.day, 0, 0).toInstant(TimeZone.UTC)
        return IcsTimestampInfo(instant, "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}", "00:00", date.dayOfWeek.daysSinceSunday, true)
    }
    val local = runCatching { LocalDateTime(year, month, day, hour.toInt(), min.toInt(), sec.toInt().coerceAtMost(59)) }.getOrNull() ?: return null
    val instant = if (isUtc) local.toInstant(TimeZone.UTC) else wallClockToInstant(tzid ?: "Europe/Budapest", local)
    return budapestInfoForInstant(instant)
}

/** Budapest wall-clock date/time/weekday for an instant. */
private fun budapestInfoForInstant(instant: Instant): IcsTimestampInfo {
    val t = instant.toLocalDateTime(Budapest)
    return IcsTimestampInfo(instant, t.date.toString(), "${pad2(t.hour)}:${pad2(t.minute)}", t.date.dayOfWeek.daysSinceSunday, false)
}

/** Unknown zone names (e.g. Windows-style TZIDs) fall back to Europe/Budapest. */
private fun wallClockToInstant(zone: String, local: LocalDateTime): Instant {
    val tz = runCatching { TimeZone.of(zone) }.getOrElse { Budapest }
    return local.toInstant(tz)
}

/** RFC 5545 TEXT escapes (\\, \; \, \n). */
private fun unescapeIcsText(value: String): String =
    value.replace(Regex("""\\([\\;,nN])""")) { if (it.groupValues[1] == "n" || it.groupValues[1] == "N") "\n" else it.groupValues[1] }

/** Unfold RFC 5545 wrapped lines. */
private fun unfoldIcs(raw: String): String = raw.replace(Regex("\r\n[ \t]"), "").replace(Regex("\n[ \t]"), "")

private val CourseColors = listOf(
    "#3B82F6", "#10B981", "#8B5CF6", "#F59E0B", "#EC4899",
    "#06B6D4", "#6366F1", "#F97316", "#14B8A6", "#A855F7",
)

/** Clusters YYYY-MM-DD dates into contiguous ranges; gaps over 8 days split a range. */
fun clusterConsecutiveWeeks(dates: List<String>): List<Pair<String, String>> {
    if (dates.isEmpty()) return emptyList()
    val sorted = dates.distinct().sorted()
    val clusters = mutableListOf<Pair<String, String>>()
    var start = sorted[0]
    var end = sorted[0]
    for (i in 1 until sorted.size) {
        val diff = LocalDate.parse(sorted[i]).toEpochDays() - LocalDate.parse(sorted[i - 1]).toEpochDays()
        if (diff <= 8) end = sorted[i]
        else {
            clusters += start to end
            start = sorted[i]
            end = sorted[i]
        }
    }
    clusters += start to end
    return clusters
}

private val Whitespace = Regex("""\s+""")

/** Case and spacing never make two rooms (or teachers) different. */
private fun normalizeSlotText(value: String): String = value.trim().lowercase().replace(Whitespace, " ")

private class RawIcsEvent {
    var dtstart = ""
    var dtend = ""
    var duration = ""
    var rrule = ""
    val exdates = mutableListOf<String>()
    var recurrenceId = ""
    var status = ""
    var uid = ""
    var summary = ""
    var location = ""
    var description = ""
}

/** RFC 5545 DURATION (`PT1H30M`, `P1D`, `P2W`) in milliseconds; null when malformed. */
fun parseIcsDurationMs(value: String): Long? {
    val v = value.trim().uppercase()
    val m = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""").find(v) ?: return null
    if (Regex("""^[+-]?PT?$""").matches(v)) return null
    fun g(i: Int) = m.groups[i]?.value?.toLong() ?: 0L
    val ms = ((g(2) * 7 + g(3)) * 86_400 + g(4) * 3_600 + g(5) * 60 + g(6)) * 1000
    return if (m.groups[1]?.value == "-") -ms else ms
}

private val IcsWeekdays = mapOf("SU" to 0, "MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6)
private const val MaxRecurrenceOccurrences = 1000
private const val UnboundedRecurrenceDays = 366
private const val DayMs = 86_400_000L

private fun ymdCompact(utcMs: Long): String {
    val d = Instant.fromEpochMilliseconds(utcMs).toLocalDateTime(TimeZone.UTC).date
    return "${d.year}${pad2(d.month.ordinal + 1)}${pad2(d.day)}"
}

private fun weekdayOfUtcMs(ms: Long): Int = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date.dayOfWeek.daysSinceSunday

/**
 * Expands a WEEKLY/DAILY RRULE (INTERVAL, COUNT, UNTIL, BYDAY, WKST) minus
 * EXDATEs into one start per occurrence, keeping DTSTART's wall-clock time in
 * its own zone (a Budapest 08:00 class stays 08:00 across DST).
 */
private fun expandRecurrence(dtstartLine: String, first: IcsTimestampInfo, rrule: String, exdateLines: List<String>): List<IcsTimestampInfo> {
    val rule = HashMap<String, String>()
    for (part in rrule.split(';')) {
        val eq = part.indexOf('=')
        if (eq > 0) rule[part.substring(0, eq).trim().uppercase()] = part.substring(eq + 1).trim()
    }

    val excludedInstants = HashSet<Long>()
    val excludedDates = HashSet<String>()
    for (line in exdateLines) {
        val property = splitIcsProperty(line) ?: continue
        val head = line.substring(0, line.length - property.value.length)
        for (value in property.value.split(',')) {
            val info = parseIcsTimestamp("$head${value.trim()}") ?: continue
            if (info.allDay) excludedDates += info.dateStr else excludedInstants += info.instant.toEpochMilliseconds()
        }
    }
    fun isExcluded(info: IcsTimestampInfo) = info.instant.toEpochMilliseconds() in excludedInstants || info.dateStr in excludedDates

    val freq = rule["FREQ"].orEmpty().uppercase()
    if (freq != "WEEKLY" && freq != "DAILY") return if (isExcluded(first)) emptyList() else listOf(first)

    val property = splitIcsProperty(dtstartLine)
    val value = property?.value?.trim() ?: dtstartLine.trim()
    val dm = Regex("""^(\d{4})(\d{2})(\d{2})(.*)$""").find(value) ?: return listOf(first)
    val trimmedLine = dtstartLine.trim()
    val head = if (property != null) trimmedLine.substring(0, trimmedLine.length - property.value.length) else ""
    val timeSuffix = dm.groupValues[4]
    val startDay = runCatching { LocalDate(dm.groupValues[1].toInt(), dm.groupValues[2].toInt(), dm.groupValues[3].toInt()) }.getOrNull()
        ?: return listOf(first)
    val startDayMs = startDay.toEpochDays() * DayMs
    val startDow = startDay.dayOfWeek.daysSinceSunday

    val interval = maxOf(1, rule["INTERVAL"]?.let { Regex("^-?\\d+").find(it.trim())?.value?.toIntOrNull() } ?: 1)
    val count = rule["COUNT"]?.let { Regex("^-?\\d+").find(it.trim())?.value?.toIntOrNull() }
    val until = rule["UNTIL"]?.let { parseIcsTimestamp(it) }
    val hardStopMs = startDayMs + UnboundedRecurrenceDays * DayMs
    val byDay = rule["BYDAY"].orEmpty().split(',').mapNotNull { code ->
        IcsWeekdays[code.trim().uppercase().replace(Regex("""^[+-]?\d+"""), "")]
    }
    val wkst = IcsWeekdays[(rule["WKST"] ?: "MO").uppercase()] ?: 1

    fun pastUntil(info: IcsTimestampInfo): Boolean = until != null &&
        (if (until.allDay) info.dateStr > until.dateStr else info.instant > until.instant)

    val result = mutableListOf<IcsTimestampInfo>()
    var emitted = 0
    fun emit(info: IcsTimestampInfo) {
        emitted++
        // EXDATEs still count towards COUNT (RFC 5545 §3.8.5.1).
        if (!isExcluded(info)) result += info
    }
    emit(first)

    fun candidateDays(step: Int): List<Long> {
        if (freq == "DAILY") {
            val dayMs = startDayMs + step.toLong() * interval * DayMs
            return if (byDay.isEmpty() || weekdayOfUtcMs(dayMs) in byDay) listOf(dayMs) else emptyList()
        }
        val weekStartMs = startDayMs - ((startDow - wkst + 7) % 7) * DayMs + step.toLong() * interval * 7 * DayMs
        val days = byDay.ifEmpty { listOf(startDow) }
        return days.map { dow -> weekStartMs + ((dow - wkst + 7) % 7) * DayMs }.sorted()
    }

    for (step in 0 until MaxRecurrenceOccurrences) {
        for (dayMs in candidateDays(step)) {
            if (dayMs <= startDayMs) continue
            if (count != null && emitted >= count) return result
            if (until == null && count == null && dayMs > hardStopMs) return result
            if (emitted >= MaxRecurrenceOccurrences) return result
            val info = parseIcsTimestamp("$head${ymdCompact(dayMs)}$timeSuffix") ?: return result
            if (pastUntil(info)) return result
            emit(info)
        }
    }
    return result
}

/** Parse Neptun ICS text into deduplicated, semester-tagged weekly slots. */
fun parseIcsContent(icsText: String, includeExams: Boolean = true): ParseIcsResult {
    val lines = unfoldIcs(icsText).split(Regex("\r?\n"))
    val events = mutableListOf<RawIcsEvent>()
    var inEvent = false
    // Nested components (VALARM) carry their own DESCRIPTION/DURATION; they must not overwrite the event's.
    var nested = 0
    var current = RawIcsEvent()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed == "BEGIN:VEVENT") {
            inEvent = true; nested = 0; current = RawIcsEvent(); continue
        }
        if (trimmed == "END:VEVENT") {
            if (inEvent) events += current
            inEvent = false
            continue
        }
        if (!inEvent) continue
        if (NestedBegin.containsMatchIn(trimmed)) { nested++; continue }
        if (NestedEnd.containsMatchIn(trimmed)) { nested = maxOf(0, nested - 1); continue }
        if (nested > 0) continue
        val property = splitIcsProperty(trimmed) ?: continue
        when (property.name) {
            "DTSTART" -> current.dtstart = trimmed
            "DTEND" -> current.dtend = trimmed
            "RECURRENCE-ID" -> current.recurrenceId = trimmed
            "EXDATE" -> current.exdates += trimmed
            "DURATION" -> current.duration = property.value.trim()
            "RRULE" -> current.rrule = property.value.trim()
            "STATUS" -> current.status = property.value.trim().uppercase()
            "UID" -> current.uid = property.value.trim()
            "SUMMARY" -> current.summary = unescapeIcsText(property.value)
            "LOCATION" -> current.location = unescapeIcsText(property.value)
            "DESCRIPTION" -> current.description = unescapeIcsText(property.value)
        }
    }

    // A modified single occurrence (RECURRENCE-ID) replaces that occurrence of its series.
    val overriddenByUid = HashMap<String, MutableList<String>>()
    for (e in events) {
        if (e.uid.isEmpty() || e.recurrenceId.isEmpty()) continue
        overriddenByUid.getOrPut(e.uid) { mutableListOf() } += e.recurrenceId
    }

    val errors = mutableListOf<IcsParseError>()
    data class Entry(val event: ParsedIcsEvent, val dates: MutableList<String>)
    val eventMap = LinkedHashMap<String, Entry>()
    val courseColors = HashMap<String, String>()
    var colorIndex = 0
    // Slot + date pairs already taken: a second VEVENT for the same class on the same date adds nothing.
    val slotDates = HashSet<String>()

    fun addOccurrence(raw: RawIcsEvent, summary: String, isExam: Boolean, start: IcsTimestampInfo, end: IcsTimestampInfo) {
        // "Course Name ( - Course Code) - Instructor - Tanóra" / "Course (Írásbeli) - Instructor - Vizsga"
        var courseName: String
        var courseCode = ""
        var instructor = ""
        val suffix = summary.lastIndexOf(" - ")
        var clean = if (suffix != -1) summary.substring(0, suffix).trim() else summary
        val codeMatch = MarkedCodeParenthetical.find(clean) ?: BareCodeParenthetical.find(clean)
        if (codeMatch != null && !isExamTypeParenthetical(codeMatch.groupValues[1])) {
            courseCode = codeMatch.groupValues[1].trim()
            clean = clean.replaceFirst(codeMatch.value, "").trim()
        }
        val parts = clean.split(" - ")
        if (parts.size >= 2) {
            courseName = parts[0].trim()
            instructor = parts.drop(1).joinToString(" - ").trim()
        } else courseName = clean.trim()

        // From the Budapest calendar date, not the device-local month of the instant.
        val (semYear, semMonth) = start.dateStr.split('-').map { it.toInt() }
        val semester = getSemesterFromYearMonth(semYear, semMonth)

        val color = courseColors.getOrPut(courseName) { CourseColors[colorIndex % CourseColors.size].also { colorIndex++ } }

        val slotKey = "$semester|${courseName.lowercase()}|${courseCode.lowercase()}|${start.dayOfWeek}|${start.timeStr}|${end.timeStr}|" +
            if (isExam) "exam:${start.dateStr}" else "class"
        val location = raw.location.trim()
        // Room and teacher belong to the slot: a class that changes room mid-semester becomes
        // date-ranged slots of its own instead of showing the first room all term.
        val dedupKey = "$slotKey|${normalizeSlotText(location)}|${normalizeSlotText(instructor)}"
        if (slotDates.add("$slotKey|${start.dateStr}")) {
            val existing = eventMap[dedupKey]
            if (existing != null) existing.dates += start.dateStr
            else eventMap[dedupKey] = Entry(
                ParsedIcsEvent(
                    courseName, courseCode, instructor, location, start.dayOfWeek, start.timeStr, end.timeStr,
                    color, semester, start.dateStr, start.dateStr, if (isExam) "exam" else "class",
                ),
                mutableListOf(start.dateStr),
            )
        }
    }

    events.forEachIndexed { index, raw ->
        val summary = raw.summary.trim()
        if (summary.isEmpty()) return@forEachIndexed
        if (raw.status == "CANCELLED") return@forEachIndexed
        val lower = summary.lowercase()
        if (lower.contains("szünnap") || lower.contains("szünet")) return@forEachIndexed
        val isExam = isExamSummary(summary)
        if (isExam && !includeExams) return@forEachIndexed

        val firstStart = parseIcsTimestamp(raw.dtstart)
        if (firstStart == null) {
            errors += IcsParseError(index + 1, "Invalid date format in event: $summary")
            return@forEachIndexed
        }
        // All-day entries are notices (holidays, registration periods), not timed classes.
        if (firstStart.allDay) return@forEachIndexed

        val firstEnd = if (raw.dtend.isNotEmpty()) parseIcsTimestamp(raw.dtend) else null
        val durationMs = when {
            firstEnd != null -> firstEnd.instant.toEpochMilliseconds() - firstStart.instant.toEpochMilliseconds()
            raw.duration.isNotEmpty() -> parseIcsDurationMs(raw.duration)
            else -> null
        }
        if (durationMs == null || durationMs < 0) {
            errors += IcsParseError(index + 1, "Invalid date format in event: $summary")
            return@forEachIndexed
        }

        val starts = if (raw.rrule.isNotEmpty() && raw.recurrenceId.isEmpty()) {
            expandRecurrence(raw.dtstart, firstStart, raw.rrule, raw.exdates + (if (raw.uid.isNotEmpty()) overriddenByUid[raw.uid].orEmpty() else emptyList()))
        } else listOf(firstStart)

        for (s in starts) {
            val rawEnd = budapestInfoForInstant(Instant.fromEpochMilliseconds(s.instant.toEpochMilliseconds() + durationMs))
            // Running to (or past) midnight ends at the end of its own day: "00:00" would read as before the start.
            val end = if (rawEnd.dateStr != s.dateStr) rawEnd.copy(timeStr = "23:59") else rawEnd
            addOccurrence(raw, summary, isExam, s, end)
        }
    }

    val valid = mutableListOf<ParsedIcsEvent>()
    val bySemester = LinkedHashMap<String, MutableList<ParsedIcsEvent>>()
    for ((event, dates) in eventMap.values) {
        val clusters = if (event.eventType == "exam") dates.distinct().map { it to it } else clusterConsecutiveWeeks(dates)
        for ((cs, ce) in clusters) {
            val clustered = event.copy(semesterStart = cs, semesterEnd = ce)
            valid += clustered
            bySemester.getOrPut(event.semester) { mutableListOf() } += clustered
        }
    }
    return ParseIcsResult(valid, errors, bySemester.keys.sorted(), bySemester)
}

/** The Google Apps Script proxy: the university blocks datacenter IPs, Google is not blocked. */
const val NEPTUN_ICS_PROXY_URL =
    "https://script.google.com/macros/s/AKfycbxp_HKH85vTNjtVC5L2d8Ojhwt7BJQzxFVvvOi00UQK1w1G89RrMX11OaAMRHnD-5kj/exec"

const val NEPTUN_FETCH_FAILED =
    "We couldn't download your Neptun calendar — the university's servers may be blocking the sync, or the connection timed out. Check your connection and try again in a few minutes."
