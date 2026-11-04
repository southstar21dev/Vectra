import { PostageStamp } from '@/components/ui/postage-stamp';
import { cn } from '@/lib/utils';
import { formatTime } from '@/lib/date-utils';
import type { TimeFormat } from '@/features/settings/appearance-prefs';
import { TODAY_STAMP_GLOW } from './date-tile';
import { StatusToggle } from './session-status-toggle';
import type { AttendanceSession, AttendanceStatus } from '../tracker-types';

interface TodaySessionCardProps {
  session: AttendanceSession;
  /** Session type as the timetable names it (Seminar, Lecture, Intensive …). */
  typeLabel: string;
  /** Academic-week title; stands in for the instructor when there is none. */
  weekTitle: string;
  instructor?: string;
  location?: string;
  timeFormat: TimeFormat;
  onSetStatus: (date: string, status: AttendanceStatus) => void;
}

/**
 * Today's session, marked in one tap: a tilted "Today" stamp naming the class,
 * who teaches it, where and when, and the four marks. All four stay lit until
 * one is chosen; then only that one does.
 */
export function TodaySessionCard({
  session,
  typeLabel,
  weekTitle,
  instructor,
  location,
  timeFormat,
  onSetStatus,
}: TodaySessionCardProps) {
  const time = session.startTime
    ? `${formatTime(session.startTime, timeFormat)}${session.endTime ? `–${formatTime(session.endTime, timeFormat)}` : ''}`
    : '';

  return (
    <section
      aria-label={`Today's ${typeLabel.toLowerCase()}`}
      className="relative mt-7 rounded-2xl card-glass card-depth px-2.5 pb-2.5 pt-16"
    >
      {/* Today's stamp stuck over the card's top edge, lit the way the
          schedule lights today's day stamp. */}
      <PostageStamp
        className={cn('absolute -top-[26px] left-3.5 -rotate-[4deg] bg-surface-tertiary', TODAY_STAMP_GLOW)}
        innerClassName="h-[72px] w-[84px] gap-1 border-solid border-accent-primary px-1 leading-none"
      >
        <h3 className="flex max-w-full flex-col items-center gap-1">
          <span className="text-xs font-extrabold leading-none tracking-[0.12em] text-accent-primary">TODAY</span>
          <span
            className={cn(
              'max-w-full truncate font-black leading-tight tracking-tight text-primary',
              typeLabel.length > 8 ? 'text-sm' : 'text-[17px]'
            )}
          >
            {typeLabel}
          </span>
        </h3>
      </PostageStamp>
      <div className="absolute left-[122px] right-3.5 top-3 flex min-w-0 flex-col gap-2">
        <span className="truncate text-sm font-bold text-primary">{instructor || weekTitle}</span>
        <span className="flex justify-between gap-3 text-[13px] text-secondary">
          <span className="truncate">{location ? `Room ${location}` : instructor ? weekTitle : ''}</span>
          {time && <span className="shrink-0 font-mono tabular-nums">{time}</span>}
        </span>
      </div>
      <StatusToggle session={session} onSetStatus={onSetStatus} variant="labels" />
    </section>
  );
}
