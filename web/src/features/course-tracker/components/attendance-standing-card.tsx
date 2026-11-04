import type { StatusTone } from '@/components/ui/status-label';
import { cn } from '@/lib/utils';
import type { AttendanceRiskTier, AttendanceStats } from '../attendance-calc';
import type { AttendanceSession } from '../tracker-types';
import { AttendanceStrip, AttendanceStripLegend } from './attendance-strip';

export interface StandingStrip {
  label: string;
  tone: StatusTone;
  stats: AttendanceStats;
  /** Sessions in date order; one dot each. */
  sessions: AttendanceSession[];
}

interface AttendanceStandingCardProps {
  /** Allowed absences; null when the course sets no limit. */
  maxAbsences: number | null;
  stats: AttendanceStats;
  riskTier: AttendanceRiskTier;
  strips: StandingStrip[];
  todayIso: string;
  describe: (session: AttendanceSession) => string;
  onSelectSession: (session: AttendanceSession) => void;
}

/** Quotas above this many absences drop the bars; the number alone carries it. */
const MAX_BARS = 12;

/**
 * "Can I still miss one?" in one card: the skips left beside a bar per allowed
 * absence, then each class type's semester as a row of dots.
 */
export function AttendanceStandingCard({
  maxAbsences,
  stats,
  riskTier,
  strips,
  todayIso,
  describe,
  onSelectSession,
}: AttendanceStandingCardProps) {
  const used = stats.absentCount;
  const over = maxAbsences !== null ? Math.max(0, used - maxAbsences) : 0;
  const remaining = maxAbsences !== null ? Math.max(0, maxAbsences - used) : 0;

  let value: string | null;
  let suffix: string | null = null;
  let caption: string;
  let valueClass = 'text-primary';
  if (maxAbsences === null) {
    // No quota to count down from: the attendance rate, once there is one.
    value = stats.activeCount > 0 ? `${Math.round(stats.attendancePercent)}%` : null;
    caption = value ? 'attended' : 'Nothing marked yet';
  } else if (over > 0) {
    value = `+${over}`;
    caption = 'over the limit';
    valueClass = 'text-tint-red';
  } else if (maxAbsences === 0) {
    value = null;
    caption = 'No skips allowed';
  } else {
    value = String(remaining);
    suffix = `/${maxAbsences}`;
    caption = remaining === 1 ? 'skip left' : 'skips left';
    valueClass = riskTier === 'warning' ? 'text-tint-amber' : 'text-tint-emerald';
  }

  const showBars = maxAbsences !== null && maxAbsences > 0 && maxAbsences <= MAX_BARS;
  const narrowBars = maxAbsences !== null && maxAbsences > 6;

  return (
    <section aria-label="Attendance standing" className="rounded-3xl card-glass card-depth p-5">
      <div className="flex items-center justify-between gap-4">
        {value === null ? (
          <p className="text-xl font-black tracking-tight text-primary">{caption}</p>
        ) : (
          <p className="flex min-w-0 items-baseline gap-2">
            <span className={cn('text-[2.75rem] font-black leading-none tracking-tight tabular-nums', valueClass)}>
              {value}
              {suffix && <span className="text-xl font-bold tracking-normal text-muted">{suffix}</span>}
            </span>
            <span className="text-[15px] font-bold text-primary">{caption}</span>
          </p>
        )}
        {showBars && maxAbsences !== null && (
          <div
            role="img"
            aria-label={`Absences: ${Math.min(used, maxAbsences)} of ${maxAbsences} used`}
            className={cn('flex shrink-0', narrowBars ? 'gap-1' : 'gap-1.5')}
          >
            {Array.from({ length: maxAbsences }, (_, i) => (
              <span
                key={i}
                className={cn(
                  'h-10 rounded-md shadow-[inset_0_1px_0_rgba(255,255,255,0.35)] transition-colors duration-500',
                  narrowBars ? 'w-2' : 'w-4',
                  i < used ? 'bg-red-500' : 'bg-emerald-500'
                )}
              />
            ))}
          </div>
        )}
      </div>

      {strips.length > 0 && (
        <>
          <div className="mt-[18px] space-y-4 border-t border-subtle pt-4">
            {strips.map((row) => (
              <AttendanceStrip
                key={row.label}
                label={row.label}
                tone={row.tone}
                sessions={row.sessions}
                todayIso={todayIso}
                value={row.stats.activeCount > 0 ? `${Math.round(row.stats.attendancePercent)}%` : null}
                describe={describe}
                onSelect={onSelectSession}
              />
            ))}
          </div>
          <div className="mt-4 border-t border-subtle pt-3">
            <AttendanceStripLegend />
          </div>
        </>
      )}
    </section>
  );
}
