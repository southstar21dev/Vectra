import { useState, useMemo, useRef, useEffect } from 'react';
import { formatYMD, nowInBudapest } from '@/lib/date-utils';
import { useBudapestToday } from '@/hooks/use-budapest-today';
import {
  getIntensiveCourseKeys,
  getOfficialSemesterStart,
  resolveCourseKey,
} from '@/features/course-tracker/attendance-calc';
import type { ScheduleEvent } from './use-schedule';
import type { SemesterInfo } from './semester-status-banner';

// Re-exported for the Course Hub modal and tests.
export { getOfficialSemesterStart };

/** Canonical course-tracker key for a schedule event. */
function resolveCourseKeyForEvent(e: ScheduleEvent): string {
  return resolveCourseKey({
    courseCode: e.course_code,
    courseName: e.course_name,
    semester: e.semester,
  });
}

export interface MonthWeek {
  index: number;
  mondayDate: Date;
  sundayDate: Date;
  startStr: string; // YYYY-MM-DD
  endStr: string; // YYYY-MM-DD
  rangeLabel: string;
  isCurrentWeek: boolean;
}

export interface AcademicWeekInfo {
  weekNumber: number;
  label: string;
  isStarted: boolean;
}

/**
 * Returns all Monday-to-Sunday weeks that overlap with the target month.
 */
export function getWeeksInMonth(targetDate: Date): MonthWeek[] {
  const year = targetDate.getFullYear();
  const month = targetDate.getMonth();
  const firstDayOfMonth = new Date(year, month, 1);
  const lastDayOfMonth = new Date(year, month + 1, 0);

  // Find the Monday of the week containing the 1st day of the month
  const firstDayWeekday = firstDayOfMonth.getDay(); // 0 = Sun, 1 = Mon ...
  const firstMonDiff = 1 - (firstDayWeekday === 0 ? 7 : firstDayWeekday);
  const startMon = new Date(firstDayOfMonth);
  startMon.setDate(firstDayOfMonth.getDate() + firstMonDiff);

  const nowYMD = nowInBudapest().ymd;

  const formatShort = (d: Date) => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });

  const weeks: MonthWeek[] = [];
  const currMon = new Date(startMon);
  let index = 0;

  while (currMon <= lastDayOfMonth || (index > 0 && currMon.getMonth() === month)) {
    const currSun = new Date(currMon);
    currSun.setDate(currMon.getDate() + 6);

    const startStr = formatYMD(currMon);
    const endStr = formatYMD(currSun);
    const isCurrentWeek = nowYMD >= startStr && nowYMD <= endStr;

    weeks.push({
      index,
      mondayDate: new Date(currMon),
      sundayDate: new Date(currSun),
      startStr,
      endStr,
      rangeLabel: `${formatShort(currMon)} – ${formatShort(currSun)}`,
      isCurrentWeek,
    });

    index++;
    currMon.setDate(currMon.getDate() + 7);
  }

  return weeks;
}

/**
 * Computes the academic semester week info for a given date relative to the
 * semester start date, mirroring exactly how the schedule tab's week switcher
 * numbers weeks: the week containing the semester start is Week 1, and any
 * date whose week falls before that is "Pre-semester".
 *
 * Returns null when either date is missing or not a valid YYYY-MM-DD string.
 */
export function getAcademicWeekInfo(
  dateStr: string,
  semesterStartStr: string | null | undefined
): AcademicWeekInfo | null {
  if (
    !semesterStartStr ||
    !/^\d{4}-\d{2}-\d{2}$/.test(semesterStartStr) ||
    !/^\d{4}-\d{2}-\d{2}$/.test(dateStr)
  ) {
    return null;
  }

  const [sy, sm, sd] = semesterStartStr.split('-').map(Number);
  const semStartDate = new Date(sy, sm - 1, sd);
  const semStartDay = semStartDate.getDay();
  const semStartMonDiff = 1 - (semStartDay === 0 ? 7 : semStartDay);
  const semStartMonday = new Date(sy, sm - 1, sd + semStartMonDiff);

  const [ay, am, ad] = dateStr.split('-').map(Number);
  const targetDate = new Date(ay, am - 1, ad);
  const targetDay = targetDate.getDay();
  const targetMonDiff = 1 - (targetDay === 0 ? 7 : targetDay);
  const targetMonday = new Date(ay, am - 1, ad + targetMonDiff);

  const diffMs = targetMonday.getTime() - semStartMonday.getTime();
  const diffDays = Math.round(diffMs / (1000 * 60 * 60 * 24));
  const weekNumber = Math.floor(diffDays / 7) + 1;

  if (weekNumber < 1) {
    return {
      weekNumber,
      label: 'Pre-semester',
      isStarted: false,
    };
  }

  return {
    weekNumber,
    label: `Week ${weekNumber}`,
    isStarted: true,
  };
}

/**
 * Finds the default week index for the active month:
 * - For upcoming or past semesters at base navigation (offset 0), selects the week containing the semester start date.
 * - For active semesters, selects the current calendar week.
 * - Defaults to week index 0.
 */
export function resolveDefaultWeekIndex(
  monthWeeks: MonthWeek[],
  semesterStatus: string,
  startDate: string | null | undefined,
  navDateOffset: number = 0
): number {
  if (monthWeeks.length === 0) return 0;

  // If semester is upcoming or past and we have a start date in this month, default to the start week
  if ((semesterStatus === 'upcoming' || semesterStatus === 'past') && startDate && navDateOffset === 0) {
    const startIdx = monthWeeks.findIndex(
      (w) => startDate >= w.startStr && startDate <= w.endStr
    );
    if (startIdx !== -1) return startIdx;
  }

  // If active semester, find the current calendar week
  const currentIdx = monthWeeks.findIndex((w) => w.isCurrentWeek);
  if (currentIdx !== -1) return currentIdx;

  return 0;
}

/** Local-midnight Date for a YYYY-MM-DD string. */
function ymdToDate(ymd: string): Date {
  const [y, m, d] = ymd.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/**
 * Index of the first week after (or the last week before) the week starting
 * on `startStr`, used when navigation crosses into the next/previous month.
 */
export function resolveAdjacentWeekIndex(
  monthWeeks: MonthWeek[],
  direction: 'after' | 'before',
  startStr: string
): number {
  if (monthWeeks.length === 0) return 0;
  if (direction === 'after') {
    const idx = monthWeeks.findIndex((w) => w.startStr > startStr);
    return idx === -1 ? monthWeeks.length - 1 : idx;
  }
  for (let i = monthWeeks.length - 1; i >= 0; i--) {
    if (monthWeeks[i].startStr < startStr) return i;
  }
  return 0;
}

/**
 * The selected week after the month's weeks were recomputed. A new month or
 * semester context starts on its default week; within the same context (the
 * day rolled over at midnight) the user's week is kept, and the default is only
 * followed when the user was still on the old default.
 */
export function resolveWeekIndexAfterRecompute(input: {
  contextChanged: boolean;
  current: number | null;
  previousDefault: number;
  nextDefault: number;
}): number {
  const { contextChanged, current, previousDefault, nextDefault } = input;
  if (contextChanged || current === null || current === previousDefault) return nextDefault;
  return current;
}

export interface UseWeekNavigationOptions {
  events: ScheduleEvent[];
  selectedSemester: string;
}

export function useWeekNavigation({ events, selectedSemester }: UseWeekNavigationOptions) {
  const [navDateOffset, setNavDateOffset] = useState<number>(0);
  // `null` until the user navigates: while unset, the shown week is the
  // computed default. Initialising at 0 and snapping to the default in an
  // effect re-keyed the schedule card (keyed by week) and replayed its
  // entrance animation — the "shows twice" flicker.
  const [userWeekIndex, setUserWeekIndex] = useState<number | null>(null);
  const todayStr = useBudapestToday();

  // Calculate active semester info & base week date for header dates. The
  // anchor is derived from date strings, not a fresh `new Date()`, so a
  // refetch of `events` does not hand out a new anchor and reset the user's
  // selected week.
  const semesterStatus = useMemo<SemesterInfo & { baseYmd: string }>(() => {

    // Filter events by selected semester for base date calculation
    const relevantEvents = selectedSemester === 'all' ? events : events.filter(e => e.semester === selectedSemester);

    const starts = relevantEvents.map((e) => e.semester_start).filter(Boolean) as string[];
    const ends = relevantEvents.map((e) => e.semester_end).filter(Boolean) as string[];

    if (starts.length === 0) return { baseYmd: todayStr, status: 'current', startDate: null, daysUntil: 0 };

    const minStart = starts.reduce((a, b) => (a < b ? a : b));
    const maxEnd = ends.length > 0 ? ends.reduce((a, b) => (a > b ? a : b)) : minStart;

    // If today is within semester start and end range:
    if (todayStr >= minStart && todayStr <= maxEnd) {
      return { baseYmd: todayStr, status: 'active', startDate: minStart, daysUntil: 0 };
    }

    // If today is before semester start:
    if (todayStr < minStart) {
      const daysUntil = Math.max(1, Math.round((ymdToDate(minStart).getTime() - ymdToDate(todayStr).getTime()) / 86_400_000));
      return { baseYmd: minStart, status: 'upcoming', startDate: minStart, daysUntil };
    }

    // If today is after semester end (past semester):
    return { baseYmd: minStart, status: 'past', startDate: minStart, daysUntil: 0 };
  }, [events, selectedSemester, todayStr]);

  const semesterInfo = useMemo<SemesterInfo & { baseDate: Date }>(() => {
    const { baseYmd, ...info } = semesterStatus;
    return { ...info, baseDate: ymdToDate(baseYmd) };
  }, [semesterStatus]);
  const baseYmd = semesterStatus.baseYmd;

  // Compute active month anchor date based on month offset navigation. Always
  // the 1st: offsetting the 31st by a month would roll past a short month.
  const activeMonthDate = useMemo(() => {
    const base = ymdToDate(baseYmd);
    return new Date(base.getFullYear(), base.getMonth() + navDateOffset, 1);
  }, [baseYmd, navDateOffset]);

  const monthYearLabel = useMemo(() => {
    const monthNames = [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December'
    ];
    return `${monthNames[activeMonthDate.getMonth()]} ${activeMonthDate.getFullYear()}`;
  }, [activeMonthDate]);

  // Weeks contained in the active month
  // `todayStr` keeps `isCurrentWeek` right after midnight / a week rollover.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const monthWeeks = useMemo(() => getWeeksInMonth(activeMonthDate), [activeMonthDate, todayStr]);

  // Find default week index for the active month (semester start week if upcoming/past, else current calendar week)
  const defaultWeekIndex = useMemo(() => {
    return resolveDefaultWeekIndex(
      monthWeeks,
      semesterInfo.status,
      semesterInfo.startDate,
      navDateOffset
    );
  }, [monthWeeks, semesterInfo.status, semesterInfo.startDate, navDateOffset]);

  // Cross-month navigation lands relative to the week the user left: a week
  // spanning two months belongs to both lists, so landing on index 0 / last
  // would show the same week again.
  const targetWeekRef = useRef<{ direction: 'after' | 'before'; startStr: string } | null>(null);

  // The selected week resets only when the month or the semester context
  // changes. `monthWeeks` is also recomputed at midnight (for isCurrentWeek),
  // and resetting on that threw away the week the user had navigated to.
  const resetKey = `${activeMonthDate.getTime()}|${semesterInfo.status}|${semesterInfo.startDate ?? ''}`;
  const lastResetKeyRef = useRef<string | null>(null);
  const lastDefaultRef = useRef<number>(defaultWeekIndex);

  useEffect(() => {
    const previousDefault = lastDefaultRef.current;
    lastDefaultRef.current = defaultWeekIndex;
    const contextChanged = lastResetKeyRef.current !== resetKey;
    lastResetKeyRef.current = resetKey;

    const target = targetWeekRef.current;
    if (target) {
      targetWeekRef.current = null;
      setUserWeekIndex(resolveAdjacentWeekIndex(monthWeeks, target.direction, target.startStr));
      return;
    }
    setUserWeekIndex((current) =>
      resolveWeekIndexAfterRecompute({ contextChanged, current, previousDefault, nextDefault: defaultWeekIndex })
    );
  }, [monthWeeks, defaultWeekIndex, resetKey]);

  // Until the user navigates, use the computed default week so the first paint
  // already shows the right week (no week-0 flash / entrance replay).
  const resolvedWeekIndex = userWeekIndex ?? defaultWeekIndex;

  const activeWeek = useMemo<MonthWeek>(() => {
    return monthWeeks[resolvedWeekIndex] || monthWeeks[0] || {
      index: 0,
      mondayDate: activeMonthDate,
      sundayDate: activeMonthDate,
      startStr: '',
      endStr: '',
      rangeLabel: '',
      isCurrentWeek: false,
    };
  }, [monthWeeks, resolvedWeekIndex, activeMonthDate]);

  const weekRange = useMemo(() => {
    return {
      startStr: activeWeek.startStr,
      endStr: activeWeek.endStr,
    };
  }, [activeWeek]);

  // Navigate to previous week (cross-month support)
  const handlePrevWeek = () => {
    if (resolvedWeekIndex > 0) {
      setUserWeekIndex(resolvedWeekIndex - 1);
    } else {
      targetWeekRef.current = { direction: 'before', startStr: activeWeek.startStr };
      setNavDateOffset((prev) => prev - 1);
    }
  };

  // Navigate to next week (cross-month support)
  const handleNextWeek = () => {
    if (resolvedWeekIndex < monthWeeks.length - 1) {
      setUserWeekIndex(resolvedWeekIndex + 1);
    } else {
      targetWeekRef.current = { direction: 'after', startStr: activeWeek.startStr };
      setNavDateOffset((prev) => prev + 1);
    }
  };

  const handleResetNavigation = () => {
    targetWeekRef.current = null;
    setNavDateOffset(0);
    setUserWeekIndex(defaultWeekIndex);
  };

  const isAtDefaultWeek = navDateOffset === 0 && resolvedWeekIndex === defaultWeekIndex;

  // Calculate academic semester week number (e.g. Week 1, Week 2... after semester starts)
  const academicWeekInfo = useMemo<AcademicWeekInfo | null>(() => {
    if (!semesterInfo.startDate) {
      return null;
    }

    // Anchor on the official semester start, ignoring intensive block events
    // that run before it so their week is not counted as a semester week.
    const semesterEvents =
      selectedSemester === 'all'
        ? events
        : events.filter((e) => e.semester === selectedSemester);
    const officialStart = getOfficialSemesterStart(semesterEvents);
    if (!officialStart) {
      return null;
    }

    const info = getAcademicWeekInfo(formatYMD(activeWeek.mondayDate), officialStart);
    if (!info) {
      return null;
    }

    // The week right before the official semester start: if it hosts an
    // intensive block, name it "Intensive Week" instead of "Pre-semester" —
    // it doesn't count toward the semester week counter.
    if (info.weekNumber === 0) {
      const intensiveKeys = getIntensiveCourseKeys(semesterEvents);
      const hasIntensiveBlockInWeek = semesterEvents.some(
        (e) =>
          e.semester_start !== null &&
          e.semester_start >= activeWeek.startStr &&
          e.semester_start <= activeWeek.endStr &&
          intensiveKeys.has(resolveCourseKeyForEvent(e))
      );
      if (hasIntensiveBlockInWeek) {
        return { ...info, label: 'Intensive Week' };
      }
    }

    return info;
  }, [
    semesterInfo.startDate,
    activeWeek.mondayDate,
    activeWeek.startStr,
    activeWeek.endStr,
    events,
    selectedSemester,
  ]);

  return {
    navDateOffset,
    selectedWeekIndex: resolvedWeekIndex,
    setSelectedWeekIndex: setUserWeekIndex,
    semesterInfo,
    activeMonthDate,
    monthYearLabel,
    monthWeeks,
    defaultWeekIndex,
    activeWeek,
    weekRange,
    handlePrevWeek,
    handleNextWeek,
    handleResetNavigation,
    isAtDefaultWeek,
    academicWeekInfo,
  };
}
