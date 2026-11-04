import { cn } from '@/lib/utils';
import { STATUS_TONE_CLASS, type StatusTone } from '@/components/ui/status-label';
import type { AttendanceSession } from '../tracker-types';

type DotKind = 'present' | 'absent' | 'excused' | 'cancelled' | 'today' | 'open';

/**
 * Dot fill per kind, in the hues the marks use. No class is hatched rather
 * than filled: the class did not happen, so it counts neither way.
 */
const DOT_CLASS: Record<DotKind, string> = {
  present: 'bg-emerald-500',
  absent: 'bg-red-500',
  excused: 'bg-amber-500',
  cancelled:
    'bg-[repeating-linear-gradient(45deg,color-mix(in_srgb,var(--color-slate-400)_30%,transparent)_0_1.5px,transparent_1.5px_4px)]',
  /** Today's session, still to be marked: the accent ring. */
  today: 'border-2 border-accent-primary',
  /** Pending: still ahead, or simply never marked — a quiet slot either way. */
  open: 'bg-border-subtle',
};

function dotKind(session: AttendanceSession, todayIso: string): DotKind {
  if (session.status !== 'unmarked') return session.status;
  return session.date === todayIso ? 'today' : 'open';
}

interface AttendanceStripProps {
  label: string;
  /** Hue of the session type, as the timetable labels it. */
  tone: StatusTone;
  /** Sessions in date order; one dot each. */
  sessions: AttendanceSession[];
  todayIso: string;
  /** Right-hand reading, e.g. "80%"; null (nothing shown) until a session of the type is marked. */
  value: string | null;
  /** Screen-reader name of one dot, e.g. "Seminar, Week 4: Absent". */
  describe: (session: AttendanceSession) => string;
  /** Jumps to the session's row in the ledger. */
  onSelect: (session: AttendanceSession) => void;
}

/**
 * The semester at a glance for one class type: one dot per session, filled by
 * how it was marked, so a missed week shows where it fell rather than only as
 * a count. Tapping a dot brings its row into view.
 */
export function AttendanceStrip({ label, tone, sessions, todayIso, value, describe, onSelect }: AttendanceStripProps) {
  return (
    <div>
      <div className="flex items-center gap-2">
        <span className={cn('h-2 w-2 shrink-0 rounded-full bg-current', STATUS_TONE_CLASS[tone])} aria-hidden="true" />
        <span className="min-w-0 flex-1 truncate text-sm font-bold text-primary">{label}</span>
        {value !== null && <span className="text-sm font-extrabold tabular-nums text-primary">{value}</span>}
      </div>
      {/* Dots grow to 20px and spread edge to edge; a long semester shrinks them. */}
      <div className="mt-2.5 flex justify-between gap-[3px]">
        {sessions.map((session) => {
          const kind = dotKind(session, todayIso);
          const isMarkedToday = session.date === todayIso && kind !== 'today';
          return (
            <button
              key={session.id || session.date}
              type="button"
              onClick={() => onSelect(session)}
              aria-label={describe(session)}
              title={describe(session)}
              className={cn(
                'aspect-square min-w-0 max-w-5 flex-1 rounded-full cursor-pointer transition-transform active:scale-90 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary',
                DOT_CLASS[kind],
                isMarkedToday && 'outline outline-2 outline-offset-2 outline-accent-primary'
              )}
            />
          );
        })}
      </div>
    </div>
  );
}

const LEGEND: Array<{ kind: DotKind; label: string }> = [
  { kind: 'present', label: 'Present' },
  { kind: 'absent', label: 'Absent' },
  { kind: 'excused', label: 'Excused' },
  { kind: 'today', label: 'Today' },
  { kind: 'cancelled', label: 'No class' },
  { kind: 'open', label: 'Pending' },
];

/** Key for the dots: every kind, always, so the dots never need guessing. */
export function AttendanceStripLegend() {
  return (
    // Columns sized to their labels and spread edge to edge, so the gaps
    // between them are even however long each label is.
    <ul className="grid grid-cols-[repeat(3,auto)] justify-between gap-x-4 gap-y-2 text-xs text-secondary" aria-hidden="true">
      {LEGEND.map((item) => (
        <li key={item.kind} className="flex items-center gap-1.5 whitespace-nowrap">
          <span className={cn('h-2.5 w-2.5 shrink-0 rounded-full', DOT_CLASS[item.kind])} />
          {item.label}
        </li>
      ))}
    </ul>
  );
}
