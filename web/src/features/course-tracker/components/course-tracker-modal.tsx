import { useId, useMemo, useRef, useState, useEffect, useCallback } from 'react';
import { Dialog, DialogContent } from '@/components/ui/dialog';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { RiCalendarCheckLine, RiAwardLine, RiEqualizerLine } from '@remixicon/react';
import { SheetBar, SheetBody, SHEET_GUTTER } from '@/features/trips/sheet-chrome';
import { useCourseTracker } from '../use-course-tracker';
import { AttendanceTab } from './attendance-tab';
import { PointsTab } from './points-tab';
import { SettingsTab } from './settings-tab';
import { generateSessionsFromScheduleEvents, getEventSessionType, isSameCourse, normalizeTime } from '../attendance-calc';
import type { ScheduleEvent } from '@/features/schedule/use-schedule';
import type { AttendanceSession } from '../tracker-types';
import { getOfficialSemesterStart } from '@/features/schedule/use-week-navigation';
import { cn } from '@/lib/utils';

/**
 * Each pane runs the sheet gutter and clears the bar above and the home
 * indicator below. The panes are flush with the body's scroll edges so the
 * body's edge fade dissolves whatever is half under the bar.
 */
const TAB_PANE_CLASS = cn('mt-0 pt-4 pb-8', SHEET_GUTTER);

interface CourseTrackerModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onExited?: () => void;
  courseName: string;
  courseCode?: string;
  semester?: string;
  instructor?: string;
  location?: string;
  color?: string;
  dayOfWeek?: number;
  startTime?: string;
  endTime?: string;
  eventType?: string;
  scheduleEvents?: ScheduleEvent[];
}

/** Order-independent identity of a session list (slot + mark), used to tell
 *  whether reconciling against the schedule would actually change anything. */
function sessionsSignature(sessions: AttendanceSession[]): string {
  return sessions
    .map((s) => `${s.date}|${s.startTime || ''}|${s.endTime || ''}|${s.sessionType}|${s.status}`)
    .sort()
    .join('\n');
}

export function CourseTrackerModal({
  open,
  onOpenChange,
  onExited,
  courseName,
  courseCode = '',
  semester = '',
  instructor,
  location,
  color,
  dayOfWeek,
  startTime,
  endTime,
  eventType,
  scheduleEvents = [],
}: CourseTrackerModalProps) {
  const titleId = useId();
  const [activeTab, setActiveTab] = useState('attendance');
  const [scrolled, setScrolled] = useState(false);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  // Each pane starts at its top: a ledger scrolled deep into the semester
  // must not leave the settings pane opening mid-way down.
  const selectTab = useCallback((next: string) => {
    setActiveTab(next);
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }, []);
  const {
    isLoading,
    getOrCreateTracker,
    setAttendanceStatus,
    addAttendanceSession,
    removeAttendanceSession,
    restoreAttendanceSession,
    addAssessment,
    updateAssessment,
    removeAssessment,
    updateGradingScale,
    updateLimits,
    confirmTrackerSettings,
    upsertTracker,
  } = useCourseTracker();

  const identity = useMemo(
    () => ({ courseName, courseCode, semester, instructor, location, color }),
    [courseName, courseCode, semester, instructor, location, color]
  );

  const tracker = useMemo(() => {
    if (!courseName) return null;
    return getOrCreateTracker(identity);
  }, [courseName, identity, getOrCreateTracker]);

  // Semester start date used to label sessions with academic week numbers,
  // mirroring the schedule tab's week switcher: the official semester start
  // (earliest semester_start among non-intensive events, across the course's
  // semester — or all events when the course has no semester label). An
  // intensive week running before the semester must not count as Week 1.
  const semesterStartDate = useMemo<string | null>(() => {
    const semLabel = (semester || '').toLowerCase().trim();
    const relevantEvents = semLabel
      ? scheduleEvents.filter((e) => (e.semester || '').toLowerCase().trim() === semLabel)
      : scheduleEvents;
    return getOfficialSemesterStart(relevantEvents);
  }, [scheduleEvents, semester]);

  // Auto-populate and reconcile sessions with schedule events. Runs whenever
  // the generated list differs from the stored one — growing (new slots) or
  // shrinking (a class dropped from the schedule). The generator itself keeps
  // every marked or custom session, so only untouched slots are ever pruned.
  // `lastReconciled` stops a non-idempotent regeneration from writing in a loop.
  const lastReconciled = useRef<string | null>(null);
  useEffect(() => {
    if (isLoading || !tracker) return;
    if (scheduleEvents && scheduleEvents.length > 0) {
      const currentSessions = tracker.attendance?.sessions || [];
      const generated = generateSessionsFromScheduleEvents({
        courseName,
        courseCode,
        semester,
        dayOfWeek,
        startTime,
        endTime,
        eventType,
        scheduleEvents,
        existingSessions: currentSessions,
        removedSessionKeys: tracker.attendance?.removedSessionKeys || [],
      });
      const nextSignature = sessionsSignature(generated);
      if (
        generated.length > 0 &&
        nextSignature !== sessionsSignature(currentSessions) &&
        nextSignature !== lastReconciled.current
      ) {
        lastReconciled.current = nextSignature;
        upsertTracker({
          ...tracker,
          attendance: {
            ...tracker.attendance,
            sessions: generated,
          },
        });
      }
    }
  }, [isLoading, tracker, courseName, courseCode, semester, dayOfWeek, startTime, endTime, eventType, scheduleEvents, upsertTracker]);

  // Who teaches a session and where: the timetable row it was generated from
  // (same course, weekday, start and type), else the class the sheet opened on.
  const sessionMeta = useCallback(
    (session: AttendanceSession) => {
      const [y, m, d] = session.date.split('-').map(Number);
      const weekday = y && m && d ? new Date(y, m - 1, d).getDay() : -1;
      const start = normalizeTime(session.startTime);
      const match = scheduleEvents.find(
        (e) =>
          isSameCourse(e, { course_name: courseName, course_code: courseCode }) &&
          e.day_of_week === weekday &&
          (!start || normalizeTime(e.start_time) === start) &&
          getEventSessionType(e) === session.sessionType
      );
      return {
        instructor: match?.instructor || instructor,
        location: match?.location || location,
      };
    },
    [scheduleEvents, courseName, courseCode, instructor, location]
  );

  if (!tracker) return null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange} onExited={onExited}>
      <DialogContent
        edgeToEdge
        aria-labelledby={titleId}
        className="sm:max-w-lg sm:h-[85vh] sm:max-h-[800px] rounded-[28px] overflow-hidden p-0 gap-0 bg-surface-primary"
      >
        <Tabs value={activeTab} onValueChange={selectTab} className="flex min-h-0 flex-1 flex-col">
          <SheetBar
            leading="close"
            onLeading={() => onOpenChange(false)}
            title={courseName}
            titleId={titleId}
            wrapTitle
            className="bg-surface-primary"
            scrolled={scrolled}
            accessory={
              <TabsList className="grid w-full grid-cols-3">
                <TabsTrigger value="attendance" aria-label="Attendance" className="gap-1.5 px-2">
                  <RiCalendarCheckLine className="h-4 w-4 shrink-0" aria-hidden />
                  <span>Attendance</span>
                </TabsTrigger>
                <TabsTrigger value="points" aria-label="Grades" className="gap-1.5 px-2">
                  <RiAwardLine className="h-4 w-4 shrink-0" aria-hidden />
                  <span>Grades</span>
                </TabsTrigger>
                <TabsTrigger value="settings" aria-label="Rules" className="gap-1.5 px-2">
                  <RiEqualizerLine className="h-4 w-4 shrink-0" aria-hidden />
                  <span>Rules</span>
                </TabsTrigger>
              </TabsList>
            }
          />

          <SheetBody ref={bodyRef} onScrolledChange={setScrolled}>
            <TabsContent value="attendance" className={TAB_PANE_CLASS}>
              <AttendanceTab
                tracker={tracker}
                semesterStart={semesterStartDate}
                sessionMeta={sessionMeta}
                onSetStatus={(date, status) =>
                  setAttendanceStatus(
                    { courseCode: tracker.courseCode, courseName: tracker.courseName, semester: tracker.semester },
                    date,
                    status
                  )
                }
                onAddSession={(session) => addAttendanceSession(tracker.courseKey, session)}
                onRemoveSession={(session) => removeAttendanceSession(tracker.courseKey, session)}
                onRestoreSession={(session) => restoreAttendanceSession(tracker.courseKey, session)}
              />
            </TabsContent>

            <TabsContent value="points" className={TAB_PANE_CLASS}>
              <PointsTab
                tracker={tracker}
                onAddAssessment={(item) => addAssessment(identity, item)}
                onUpdateAssessment={(id, patch) => updateAssessment(identity, id, patch)}
                onRemoveAssessment={(id) => removeAssessment(identity, id)}
              />
            </TabsContent>

            <TabsContent value="settings" className={TAB_PANE_CLASS}>
              <SettingsTab
                tracker={tracker}
                onUpdateLimits={(limits) => updateLimits(tracker.courseKey, limits)}
                onUpdateGradingScale={(scale) => updateGradingScale(tracker.courseKey, scale)}
                onConfirmSettings={() => confirmTrackerSettings(tracker.courseKey)}
              />
            </TabsContent>
          </SheetBody>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
}
