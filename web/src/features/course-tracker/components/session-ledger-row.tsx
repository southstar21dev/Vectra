import { RiDeleteBinLine } from '@remixicon/react';
import { StatusLabel, type StatusTone } from '@/components/ui/status-label';
import { cn } from '@/lib/utils';
import { formatTime } from '@/lib/date-utils';
import type { TimeFormat } from '@/features/settings/appearance-prefs';
import { formatSessionDate } from '../session-date';
import { DateTile } from './date-tile';
import { StatusToggle } from './session-status-toggle';
import type { AttendanceSession, AttendanceStatus } from '../tracker-types';

export interface SessionLedgerRowProps {
  session: AttendanceSession;
  /** Academic-week title, already corrected for a mid-semester start date. */
  title: string;
  /** Session-type label resolved by the caller (lecture / seminar / intensive …). */
  badge: { label: string; tone: StatusTone };
  /** The ISO date the ledger treats as today — drives the label and the today tile. */
  todayIso: string;
  timeFormat: TimeFormat;
  /** Briefly washes the row, after a semester dot jumped to it. */
  flash?: boolean;
  onSetStatus: (date: string, status: AttendanceStatus) => void;
  onRequestDelete: (session: AttendanceSession) => void;
}

/**
 * One row of the semester ledger: the date as a tile, the week and session
 * type, and the four marks right underneath, so any past session is changed
 * in place with one tap.
 *
 * The tab supplies an already-resolved title and badge and owns the delete
 * confirmation, so the row can be laid out and tested on its own.
 */
export function SessionLedgerRow({
  session,
  title,
  badge,
  todayIso,
  timeFormat,
  flash = false,
  onSetStatus,
  onRequestDelete,
}: SessionLedgerRowProps) {
  const isToday = session.date === todayIso;
  const dateLabel = formatSessionDate(session.date, todayIso);
  const time = session.startTime ? formatTime(session.startTime, timeFormat) : '';

  return (
    <li
      data-session-id={session.id}
      aria-current={isToday ? 'date' : undefined}
      className={cn(
        'flex gap-3 px-3.5 py-3 transition-colors duration-700',
        flash && 'bg-accent-primary/10 duration-150'
      )}
    >
      <DateTile date={session.date} today={isToday} />
      <div className="flex min-w-0 flex-1 flex-col justify-between gap-2">
        <div className="flex min-w-0 items-center justify-between gap-2">
          <span className="flex min-w-0 items-center gap-2">
            <span className="truncate text-sm font-bold text-primary">{title}</span>
            {/* The tile is decorative; the date and time are read here instead. */}
            <span className="sr-only">
              {`, ${dateLabel}${time ? ` ${time}` : ''}`}
            </span>
            {session.isCustom && <StatusLabel tone="amber">Custom</StatusLabel>}
          </span>
          <span className="-my-1 flex shrink-0 items-center gap-1">
            <StatusLabel tone={badge.tone}>{badge.label}</StatusLabel>
            <button
              type="button"
              onClick={() => onRequestDelete(session)}
              title="Delete session"
              aria-label={`Delete ${title} on ${dateLabel}`}
              className="flex h-8 w-8 items-center justify-center rounded-full text-muted transition-colors cursor-pointer hover:bg-rose-500/10 hover:text-tint-red active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-primary"
            >
              <RiDeleteBinLine className="h-3.5 w-3.5" aria-hidden="true" />
            </button>
          </span>
        </div>
        {session.notes && <p className="truncate text-xs text-secondary">{session.notes}</p>}
        <StatusToggle session={session} onSetStatus={onSetStatus} variant="icons" />
      </div>
    </li>
  );
}
