import { useMemo } from 'react';
import { RiMapPinLine, RiCheckLine, RiCloseLine, RiErrorWarningLine, RiAlertLine, RiRadioButtonLine, RiCalendarCloseLine, RiGroupLine } from '@remixicon/react';
import { getClassType, extractGroupCode, isIntensiveClass, cn } from '@/lib/utils';
import { StatusLabel } from '@/components/ui/status-label';
import { formatTime } from '@/lib/date-utils';
import { getEventSessionType, normalizeTime, resolveCourseKey } from '@/features/course-tracker/attendance-calc';
import { classOccursOnDate } from './schedule-conflicts';
import { isExamEvent } from './exam-detection';
import { nowInBudapest } from '@/lib/date-utils';
import { useCourseTracker } from '@/features/course-tracker/use-course-tracker';
import { useAppearance } from '@/hooks/use-appearance';
import type { TimeFormat } from '@/features/settings/appearance-prefs';
import type { ScheduleEvent } from './use-schedule';
import type { AttendanceStatus, SessionType } from '@/features/course-tracker/tracker-types';
import { toast } from 'sonner';

interface ScheduleCardProps {
  event: ScheduleEvent;
  onClick?: (event: ScheduleEvent) => void;
  compact?: boolean;
  showQuickAttendance?: boolean;
  dateStr?: string;
  timeFormat?: TimeFormat;
  /** Canonical course keys (from getIntensiveCourseKeys) of intensive block courses. */
  intensiveCourses?: ReadonlySet<string> | null;
  /** Personal items overlap this class occurrence on its date. */
  hasConflict?: boolean;
}

export function ScheduleCard({ event, onClick, showQuickAttendance = true, dateStr, timeFormat: explicitTimeFormat, intensiveCourses, hasConflict = false }: ScheduleCardProps) {
  const { prefs } = useAppearance();
  const timeFormat = explicitTimeFormat || prefs.timeFormat;
  const now = nowInBudapest();
  const currentMinutes = now.hours * 60 + now.minutes;
  const todayStr = now.ymd;
  const activeDateStr = dateStr || todayStr;
  // Without an explicit date the card is "today" only when the class actually
  // meets today — not merely on today's weekday (odd/even weeks, date ranges,
  // holidays, exams on another date).
  const isToday = dateStr
    ? dateStr === todayStr
    : event.day_of_week === now.dayOfWeek && classOccursOnDate(event, todayStr);

  const { getTracker, setAttendanceStatus } = useCourseTracker();
  const courseIdentity = {
    courseCode: event.course_code,
    courseName: event.course_name,
    semester: event.semester,
    instructor: event.instructor,
    location: event.location,
    color: event.color,
  };
  const tracker = getTracker(courseIdentity);

  // Session status for this card's specific active date & start time
  const activeSession = useMemo(() => {
    if (!tracker || !tracker.attendance?.sessions) return null;
    const dateSessions = tracker.attendance.sessions.filter((s) => s.date === activeDateStr);
    if (dateSessions.length === 0) return null;

    if (event.start_time) {
      const eventStart = normalizeTime(event.start_time);
      const exactTimeMatch = eventStart
        ? dateSessions.find((s) => s.startTime && normalizeTime(s.startTime) === eventStart)
        : undefined;
      if (exactTimeMatch) return exactTimeMatch;

      const hasOtherTimedSessions = eventStart
        ? dateSessions.some((s) => s.startTime && normalizeTime(s.startTime) !== eventStart)
        : dateSessions.some((s) => s.startTime);
      if (!hasOtherTimedSessions) {
        return dateSessions.find((s) => !s.startTime) || null;
      }
      return null;
    }

    return dateSessions[0] || null;
  }, [tracker, activeDateStr, event.start_time]);

  const currentStatus: AttendanceStatus = activeSession?.status || 'unmarked';
  const isCancelled = currentStatus === 'cancelled';

  const timing = useMemo(() => {
    if (!event.start_time || !event.end_time) return null;
    const [sH, sM] = event.start_time.split(':').map(Number);
    const [eH, eM] = event.end_time.split(':').map(Number);
    const startMin = sH * 60 + sM;
    const endMin = eH * 60 + eM;
    const totalDuration = endMin - startMin;

    const isActive = isToday && currentMinutes >= startMin && currentMinutes < endMin;
    const isUpcoming = isToday && currentMinutes < startMin && startMin - currentMinutes <= 120; // within 2 hours
    const minutesToStart = startMin - currentMinutes;
    const elapsedMinutes = currentMinutes - startMin;
    const progressPercent = totalDuration > 0 ? Math.min(100, Math.max(0, (elapsedMinutes / totalDuration) * 100)) : 0;

    return {
      isActive,
      isUpcoming,
      minutesToStart,
      elapsedMinutes,
      remainingMinutes: endMin - currentMinutes,
      progressPercent,
    };
  }, [event.start_time, event.end_time, isToday, currentMinutes]);

  const eventColor = event.color || '#38bdf8';

  // A course is an intensive block only when its events are concentrated (see
  // isIntensiveCourseEvents); the caller passes the course keys computed from
  // the full schedule. Without that context (e.g. onboarding previews) fall
  // back to the keyword-only check.
  const isIntensive = useMemo(() => {
    if (intensiveCourses) {
      return intensiveCourses.has(
        resolveCourseKey({
          courseCode: event.course_code,
          courseName: event.course_name,
          semester: event.semester,
        })
      );
    }
    return isIntensiveClass({
      course_name: event.course_name,
      course_code: event.course_code,
      event_type: event.event_type,
    });
  }, [intensiveCourses, event.course_code, event.course_name, event.semester, event.event_type]);

  const typeInfo = useMemo(() => {
    // Exams are classified by event type, not by course code: an exam row's code
    // is often the course name, which getClassType() can only guess at — and it
    // guessed the Lecture sky tone, so the badge read as a lecture. isExamEvent
    // also recognises exams imported before the title check existed.
    if (isExamEvent(event)) {
      return { label: 'Exam', tone: 'violet' as const };
    }
    if (isIntensive) {
      return { label: 'Intensive', tone: 'amber' as const };
    }
    if (event.attendance_type) {
      if (event.attendance_type === 'lecture') return { label: 'Lecture', tone: 'sky' as const };
      if (event.attendance_type === 'seminar') return { label: 'Seminar', tone: 'emerald' as const };
      if (event.attendance_type === 'lab') return { label: 'Lab', tone: 'purple' as const };
      return { label: 'Unknown', tone: 'neutral' as const };
    }
    if (event.source === 'manual') {
      return { label: 'Unknown', tone: 'neutral' as const };
    }
    return (
      getClassType(event.course_code || event.event_type) || {
        label: (event.event_type ? event.event_type : 'Lecture').replace(/^./, (c) => c.toUpperCase()),
        tone: 'sky' as const,
      }
    );
  }, [isIntensive, event]);

  const groupCode = useMemo(() => {
    return extractGroupCode(event.course_code);
  }, [event.course_code]);

  const isActive = timing?.isActive;

  // Exams are one-off events with no attendance ledger, so the card never
  // offers a check-in — not even when a caller passes showQuickAttendance.
  const canTrackAttendance = showQuickAttendance && !isExamEvent(event);

  const handleCycleAttendance = (e: React.MouseEvent) => {
    e.stopPropagation();

    let nextStatus: AttendanceStatus = 'present';
    if (currentStatus === 'unmarked') nextStatus = 'present';
    else if (currentStatus === 'present') nextStatus = 'absent';
    else if (currentStatus === 'absent') nextStatus = 'excused';
    else if (currentStatus === 'excused') nextStatus = 'cancelled';
    else if (currentStatus === 'cancelled') nextStatus = 'unmarked';
    else nextStatus = 'present';

    const sessionType: SessionType = getEventSessionType(event);

    const sessionIdentifier = activeSession?.id || activeDateStr;

    // The identity (course code/name/semester) derives the canonical tracker
    // key, so the tracker created here is the same one the Course Hub modal
    // opens — even for intensive classes with group-suffixed codes.
    setAttendanceStatus(
      courseIdentity,
      sessionIdentifier,
      nextStatus,
      {
        startTime: event.start_time,
        endTime: event.end_time,
        title: event.course_name,
        sessionType,
      }
    );

    const labels: Record<AttendanceStatus, string> = {
      present: 'Present (Attended)',
      absent: 'Absent (Missed)',
      excused: 'Excused',
      cancelled: 'Cancelled / Holiday',
      unmarked: 'Attendance reset',
    };
    toast.success(`${event.course_name}: Marked as ${labels[nextStatus]}`);
  };

  return (
    <div
      // Keyboard-reachable so the course tracker can be opened without a
      // pointer, and so the dialog has a trigger to return focus to. Not a
      // <button>: the footer holds its own check-in button.
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      aria-label={
        onClick
          ? `${event.course_name}${event.start_time ? `, ${formatTime(event.start_time, timeFormat)}` : ''}${event.end_time ? `–${formatTime(event.end_time, timeFormat)}` : ''}. Open course tracker`
          : undefined
      }
      onKeyDown={
        onClick
          ? (e) => {
              // Keys pressed on the nested check-in button are its own.
              if (e.target !== e.currentTarget) return;
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onClick(event);
              }
            }
          : undefined
      }
      className={cn(
        "group w-full flex flex-col justify-between rounded-2xl card-glass-interactive cursor-pointer transition-[color,background-color,border-color,box-shadow,transform,opacity] duration-300 relative focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-primary",
        isCancelled && "opacity-75 bg-surface-secondary/40 border-dashed border-subtle",
        isActive && !isCancelled && "border shadow-lg"
      )}
      style={
        isActive && !isCancelled
          ? {
              boxShadow: `0 0 36px -4px ${eventColor}45, var(--glass-shadow)`,
              borderColor: `${eventColor}70`,
            }
          : undefined
      }
      onClick={() => onClick?.(event)}
    >
      {/* Dynamic ambient color glow backdrop. The card deliberately does not clip, so this
          layer matches the card's own rounded box instead of relying on the card to cut a
          larger square layer down to size. */}
      {isActive && !isCancelled && (
        <div
          className="absolute inset-0 rounded-2xl opacity-20 pointer-events-none transition-[color,background-color,border-color,box-shadow,transform,opacity]"
          style={{
            background: `radial-gradient(circle at 50% 50%, ${eventColor}, transparent 75%)`,
          }}
        />
      )}

      {/* Subtle background watermark for Cancelled / Holiday class */}
      {isCancelled && (
        <div className="absolute right-3 bottom-1 pointer-events-none select-none z-0 opacity-[0.05] dark:opacity-[0.08]">
          <span className="font-mono font-black text-4xl sm:text-5xl tracking-widest uppercase rotate-[-6deg] block">
            CANCELLED
          </span>
        </div>
      )}

      <div className="flex items-center justify-between gap-3.5 px-4 py-3.5 relative z-10">
        <div className="flex items-center gap-3.5 min-w-0 flex-1">
          {/* Accent indicator bar */}
          <div
            className={cn(
              "w-1.5 self-stretch min-h-[36px] rounded-full shrink-0 shadow-sm transition-[color,background-color,border-color,box-shadow,transform,opacity] my-0.5",
              isCancelled && "opacity-40"
            )}
            style={{
              backgroundColor: isCancelled ? '#64748b' : eventColor,
              boxShadow: isActive && !isCancelled ? `0 0 12px ${eventColor}` : undefined,
            }}
          />

          <div className="min-w-0 flex-1 space-y-1">
            {/* Top row: Type + Clash */}
            <div className="flex items-center gap-1.5 flex-nowrap overflow-x-auto no-scrollbar">
              {typeInfo && (
                <StatusLabel tone={typeInfo.tone} className="select-none">
                  {typeInfo.label}
                </StatusLabel>
              )}

              {hasConflict && (
                <StatusLabel tone="red" icon={RiAlertLine} className="select-none">
                  Clash
                </StatusLabel>
              )}
            </div>

            {/* Course Title & Professor */}
            <div>
              <p className={cn(
                "text-sm sm:text-base font-bold line-clamp-2 transition-colors leading-snug",
                isCancelled
                  ? "text-muted line-through"
                  : "text-primary group-hover:text-tint-sky"
              )}>
                {event.course_name}
              </p>
              {event.instructor && (
                <p className="text-xs text-muted font-medium truncate mt-0.5">
                  {event.instructor}
                </p>
              )}
              {isCancelled && (
                <span className="text-xs text-tint-red font-medium mt-0.5 block">
                  Cancelled for this date
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Right column: Clean compact stacked time */}
        {event.start_time && event.end_time && (
          <div
            className={cn(
              "flex flex-col items-center justify-center px-3.5 py-2 rounded-2xl border shrink-0 font-mono transition-colors shadow-xs",
              isCancelled && "opacity-50",
              isActive && !isCancelled
                ? "text-primary"
                : "bg-surface-overlay border-subtle text-secondary"
            )}
            style={
              isActive && !isCancelled
                ? {
                    backgroundColor: `${eventColor}18`,
                    borderColor: `${eventColor}45`,
                  }
                : undefined
            }
          >
            <span className="text-xs font-bold tracking-tight leading-none text-primary">
              {formatTime(event.start_time, timeFormat)}
            </span>
            <span className="w-3.5 h-[1px] bg-border-primary my-1 opacity-60" />
            <span className="text-xs font-bold tracking-tight leading-none text-secondary">
              {formatTime(event.end_time, timeFormat)}
            </span>
          </div>
        )}
      </div>

      {/* Clean hairline footer bar: Location · Group + 1-Tap Attendance Check-in */}
      {(event.location || groupCode || (canTrackAttendance && isToday)) && (
        <div className="border-t border-border-subtle/60 px-4 py-2 flex items-center justify-between gap-3 text-xs text-secondary relative z-10 bg-surface-overlay/20">
          {event.location || groupCode ? (
            <div className="flex items-center gap-1.5 text-xs font-semibold text-secondary min-w-0">
              {event.location && (
                <>
                  <RiMapPinLine className="w-3 h-3 text-secondary/80 shrink-0" />
                  <span className="truncate">{event.location}</span>
                </>
              )}
              {groupCode && (
                <span className={cn('inline-flex items-center gap-1.5 shrink-0 select-none', event.location && 'ml-1.5')}>
                  <RiGroupLine className="w-3 h-3 text-secondary/80 shrink-0" role="img" aria-label="Group" />
                  {groupCode}
                </span>
              )}
            </div>
          ) : (
            <div />
          )}

          {/* Quick 1-Tap Attendance Check-in on right side of footer */}
          {canTrackAttendance && isToday && (
            <button
              type="button"
              onClick={handleCycleAttendance}
              className="inline-flex items-center justify-center gap-1.5 rounded-full border border-subtle bg-surface-overlay px-2.5 py-1 text-xs font-semibold text-secondary hover:text-primary transition-[color,background-color,border-color,box-shadow,transform,opacity] select-none cursor-pointer hover:scale-105 active:scale-95 shrink-0"
              title="Click to toggle today's attendance status"
            >
              {currentStatus === 'present' ? (
                <>
                  <RiCheckLine className="h-3.5 w-3.5 shrink-0 text-tint-emerald" aria-hidden />
                  <span>Present</span>
                </>
              ) : currentStatus === 'absent' ? (
                <>
                  <RiCloseLine className="h-3.5 w-3.5 shrink-0 text-tint-red" aria-hidden />
                  <span>Absent</span>
                </>
              ) : currentStatus === 'excused' ? (
                <>
                  <RiErrorWarningLine className="h-3.5 w-3.5 shrink-0 text-tint-amber" aria-hidden />
                  <span>Excused</span>
                </>
              ) : currentStatus === 'cancelled' ? (
                <>
                  <RiCalendarCloseLine className="h-3.5 w-3.5 shrink-0 text-tint-red" aria-hidden />
                  <span>Holiday</span>
                </>
              ) : (
                <>
                  <RiRadioButtonLine className="h-3.5 w-3.5 shrink-0 text-muted" aria-hidden />
                  <span>Check in</span>
                </>
              )}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
