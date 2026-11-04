import { nowInBudapest } from '@/lib/date-utils';
import { getCurrentSemester } from './ics-parser';

/** The fields semester selection reads from a parsed feed event. */
export interface SemesterTaggedEvent {
  semester: string;
  semester_end?: string | null;
}

/**
 * Corvinus semester tags ("2025/26/1", "2025/26/2", "2026/27/1") sort
 * chronologically as plain strings.
 */
function compareSemesters(a: string, b: string): number {
  return a.localeCompare(b);
}

/**
 * Picks the one semester a Neptun sync imports. Both the import wizard and the
 * background sync REPLACE the stored calendar with the chosen semester, so they
 * must agree:
 *
 * 1. The semester in session (Budapest calendar month) when the feed has it and
 *    it is not over yet — in January the autumn semester's exams still run.
 * 2. Otherwise the nearest upcoming semester in the feed — late January, or
 *    July once the spring semester has ended and autumn has been published.
 * 3. Otherwise the current semester even though it has ended (nothing newer).
 *
 * A stored semester is never replaced by an older one: if the pick is older
 * than `storedSemester`, the stored semester is re-synced when the feed still
 * has it, and `null` is returned (keep what is stored) when it does not.
 */
export function selectSyncSemester(
  events: SemesterTaggedEvent[],
  options: { at?: Date; storedSemester?: string | null } = {}
): string | null {
  const at = options.at ?? new Date();
  const today = nowInBudapest(at).ymd;
  const current = getCurrentSemester(at);
  const feedSemesters = Array.from(new Set(events.map((e) => e.semester).filter(Boolean))).sort(compareSemesters);

  const lastDay = (semester: string): string =>
    events
      .filter((e) => e.semester === semester && e.semester_end)
      .reduce((max, e) => (String(e.semester_end) > max ? String(e.semester_end) : max), '');

  const hasCurrent = feedSemesters.includes(current);
  const currentOngoing = hasCurrent && (lastDay(current) === '' || lastDay(current) >= today);
  const upcoming = feedSemesters.find((semester) => compareSemesters(semester, current) > 0) ?? null;

  let pick: string | null = null;
  if (currentOngoing) pick = current;
  else if (upcoming) pick = upcoming;
  else if (hasCurrent) pick = current;

  const stored = options.storedSemester || null;
  if (stored && (!pick || compareSemesters(pick, stored) < 0)) {
    return feedSemesters.includes(stored) ? stored : null;
  }
  return pick;
}

/** Newest semester among already-stored Neptun events, or `null` when none. */
export function newestStoredSemester(events: ReadonlyArray<{ semester?: string | null; source?: string }>): string | null {
  const semesters = events
    .filter((e) => e.source === 'neptun' && e.semester)
    .map((e) => String(e.semester))
    .sort(compareSemesters);
  return semesters[semesters.length - 1] ?? null;
}
