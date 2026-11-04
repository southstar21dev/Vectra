/**
 * Date labels for the attendance ledger.
 *
 * These live outside the tab so the ledger row can format its own dates
 * without importing the tab that renders it.
 */

/** "Today" stays literal; everything else reads day-month like a timetable. */
export function formatSessionDate(iso: string, todayIso: string): string {
  if (iso === todayIso) return 'Today';
  return formatSessionDateFull(iso);
}

/** The day-month reading unconditionally — for callers whose own heading
 *  already says "Today" and would otherwise repeat it. */
export function formatSessionDateFull(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return iso;
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  });
}
