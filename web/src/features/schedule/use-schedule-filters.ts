import { useState, useMemo, useRef, useEffect } from 'react';
import { formatDDMMYYYY } from '@/lib/date-utils';
import { formatYMD, nowInBudapest } from '@/lib/date-utils';
import { isTapGesture, swipeStep } from '@/lib/touch-gestures';
import type { ScheduleEvent } from './use-schedule';
import type { MonthWeek } from './use-week-navigation';

export const dayOrder = [1, 2, 3, 4, 5, 6, 0] as const; // Mon, Tue, Wed, Thu, Fri, Sat, Sun

export function getFormattedDateForDay(
  dayOfWeek: number,
  mondayBaseDate: Date
): { formattedDate: string; isToday: boolean; dateStr: string } {
  const now = nowInBudapest();
  const targetMonBased = dayOfWeek === 0 ? 7 : dayOfWeek;
  const targetDate = new Date(mondayBaseDate);
  targetDate.setDate(mondayBaseDate.getDate() + (targetMonBased - 1));

  const isToday = now.ymd === formatYMD(targetDate);

  const formattedDate = formatDDMMYYYY(targetDate);
  const dateStr = formatYMD(targetDate);

  return { formattedDate, isToday, dateStr };
}

export interface UseScheduleFiltersOptions {
  events: ScheduleEvent[];
  selectedSemester: string;
  activeWeek: MonthWeek;
  weekRange: { startStr: string; endStr: string };
}

export function useScheduleFilters({
  events,
  selectedSemester,
  activeWeek,
  weekRange,
}: UseScheduleFiltersOptions) {

  const [selectedDay, setSelectedDay] = useState<number | 'all'>('all');

  // Filter events
  const filteredEvents = useMemo(() => {
    return events.filter((e) => {
      // Manual classes without a semester specified show up in all semesters
      const matchesSemester = selectedSemester === 'all' || e.semester === selectedSemester || !e.semester;

      const matchesDay = selectedDay === 'all' || e.day_of_week === selectedDay;

      // Filter by semester active date range
      let matchesDateRange = true;
      if (e.semester_start && weekRange.endStr && weekRange.endStr < e.semester_start) {
        matchesDateRange = false;
      }
      if (e.semester_end && weekRange.startStr && weekRange.startStr > e.semester_end) {
        matchesDateRange = false;
      }

      return matchesSemester && matchesDay && matchesDateRange;
    });
  }, [events, selectedSemester, selectedDay, weekRange]);

  // Group filtered events by day and sort chronologically
  const filteredEventsByDay = useMemo(() => {
    const grouped: Record<number, ScheduleEvent[]> = {};
    for (let i = 0; i < 7; i++) grouped[i] = [];
    filteredEvents.forEach((event) => {
      if (grouped[event.day_of_week]) {
        grouped[event.day_of_week].push(event);
      }
    });
    for (let i = 0; i < 7; i++) {
      grouped[i].sort((a, b) => (a.start_time || '').localeCompare(b.start_time || ''));
    }
    return grouped;
  }, [filteredEvents]);

  // Displayed days for grid and agenda
  const displayedDays = useMemo(() => {
    return selectedDay === 'all' ? dayOrder : [selectedDay as (typeof dayOrder)[number]];
  }, [selectedDay]);

  // Memoized formatted date / is-today info per weekday (reused by agenda + grid views)
  const dayDateInfo = useMemo(() => {
    const map: Record<number, { formattedDate: string; isToday: boolean; dateStr: string }> = {};
    for (const day of dayOrder) {
      map[day] = getFormattedDateForDay(day, activeWeek.mondayDate);
    }
    return map;
  }, [activeWeek.mondayDate]);

  // Swipe gesture handlers — horizontal day navigation.
  const swipeStart = useRef<{ x: number; y: number } | null>(null);
  const gestureHandledRef = useRef(false);
  const gestureResetTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (gestureResetTimerRef.current) clearTimeout(gestureResetTimerRef.current);
    };
  }, []);

  const markGestureHandled = () => {
    gestureHandledRef.current = true;
    if (gestureResetTimerRef.current) clearTimeout(gestureResetTimerRef.current);
    gestureResetTimerRef.current = setTimeout(() => {
      gestureHandledRef.current = false;
    }, 200);
  };

  const clearGestureHandled = () => {
    gestureHandledRef.current = false;
  };

  /** True when a swipe already handled the interaction; consumes the flag. */
  const consumeGestureHandled = () => {
    if (!gestureHandledRef.current) return false;
    gestureHandledRef.current = false;
    return true;
  };

  const handleSwipeStart = (e: React.TouchEvent) => {
    const touch = e.targetTouches[0];
    swipeStart.current = { x: touch.clientX, y: touch.clientY };
  };

  const handleSwipeEnd = (e: React.TouchEvent) => {
    const start = swipeStart.current;
    swipeStart.current = null;
    if (!start) return;

    const end = { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };

    if (isTapGesture(start, end)) return;

    const step = swipeStep(start, end);
    if (step === 0) return;

    const weekRing: (number | 'all')[] = ['all', ...dayOrder];
    const currentIndex = weekRing.indexOf(selectedDay);
    if (currentIndex === -1) return;

    const nextIndex = (currentIndex + step + weekRing.length) % weekRing.length;
    setSelectedDay(weekRing[nextIndex]);
    markGestureHandled();
  };

  const pillTouchStart = useRef<{ x: number; y: number } | null>(null);

  const handlePillTouchStart = (e: React.TouchEvent) => {
    const touch = e.targetTouches[0];
    pillTouchStart.current = { x: touch.clientX, y: touch.clientY };
  };

  const handlePillTouchEnd = (e: React.TouchEvent) => {
    const start = pillTouchStart.current;
    pillTouchStart.current = null;
    if (!start) return;

    const end = { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    if (!isTapGesture(start, end)) return;

    const value = (e.target as HTMLElement).closest('button')?.getAttribute('data-value');
    if (value == null) return;

    setSelectedDay(value === 'all' ? 'all' : Number(value));
    markGestureHandled();
  };

  return {
    selectedDay,
    setSelectedDay,
    filteredEvents,
    filteredEventsByDay,
    displayedDays,
    dayDateInfo,
    handleSwipeStart,
    handleSwipeEnd,
    handlePillTouchStart,
    handlePillTouchEnd,
    clearGestureHandled,
    consumeGestureHandled,
  };
}
