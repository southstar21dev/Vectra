package dev.southstar21.vectra.features.reminders

import dev.southstar21.vectra.core.platform.DepartureQuery
import dev.southstar21.vectra.core.platform.ReminderChannel
import dev.southstar21.vectra.core.platform.ScheduledNotification
import dev.southstar21.vectra.core.time.formatBudapestHm

/** "Next class" fires this long before the current one ends. */
const val NextClassWarningMinutes = 5

/**
 * Every reminder [sessions] call for under [prefs], from [nowMs] on. Per stretch of the day
 * (split at breaks over [LongBreakMinutes]): a before-class reminder for its first session with the
 * to-uni departures, a next-class warning before each class inside it ends, and the back-home
 * departures when it's over.
 */
fun planReminders(sessions: List<ReminderSession>, prefs: ReminderPrefs, nowMs: Long): List<ScheduledNotification> {
    val toUni = prefs.toUni?.query?.takeIf { prefs.departures }
    val backHome = prefs.backHome?.query?.takeIf { prefs.departures }
    val stretches = splitIntoStretches(sessions)
    val out = stretches.flatMapIndexed { i, stretch ->
        val after = stretches.getOrNull(i + 1)?.first()?.takeIf { it.day == stretch.first().day }
        listOfNotNull(arrivalReminder(stretch.first(), prefs, toUni)) +
            insideReminders(stretch, prefs) +
            listOfNotNull(backHome?.let { homeReminder(stretch, after, it) })
    }
    return out.filter { it.atMs > nowMs + 30_000 }.distinctBy { it.id }.sortedBy { it.atMs }
}

/** Arriving: the room ahead of time, and the departures to get there. */
private fun arrivalReminder(first: ReminderSession, prefs: ReminderPrefs, toUni: DepartureQuery?): ScheduledNotification? {
    val classOn = prefs.beforeClass && first.kind.allowedBy(prefs)
    return if (!classOn && toUni == null) null else ScheduledNotification(
        id = "rem:before:${first.key}",
        atMs = first.startMs - prefs.leadMinutes * MinuteMs,
        title = if (classOn) "${first.title} ${leadLabel(prefs.leadMinutes)}" else "Leave for ${first.title}",
        body = details(roomOf(first), first.typeLabel, formatBudapestHm(first.startMs)),
        url = if (toUni != null) "/transit" else "/schedule",
        channel = if (toUni != null) ReminderChannel.Commute else ReminderChannel.Classes,
        departures = toUni,
        expiresAtMs = first.startMs,
    )
}

/** Inside a stretch: what's next and where before each class ends (or, with that off, the before-class one). */
private fun insideReminders(stretch: List<ReminderSession>, prefs: ReminderPrefs): List<ScheduledNotification> =
    stretch.zipWithNext()
        .filter { (_, next) -> next.kind.allowedBy(prefs) }
        .mapNotNull { (cur, next) ->
            when {
                prefs.nextClass -> nextClassWarning(cur, next)
                prefs.beforeClass -> ScheduledNotification(
                    id = "rem:before:${next.key}",
                    atMs = next.startMs - prefs.leadMinutes * MinuteMs,
                    title = "${next.title} ${leadLabel(prefs.leadMinutes)}",
                    body = details(roomOf(next), next.typeLabel, formatBudapestHm(next.startMs)),
                    url = "/schedule",
                    expiresAtMs = next.startMs,
                )
                else -> null
            }
        }

private fun nextClassWarning(cur: ReminderSession, next: ReminderSession): ScheduledNotification? {
    val at = maxOf(cur.endMs - NextClassWarningMinutes * MinuteMs, cur.startMs)
    val gap = (next.startMs - cur.endMs) / MinuteMs
    // Overlapping classes: by the warning they're already on the way.
    return if (next.startMs <= at) null else ScheduledNotification(
        id = "rem:next:${next.key}",
        atMs = at,
        title = "Next: ${next.title} at ${formatBudapestHm(next.startMs)}",
        body = details(roomOf(next), next.typeLabel, if (gap <= 0) "right after" else "${durationLabel(gap)} break"),
        url = "/schedule",
        expiresAtMs = next.startMs,
    )
}

/** Leaving: the way home when the stretch's last session ends ([after] = the day's next stretch, if any). */
private fun homeReminder(stretch: List<ReminderSession>, after: ReminderSession?, backHome: DepartureQuery): ScheduledNotification {
    val last = stretch.maxBy { it.endMs }
    return ScheduledNotification(
        id = "rem:home:${last.key}",
        atMs = last.endMs,
        title = if (after != null) "Break until ${formatBudapestHm(after.startMs)}, heading home?" else "Done for today, heading home?",
        body = "${last.title} ended at ${formatBudapestHm(last.endMs)}",
        url = "/transit",
        channel = ReminderChannel.Commute,
        departures = backHome,
        expiresAtMs = last.endMs + 60 * MinuteMs,
    )
}
