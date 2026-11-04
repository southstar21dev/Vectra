import { useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { ConfirmDialog } from '@/components/ui/confirm-dialog';
import { PillRadio } from '@/components/ui/sheet-form';
import { RiAddLine, RiCalendarLine } from '@remixicon/react';
import { SheetSectionTitle } from '@/features/trips/sheet-chrome';
import { toast } from 'sonner';
import {
  calculateAttendanceStats,
  getAttendanceRiskTier,
  deduplicateSessions,
  type AttendanceStats,
  type AttendanceRiskTier,
} from '../attendance-calc';
import { AddSessionDialog } from './add-session-dialog';
import { AttendanceStandingCard, type StandingStrip } from './attendance-standing-card';
import { SessionLedgerRow } from './session-ledger-row';
import { TodaySessionCard } from './today-session-card';
import { STATUS_LABELS } from '../session-status';
import type { StatusTone } from '@/components/ui/status-label';
import { formatSessionDate } from '../session-date';
import type { AttendanceSession, AttendanceStatus, CourseTracker, SessionType } from '../tracker-types';
import { isIntensiveClass, getWeekBeforeStart } from '@/lib/utils';
import { nowInBudapest } from '@/lib/date-utils';
import { useNow } from '@/hooks/use-now';
import { useAppearance } from '@/hooks/use-appearance';
import { getAcademicWeekInfo } from '@/features/schedule/use-week-navigation';

interface AttendanceTabProps {
  tracker: CourseTracker;
  /** Semester start date (YYYY-MM-DD) used to label sessions with academic week numbers. */
  semesterStart?: string | null;
  /** Who teaches a session and where, from the timetable row it came from. */
  sessionMeta?: (session: AttendanceSession) => { instructor?: string; location?: string };
  onSetStatus: (date: string, status: AttendanceStatus) => void;
  onAddSession: (session: AttendanceSession) => void;
  onRemoveSession: (session: AttendanceSession) => void;
  onRestoreSession: (session: AttendanceSession) => void;
}

const SESSION_TYPES: Record<SessionType, { label: string; plural: string; tone: StatusTone }> = {
  lecture: { label: 'Lecture', plural: 'Lectures', tone: 'sky' },
  seminar: { label: 'Seminar', plural: 'Seminars', tone: 'emerald' },
  lab: { label: 'Lab', plural: 'Labs', tone: 'purple' },
  consultation: { label: 'Consultation', plural: 'Consultations', tone: 'amber' },
  exam: { label: 'Exam', plural: 'Exams', tone: 'violet' },
  other: { label: 'Session', plural: 'Other', tone: 'neutral' },
};

/** Ledger order of the type filter and the semester rows. */
const TYPE_ORDER: SessionType[] = ['lecture', 'seminar', 'lab', 'consultation', 'exam', 'other'];

const INTENSIVE = { label: 'Intensive', plural: 'Intensive', tone: 'amber' as StatusTone };

const NO_LIMITS = { maxAbsencesAllowed: null, minAttendancePercent: null };

const byDateAndTime = (a: AttendanceSession, b: AttendanceSession) =>
  a.date.localeCompare(b.date) || (a.startTime || '').localeCompare(b.startTime || '');

export function AttendanceTab({
  tracker,
  semesterStart,
  sessionMeta,
  onSetStatus,
  onAddSession,
  onRemoveSession,
  onRestoreSession,
}: AttendanceTabProps) {
  const { prefs } = useAppearance();
  const [showAddModal, setShowAddModal] = useState(false);
  // The ledger always opens on seminars — the sessions attendance is usually
  // counted on — whichever class card the sheet was opened from.
  const [typeFilter, setTypeFilter] = useState<string>('seminar');
  const [pendingDelete, setPendingDelete] = useState<AttendanceSession | null>(null);
  /** The row a semester dot just brought into view, washed for a moment. */
  const [flashId, setFlashId] = useState<string | null>(null);
  const ledgerRef = useRef<HTMLUListElement | null>(null);

  // Sessions are stored as Budapest dates, so "today" is the Budapest date
  // (not the device's) and it rolls over at midnight while the sheet is open.
  const now = useNow(60_000);
  const todayIso = useMemo(() => nowInBudapest(new Date(now)).ymd, [now]);

  const sessions = useMemo(
    () => deduplicateSessions(tracker.attendance.sessions || []).sort(byDateAndTime),
    [tracker.attendance.sessions]
  );

  const presentTypes = useMemo(
    () => TYPE_ORDER.filter((type) => sessions.some((s) => s.sessionType === type)),
    [sessions]
  );

  // Fall back to "all" when the starting filter has nothing to show. Adjusted
  // during render so the list never paints an empty frame first. Waits for
  // sessions to exist so a ledger still being generated keeps the filter.
  if (sessions.length > 0 && typeFilter !== 'all' && !sessions.some((s) => s.sessionType === typeFilter)) {
    setTypeFilter('all');
  }

  // Unmarked sessions after today are left out of every denominator.
  const statsOptions = useMemo(() => ({ today: todayIso }), [todayIso]);

  const stats: AttendanceStats = useMemo(
    () =>
      calculateAttendanceStats(
        sessions,
        { maxAbsencesAllowed: tracker.attendance.maxAbsencesAllowed, minAttendancePercent: null },
        statsOptions
      ),
    [sessions, tracker.attendance.maxAbsencesAllowed, statsOptions]
  );

  const riskTier: AttendanceRiskTier = getAttendanceRiskTier({
    absentCount: stats.absentCount,
    maxAbsencesAllowed: tracker.attendance.maxAbsencesAllowed,
    attendancePercent: stats.attendancePercent,
    minAttendancePercent: null,
    activeCount: stats.activeCount,
  });

  // An intensive week class is simply a class that happens in the week right
  // before classes start and never happens again after: every session must
  // fall inside that pre-semester week.
  const isIntensive = useMemo(() => {
    if (isIntensiveClass({ course_name: tracker.courseName, course_code: tracker.courseCode })) {
      return true;
    }
    const preWeek = getWeekBeforeStart(semesterStart);
    if (!preWeek) return false;
    const dates = sessions.map((s) => s.date).filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d));
    return dates.length > 0 && dates.every((d) => d >= preWeek.startStr && d <= preWeek.endStr);
  }, [tracker.courseName, tracker.courseCode, semesterStart, sessions]);

  const typeInfo = (type: SessionType) =>
    isIntensive && type === 'seminar' ? INTENSIVE : SESSION_TYPES[type] || SESSION_TYPES.other;

  const sessionDisplay = (session: AttendanceSession) => {
    const info = typeInfo(session.sessionType);
    const cleanTitle = (session.title || '').replace(
      /\s+(lecture|seminar|lab|practice|exam|session|consultation)$/i,
      ''
    );
    // Generated sessions default to a "Week N" title keyed to the generation
    // loop index, which can drift from the academic week number (e.g. a
    // mid-week semester start). When the title is that generic placeholder,
    // show the corrected semester week label used by the schedule tab's week
    // switcher instead. For intensive courses, sessions in the week right
    // before the official semester start are named "Intensive Week".
    const weekInfo = getAcademicWeekInfo(session.date, semesterStart);
    const displayTitle =
      weekInfo && /^week\s+\d+$/i.test(cleanTitle.trim())
        ? isIntensive && weekInfo.weekNumber === 0
          ? 'Intensive Week'
          : weekInfo.label
        : cleanTitle;
    return { displayTitle, badgeInfo: { label: info.label, tone: info.tone } };
  };

  const describe = (session: AttendanceSession) => {
    const { displayTitle, badgeInfo } = sessionDisplay(session);
    const mark = session.status === 'unmarked' ? 'Pending' : STATUS_LABELS[session.status];
    return `${badgeInfo.label}, ${displayTitle}, ${formatSessionDate(session.date, todayIso)}: ${mark}`;
  };

  const todaySessions = sessions.filter((s) => s.date === todayIso);

  // One row of dots per class type; a course with a single type gets one row
  // named for it.
  const strips: StandingStrip[] = presentTypes.map((type) => {
    const typeSessions = sessions.filter((s) => s.sessionType === type);
    const info = typeInfo(type);
    return {
      label: info.plural,
      tone: info.tone,
      sessions: typeSessions,
      stats: calculateAttendanceStats(typeSessions, NO_LIMITS, statsOptions),
    };
  });

  // The whole semester in order, week 1 to the last.
  const filteredSessions = typeFilter === 'all' ? sessions : sessions.filter((s) => s.sessionType === typeFilter);

  // A dot jumps to its row: widen the filter if it hides it, then scroll it
  // into view once it has rendered and wash it briefly so the eye lands on it.
  const selectSession = (session: AttendanceSession) => {
    if (typeFilter !== 'all' && session.sessionType !== typeFilter) setTypeFilter('all');
    setFlashId(session.id);
    // Two frames: the first lets React commit a row the filter only just revealed.
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        ledgerRef.current
          ?.querySelector<HTMLElement>(`[data-session-id="${CSS.escape(session.id)}"]`)
          ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      })
    );
  };

  useEffect(() => {
    if (!flashId) return;
    const timer = setTimeout(() => setFlashId(null), 1200);
    return () => clearTimeout(timer);
  }, [flashId]);

  const performDelete = (session: AttendanceSession) => {
    onRemoveSession(session);
    const { displayTitle } = sessionDisplay(session);
    toast('Session deleted', {
      description: `${formatSessionDate(session.date, todayIso)}${displayTitle ? ` · ${displayTitle}` : ''}`,
      action: { label: 'Undo', onClick: () => onRestoreSession(session) },
      duration: 6000,
    });
  };

  const requestDelete = (session: AttendanceSession) => {
    const isMarked = !!session.status && session.status !== 'unmarked';
    if (isMarked) {
      setPendingDelete(session);
    } else {
      performDelete(session);
    }
  };

  const renderRow = (session: AttendanceSession) => {
    const { displayTitle, badgeInfo } = sessionDisplay(session);
    return (
      <SessionLedgerRow
        key={session.id}
        session={session}
        title={displayTitle}
        badge={badgeInfo}
        todayIso={todayIso}
        timeFormat={prefs.timeFormat}
        flash={flashId === session.id}
        onSetStatus={onSetStatus}
        onRequestDelete={requestDelete}
      />
    );
  };

  const filterOptions = [
    { value: 'all', label: 'All' },
    ...presentTypes.map((type) => ({ value: type, label: typeInfo(type).plural })),
  ];
  const filterColumns = Math.min(4, filterOptions.length) as 2 | 3 | 4;

  return (
    <div className="space-y-6">
      {sessions.length === 0 ? (
        <div className="rounded-3xl card-glass card-depth p-8 text-center">
          <RiCalendarLine className="mx-auto h-8 w-8 text-muted" aria-hidden="true" />
          <p className="mt-3 text-base font-extrabold tracking-tight text-primary">No sessions yet</p>
          <p className="mx-auto mt-1 max-w-xs text-sm text-secondary">
            Sessions appear here once this course is in your timetable. You can also add one by hand.
          </p>
          <Button type="button" size="sm" onClick={() => setShowAddModal(true)} className="mt-4 h-9 gap-1.5 rounded-full px-4">
            <RiAddLine className="h-3.5 w-3.5" aria-hidden="true" />
            Add a session
          </Button>
        </div>
      ) : (
        <>
          {todaySessions.map((session) => {
            const { displayTitle, badgeInfo } = sessionDisplay(session);
            const meta = sessionMeta?.(session) ?? {};
            return (
              <TodaySessionCard
                key={session.id}
                session={session}
                typeLabel={badgeInfo.label}
                weekTitle={displayTitle}
                instructor={meta.instructor}
                location={meta.location}
                timeFormat={prefs.timeFormat}
                onSetStatus={onSetStatus}
              />
            );
          })}

          <AttendanceStandingCard
            maxAbsences={tracker.attendance.maxAbsencesAllowed}
            stats={stats}
            riskTier={riskTier}
            strips={strips}
            todayIso={todayIso}
            describe={describe}
            onSelectSession={selectSession}
          />

          <section className="space-y-3" aria-labelledby="attendance-ledger-title">
            <SheetSectionTitle
              id="attendance-ledger-title"
              action={
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => setShowAddModal(true)}
                  className="h-9 gap-1.5 rounded-full px-3.5 text-[13px] font-bold"
                >
                  <RiAddLine className="h-3.5 w-3.5" aria-hidden="true" />
                  Add
                </Button>
              }
            >
              Sessions
            </SheetSectionTitle>

            {filterOptions.length > 2 && (
              <PillRadio
                value={typeFilter}
                onChange={setTypeFilter}
                options={filterOptions}
                columns={filterColumns}
                labelledBy="attendance-ledger-title"
              />
            )}

            <ul
              ref={ledgerRef}
              aria-label="Sessions"
              className="divide-y divide-subtle overflow-hidden rounded-3xl card-glass card-depth"
            >
              {filteredSessions.map(renderRow)}
            </ul>
          </section>
        </>
      )}

      <AddSessionDialog open={showAddModal} onOpenChange={setShowAddModal} onAddSession={onAddSession} />

      {/* Destructive confirmation for marked sessions (undo toast covers the rest) */}
      <ConfirmDialog
        open={pendingDelete !== null}
        onClose={() => setPendingDelete(null)}
        onConfirm={() => {
          if (pendingDelete) performDelete(pendingDelete);
        }}
        title="Delete this session?"
        description={
          pendingDelete
            ? `This removes the ${formatSessionDate(pendingDelete.date, todayIso)} session marked “${
                STATUS_LABELS[pendingDelete.status] || pendingDelete.status
              }”. You can undo right after.`
            : ''
        }
        confirmText="Delete session"
        cancelText="Keep session"
      />
    </div>
  );
}
