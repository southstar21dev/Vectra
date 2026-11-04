import { getItemIntervals, type ScheduleItem, type ScheduleInterval } from './schedule-conflicts';
import { formatMinuteOfDay, isValidCalendarDate } from '@/lib/date-utils';

export interface ScheduleItemOccurrence {
  item: ScheduleItem;
  date: string;
  startTime: string;
  endTime: string;
  continuation: boolean;
}

function toOccurrence(item: ScheduleItem, interval: ScheduleInterval): ScheduleItemOccurrence {
  return {
    item,
    date: interval.date,
    startTime: formatMinuteOfDay(interval.startMinute),
    endTime: formatMinuteOfDay(interval.endMinute),
    continuation: interval.date !== item.date,
  };
}

/** Expand items into day-specific timeline rows for an inclusive date range. */
export function getScheduleItemOccurrences(
  items: readonly ScheduleItem[],
  startDate: string,
  endDate: string
): ScheduleItemOccurrence[] {
  if (!isValidCalendarDate(startDate) || !isValidCalendarDate(endDate) || startDate > endDate) return [];
  const occurrences: ScheduleItemOccurrence[] = [];
  for (const item of items) {
    for (const interval of getItemIntervals(item)) {
      if (interval.date >= startDate && interval.date <= endDate) {
        occurrences.push(toOccurrence(item, interval));
      }
    }
  }
  return occurrences.sort((a, b) =>
    a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime) || a.item.id.localeCompare(b.item.id)
  );
}

/** Get a day's item rows, including the next-day tail of an overnight shift. */
export function getScheduleItemOccurrencesForDate(
  items: readonly ScheduleItem[],
  date: string
): ScheduleItemOccurrence[] {
  return getScheduleItemOccurrences(items, date, date);
}
