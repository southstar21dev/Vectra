import { cn } from '@/lib/utils';
import { STATUS_BUTTONS } from '../session-status';
import type { AttendanceSession, AttendanceStatus } from '../tracker-types';

interface StatusToggleProps {
  session: AttendanceSession;
  onSetStatus: (date: string, status: AttendanceStatus) => void;
  /**
   * `labels` — the Today card's text buttons, all lit while the session waits
   * for a mark. `icons` — a ledger row's glyph buttons, quiet until one is
   * chosen. Either way the chosen mark stays lit and the rest drop to glass.
   */
  variant?: 'labels' | 'icons';
  className?: string;
}

const LIT_CLASS: Record<Exclude<AttendanceStatus, 'unmarked'>, string> = {
  present: 'mark-depth mark-depth-present',
  absent: 'mark-depth mark-depth-absent',
  excused: 'mark-depth mark-depth-excused',
  cancelled: 'mark-depth mark-depth-cancelled',
};

/** Glyph hue on an idle icon button, so the four read apart before one is lit. */
const IDLE_ICON_CLASS: Record<Exclude<AttendanceStatus, 'unmarked'>, string> = {
  present: 'text-tint-emerald',
  absent: 'text-tint-red',
  excused: 'text-tint-amber',
  cancelled: 'text-secondary',
};

/** The four-state attendance control: tap the lit mark again to clear it. */
export function StatusToggle({ session, onSetStatus, variant = 'icons', className }: StatusToggleProps) {
  const label = session.title || session.date;
  const unmarked = session.status === 'unmarked';
  const labels = variant === 'labels';
  return (
    <div
      className={cn('grid grid-cols-4', labels ? 'gap-1.5' : 'gap-1', className)}
      role="group"
      aria-label={`Attendance status for ${label}`}
    >
      {STATUS_BUTTONS.map((btn) => {
        const status = btn.status as Exclude<AttendanceStatus, 'unmarked'>;
        const Icon = btn.icon;
        const isActive = session.status === status;
        const lit = isActive || (labels && unmarked);
        return (
          <button
            key={status}
            type="button"
            aria-pressed={isActive}
            aria-label={labels ? undefined : btn.label}
            title={isActive ? `${btn.label} — tap to clear` : `Mark ${btn.label.toLowerCase()}`}
            onClick={() => onSetStatus(session.id || session.date, isActive ? 'unmarked' : status)}
            className={cn(
              'flex min-w-0 items-center justify-center whitespace-nowrap rounded-lg text-xs transition-[color,background-color,border-color,box-shadow,transform] duration-150 select-none cursor-pointer active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary',
              labels ? 'h-11' : 'h-9',
              lit ? cn(LIT_CLASS[status], 'font-bold') : 'mark-glass font-semibold text-secondary hover:text-primary'
            )}
          >
            {labels ? (
              btn.label
            ) : (
              <Icon className={cn('h-[18px] w-[18px]', !lit && IDLE_ICON_CLASS[status])} aria-hidden="true" />
            )}
          </button>
        );
      })}
    </div>
  );
}
