import { useMemo } from 'react';
import { cn } from '@/lib/utils';
import { PostageStamp } from '@/components/ui/postage-stamp';
import { formatDayMonth, getDayName } from '@/lib/date-utils';
import { nowInBudapest } from '@/lib/date-utils';
import type { ScheduleEvent } from './use-schedule';
import { RiResetLeftLine } from '@remixicon/react';
import type { ScheduleConflict, ScheduleItem } from './schedule-conflicts';
import { classOccursOnDate, getScheduleConflicts } from './schedule-conflicts';
import type { StoredScheduleItem } from './schedule-items-store';
import { getScheduleItemOccurrences } from './schedule-occurrences';
import { ScheduleDayTimeline } from './schedule-day-timeline';
import { getIntensiveCourseKeys } from '@/features/course-tracker/attendance-calc';
import { Reveal } from '@/components/ui/reveal';
import { useAppPresented } from '@/hooks/use-app-presented';
import { SemesterStatusBanner } from './semester-status-banner';
import { shouldShowSemesterStatus } from './semester-status-banner-logic';
import { ScheduleToolbar } from './schedule-toolbar';
import { motion, AnimatePresence } from 'framer-motion';
import { useWeekNavigation } from './use-week-navigation';
import { useScheduleFilters } from './use-schedule-filters';

interface ScheduleViewProps {
  eventsByDay: Record<number, ScheduleEvent[]>;
  events: ScheduleEvent[];
  onEventClick: (event: ScheduleEvent) => void;
  actions?: React.ReactNode;
  semesters?: string[];
  currentSemester?: string;
  scheduleItems?: StoredScheduleItem[];
  conflictsByEventId?: ReadonlyMap<string, ScheduleConflict[]>;
  syncConflictIds?: ReadonlySet<string>;
  needsAcknowledgmentIds?: ReadonlySet<string>;
  onItemClick?: (item: ScheduleItem) => void;
  onToggleTaskCompleted?: (item: ScheduleItem) => void;
  onResolveSyncConflict?: (id: string, resolution: 'server' | 'local') => void;
}

export function ScheduleView({
  eventsByDay: _eventsByDay,
  events,
  onEventClick,
  actions,
  semesters = [],
  currentSemester,
  scheduleItems = [],
  conflictsByEventId: _conflictsByEventId,
  syncConflictIds,
  needsAcknowledgmentIds: externallyNeededAcknowledgmentIds,
  onItemClick,
  onToggleTaskCompleted,
  onResolveSyncConflict,
}: ScheduleViewProps) {
  const presented = useAppPresented();
  const isTouchDevice = useMemo(
    () => typeof window !== 'undefined' && ('ontouchstart' in window || navigator.maxTouchPoints > 0),
    []
  );
  // No semester picker: the view always shows the current semester, falling
  // back to the newest one when the current semester has no events yet.
  const selectedSemester = useMemo(() => {
    if (currentSemester && semesters.includes(currentSemester)) return currentSemester;
    if (semesters.length > 0) return semesters[semesters.length - 1];
    return 'all';
  }, [semesters, currentSemester]);
  const weekNav = useWeekNavigation({ events, selectedSemester });
  // An empty week (autumn break, exam period) is not an empty timetable.
  const hasAnyScheduleItems = events.length > 0 || scheduleItems.length > 0;
  const intensiveCourseKeys = useMemo(() => getIntensiveCourseKeys(events), [events]);
  const viewConflicts = useMemo(
    () => getScheduleConflicts(scheduleItems, events, nowInBudapest().ymd),
    [scheduleItems, events]
  );
  const viewConflictsByEventDate = useMemo(() => {
    const grouped = new Map<string, ScheduleConflict[]>();
    for (const conflict of viewConflicts) {
      if (!conflict.classId) continue;
      const key = `${conflict.date}:${conflict.classId}`;
      grouped.set(key, [...(grouped.get(key) || []), conflict]);
    }
    return grouped;
  }, [viewConflicts]);
  const viewConflictsByItemDate = useMemo(() => {
    const grouped = new Map<string, ScheduleConflict[]>();
    for (const conflict of viewConflicts) {
      for (const id of [conflict.itemId, conflict.otherItemId].filter((value): value is string => !!value)) {
        const key = `${conflict.date}:${id}`;
        grouped.set(key, [...(grouped.get(key) || []), conflict]);
      }
    }
    return grouped;
  }, [viewConflicts]);
  const viewNeedsAcknowledgmentIds = useMemo(
    () => new Set([
      ...viewConflicts.filter((conflict) => conflict.needsAcknowledgment).flatMap((conflict) => [conflict.itemId, conflict.otherItemId].filter((id): id is string => !!id)),
      ...(externallyNeededAcknowledgmentIds || []),
    ]),
    [externallyNeededAcknowledgmentIds, viewConflicts]
  );
  const viewSyncConflictIds = useMemo(
    () => new Set([...(syncConflictIds || []), ...scheduleItems.filter((item) => item.syncConflict).map((item) => item.id)]),
    [scheduleItems, syncConflictIds]
  );

  const filters = useScheduleFilters({
    events,
    selectedSemester,
    activeWeek: weekNav.activeWeek,
    weekRange: weekNav.weekRange,
  });
  const filteredItemOccurrences = useMemo(() => {
    const expanded = getScheduleItemOccurrences(scheduleItems, weekNav.activeWeek.startStr, weekNav.activeWeek.endStr);
    return expanded.filter(({ date }) => {
      const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
      return filters.selectedDay === 'all' || filters.selectedDay === weekday;
    });
  }, [scheduleItems, weekNav.activeWeek.startStr, weekNav.activeWeek.endStr, filters.selectedDay]);
  // Anchor for semester/frequency math on events that carry no semester bounds.
  // Memoized on mount so the grid/clash memos below keep a stable dependency.
  const referenceDate = useMemo(() => nowInBudapest().ymd, []);
  const eventsForDate = (day: number, dateStr: string) => (filters.filteredEventsByDay[day] || []).filter((event) =>
    classOccursOnDate(event, dateStr, event.semester_start || referenceDate, events)
  );
  const itemsForDate = (dateStr: string) => filteredItemOccurrences.filter((entry) => entry.date === dateStr);
  return (
    <div
      className="space-y-4 min-w-0 mx-auto max-w-3xl"
      onTouchStartCapture={filters.clearGestureHandled}
      onClickCapture={(event) => {
        if (filters.consumeGestureHandled()) {
          event.preventDefault();
          event.stopPropagation();
        }
      }}
    >
      {/* The toolbar (0) and the semester banner (1) fade and rise into place
          in reading order; the agenda card below does its own. */}
      <Reveal index={0}>
        <ScheduleToolbar
          actions={actions}
          semesters={semesters}
          selectedSemester={selectedSemester}
          activeWeek={weekNav.activeWeek}
          onPrevWeek={weekNav.handlePrevWeek}
          onNextWeek={weekNav.handleNextWeek}
          onResetNavigation={weekNav.handleResetNavigation}
          isAtDefaultWeek={weekNav.isAtDefaultWeek}
          semesterInfo={weekNav.semesterInfo}
          academicWeekInfo={weekNav.academicWeekInfo}
          selectedDay={filters.selectedDay}
          onDayChange={filters.setSelectedDay}
          onPillTouchStart={filters.handlePillTouchStart}
          onPillTouchEnd={filters.handlePillTouchEnd}
        />
      </Reveal>
      {shouldShowSemesterStatus(weekNav.semesterInfo, selectedSemester) && (
        <Reveal index={1}>
          <SemesterStatusBanner
            semesterInfo={weekNav.semesterInfo}
            selectedSemester={selectedSemester}
            activeWeekRangeLabel={weekNav.activeWeek.rangeLabel}
            isAtDefaultWeek={weekNav.isAtDefaultWeek}
            onResetNavigation={weekNav.handleResetNavigation}
          />
        </Reveal>
      )}

      {/* The card below is keyed by week, so it re-mounts on every week and
          view switch — wrapping it in `Reveal` would replay the card entrance
          on each switch. It instead holds the entrance it already had until
          `useAppPresented` says the app is on screen, and the switches keep
          their quick crossfade. */}
      <AnimatePresence mode="wait">
        {(() => {
          const activeDays = filters.displayedDays.filter((day) => {
            const date = filters.dayDateInfo[day].dateStr;
            return eventsForDate(day, date).length > 0 || itemsForDate(date).length > 0;
          });
          const renderDayCard = (day: number) => {
            const { isToday, dateStr } = filters.dayDateInfo[day];
            const dayEvents = eventsForDate(day, dateStr);
            const dayItems = itemsForDate(dateStr);
            const totalCount = dayEvents.length + dayItems.length;
            if (totalCount === 0 && filters.selectedDay !== 'all') {
              return (
                <div key={day} onTouchStart={filters.handleSwipeStart} onTouchEnd={filters.handleSwipeEnd} className="card-glass schedule-flush-card rounded-3xl p-8 text-center touch-pan-y shadow-md">
                  <p className="text-xs font-bold text-secondary">{hasAnyScheduleItems ? `Nothing scheduled on ${getDayName(day)}` : "You don't have any schedule items"}</p>
                  {isTouchDevice && <p className="mt-2 text-[10px] text-muted">Swipe left or right to browse other days</p>}
                </div>
              );
            }
            if (totalCount === 0) return null;
            return (
              <div key={day} onTouchStart={filters.handleSwipeStart} onTouchEnd={filters.handleSwipeEnd} className={cn('relative mt-6 card-glass schedule-flush-card rounded-3xl shadow-md transition-[color,background-color,border-color,box-shadow,transform,opacity] touch-pan-y w-full', isToday && 'border border-accent-primary/40 shadow-[0_0_24px_rgba(56,189,248,0.15)]')}>
                {/* The day as a postage stamp stuck over the card's top edge,
                    tilted alternately like the transit and settings titles. */}
                <PostageStamp
                  className={cn(
                    'absolute -top-5 left-4 z-10',
                    day % 2 === 0 ? 'rotate-2' : '-rotate-3',
                    // Today's stamp glows in the accent instead of saying "Today".
                    isToday && 'shadow-[0_0_22px_color-mix(in_srgb,var(--accent-primary)_55%,transparent),0_6px_16px_rgba(15,23,42,0.14)]'
                  )}
                  innerClassName={cn('px-3 pt-1.5 pb-1', isToday && 'border-solid border-accent-primary')}
                >
                  <h3 className="text-base font-extrabold leading-none tracking-tight text-primary">{getDayName(day)}</h3>
                  <span className="text-[11px] font-semibold leading-none tabular-nums text-accent-primary">
                    {formatDayMonth(dateStr)}
                  </span>
                </PostageStamp>
                <div className="px-3 pt-8 pb-3 sm:px-4 sm:pb-4">
                  <ScheduleDayTimeline
                    events={dayEvents}
                    items={dayItems}
                    dateStr={dateStr}
                    isToday={isToday}
                    onEventClick={onEventClick}
                    onItemClick={onItemClick}
                    onToggleTaskCompleted={onToggleTaskCompleted}
                    onResolveSyncConflict={onResolveSyncConflict}
                    syncConflictIds={viewSyncConflictIds}
                    needsAcknowledgmentIds={viewNeedsAcknowledgmentIds}
                    itemConflictsById={viewConflictsByItemDate}
                    classConflictsByEventId={viewConflictsByEventDate}
                    intensiveCourses={intensiveCourseKeys}
                  />
                </div>
              </div>
            );
          };

          return (
            <motion.div key={`agenda-${weekNav.activeWeek.startStr}`} initial={{ opacity: 0, y: 10 }} animate={presented ? { opacity: 1, y: 0 } : { opacity: 0, y: 10 }} exit={{ opacity: 0, y: -10 }} transition={{ duration: 0.2 }} className="space-y-4 w-full">
              {activeDays.length === 0 ? (
                <div onTouchStart={filters.handleSwipeStart} onTouchEnd={filters.handleSwipeEnd} className="card-glass schedule-flush-card rounded-3xl p-10 text-center space-y-3 touch-pan-y shadow-md w-full">
                  <div className="w-12 h-12 rounded-2xl bg-surface-overlay border border-subtle flex items-center justify-center mx-auto text-muted font-mono text-xl">📅</div>
                  <h3 className="text-sm font-bold text-primary">{filters.selectedDay !== 'all'
                    ? `Nothing scheduled on ${getDayName(filters.selectedDay)}`
                    : events.length > 0
                      ? 'No classes this week'
                      : hasAnyScheduleItems
                        ? 'Nothing scheduled this week'
                        : "You don't have any schedule items"}</h3>
                  {isTouchDevice && <p className="text-[11px] text-muted">Swipe left or right to browse other days</p>}
                  {!weekNav.isAtDefaultWeek && (
                    <button onClick={weekNav.handleResetNavigation} className="inline-flex items-center gap-1.5 rounded-full border border-subtle bg-surface-overlay px-3 py-1.5 text-xs font-semibold text-secondary hover:text-primary transition-colors cursor-pointer mt-2">
                      <RiResetLeftLine className="w-3.5 h-3.5 text-tint-blue" aria-hidden />
                      <span>{weekNav.semesterInfo.status === 'upcoming' ? 'Jump to semester start' : 'Jump to current week'}</span>
                    </button>
                  )}
                </div>
              ) : filters.selectedDay !== 'all' ? filters.displayedDays.map(renderDayCard) : activeDays.map(renderDayCard)}
            </motion.div>
          );
        })()}

      </AnimatePresence>
    </div>
  );
}
