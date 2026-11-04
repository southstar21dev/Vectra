import { isIntensiveClass, getClassType } from '@/lib/utils';
import { getEventFrequency, type ScheduleEvent } from './use-schedule';
import { getIntensiveCourseKeys, isSameCourse, resolveCourseKey } from '@/features/course-tracker/attendance-calc';
import { isExamEvent } from './exam-detection';
import { isHungarianPublicHoliday } from '@/features/holidays/hungarian-holidays';

export interface ScheduleItem {
  id: string;
  type: 'shift' | 'task';
  title: string;
  date: string;
  startTime: string;
  endTime: string;
  location?: string;
  completed?: boolean;
  acknowledgedConflictKeys: string[];
  /** True only for the second timeline segment of an overnight shift. */
  continuation?: boolean;
}

export type ScheduleConflictClass = Pick<
  ScheduleEvent,
  | 'id'
  | 'course_name'
  | 'course_code'
  | 'day_of_week'
  | 'start_time'
  | 'end_time'
  | 'semester'
  | 'semester_start'
  | 'semester_end'
  | 'event_type'
  | 'csv_metadata'
> & {
  source?: 'manual' | 'neptun';
  attendance_type?: 'lecture' | 'seminar' | 'lab' | 'unknown' | null;
};

export interface ScheduleInterval {
  date: string;
  startMinute: number;
  endMinute: number;
}

export interface ScheduleConflict {
  key: string;
  itemId: string;
  otherItemId?: string;
  classId?: string;
  classKey?: string;
  otherTitle: string;
  otherType: 'shift' | 'task' | 'class' | 'exam';
  date: string;
  startMinute: number;
  endMinute: number;
  needsAcknowledgment: boolean;
}

const VALID_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const VALID_TIME = /^(\d{2}):(\d{2})$/;
const MINUTES_PER_DAY = 24 * 60;

function parseDate(value: string): { year: number; month: number; day: number } | null {
  const match = VALID_DATE.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return { year, month, day };
}

function dateFromParts(year: number, monthIndex: number, day: number): string {
  const date = new Date(Date.UTC(year, monthIndex, day));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`;
}

function datePartsFromOrdinal(ordinal: number): { year: number; month: number; day: number } {
  const date = new Date(ordinal * MINUTES_PER_DAY * 60_000);
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1, day: date.getUTCDate() };
}

function ordinalForDate(value: string): number | null {
  const parts = parseDate(value);
  if (!parts) return null;
  return Math.floor(Date.UTC(parts.year, parts.month - 1, parts.day) / (MINUTES_PER_DAY * 60_000));
}

function formatDateOrdinal(ordinal: number): string {
  const parts = datePartsFromOrdinal(ordinal);
  return `${parts.year}-${String(parts.month).padStart(2, '0')}-${String(parts.day).padStart(2, '0')}`;
}

function weekdayForDate(value: string): number | null {
  const ordinal = ordinalForDate(value);
  if (ordinal === null) return null;
  return new Date(ordinal * MINUTES_PER_DAY * 60_000).getUTCDay();
}

function minuteOfDay(value: string): number | null {
  const match = VALID_TIME.exec(value);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > 23 || minute > 59) return null;
  return hour * 60 + minute;
}

/**
 * Returns the actual date-specific intervals occupied by an item. Intervals
 * are half-open, represented in Budapest-local calendar minutes.
 */
export function getItemIntervals(item: ScheduleItem): ScheduleInterval[] {
  const ordinal = ordinalForDate(item.date);
  const startMinute = minuteOfDay(item.startTime);
  const endMinute = minuteOfDay(item.endTime);
  if (ordinal === null || startMinute === null || endMinute === null || startMinute === endMinute) return [];

  if (endMinute > startMinute) return [{ date: item.date, startMinute, endMinute }];
  if (item.type !== 'shift') return [];

  const intervals: ScheduleInterval[] = [];
  if (startMinute < MINUTES_PER_DAY) intervals.push({ date: item.date, startMinute, endMinute: MINUTES_PER_DAY });
  if (endMinute > 0) intervals.push({ date: formatDateOrdinal(ordinal + 1), startMinute: 0, endMinute });
  return intervals;
}

function getOccurrenceInterval(event: ScheduleConflictClass): ScheduleInterval | null {
  const startMinute = minuteOfDay(event.start_time);
  const endMinute = minuteOfDay(event.end_time);
  if (startMinute === null || endMinute === null || endMinute <= startMinute) return null;
  return { date: '', startMinute, endMinute };
}

// Shared with the importer and the card, so an exam whose row predates the
// English-title check is treated as an exam everywhere rather than only on the
// badge (see exam-detection.ts).
const isExam = isExamEvent;

function hasIntensiveKeyword(event: ScheduleConflictClass): boolean {
  return isIntensiveClass({
    course_name: event.course_name,
    course_code: event.course_code,
    event_type: event.event_type,
  });
}

/** True when the class/exam is a mandatory-attendance conflict candidate. */
export function isConflictRelevantClass(event: ScheduleConflictClass, intensive = false): boolean {
  if (isExam(event) || intensive || hasIntensiveKeyword(event)) return true;
  // Any non-Neptun event is manual (including legacy rows whose source marker
  // predates this feature), so missing/unknown classification is conservative.
  // Do not infer a lecture from its course code until explicitly classified.
  if (event.source !== 'neptun') {
    return event.attendance_type ? event.attendance_type !== 'lecture' : true;
  }
  const inferred = getClassType(event.course_code || event.event_type);
  if (inferred?.label === 'Intensive') return true;
  if (inferred?.label === 'Lecture') return false;
  if (inferred?.label === 'Seminar' || inferred?.label === 'Lab') return true;
  if (event.event_type === 'lecture') return false;
  if (event.event_type === 'seminar' || event.event_type === 'lab') return true;
  return true;
}

function getReferenceDateParts(referenceDate: string): { year: number; month: number; day: number } | null {
  return parseDate(referenceDate);
}

function getSemesterWeekOneStart(
  event: ScheduleConflictClass,
  referenceDate: string,
  scheduleEvents: readonly ScheduleConflictClass[]
): string | null {
  // Attendance generation anchors odd/even weeks at the earliest scheduled
  // date among the matching course slots, not each row's own start date.
  let matchingEvents = scheduleEvents.filter((candidate) =>
    !isExam(candidate) && isSameCourse(
      { course_name: event.course_name, course_code: event.course_code },
      { course_name: candidate.course_name, course_code: candidate.course_code }
    )
  );
  const semester = (event.semester || '').trim().toLocaleLowerCase('en-US');
  if (semester) {
    const matchingSemester = matchingEvents.filter(
      (candidate) => (candidate.semester || '').trim().toLocaleLowerCase('en-US') === semester
    );
    if (matchingSemester.length > 0) matchingEvents = matchingSemester;
  }

  const starts = matchingEvents
    .map((candidate) => candidate.semester_start)
    .filter((start): start is string => !!start && parseDate(start) !== null)
    .sort();
  if (starts.length > 0) return starts[0];

  // Match attendance generation's first-of-current-month fallback, but anchor
  // it to the reference date so historical/future dates remain deterministic.
  const reference = getReferenceDateParts(referenceDate);
  if (!reference) return null;
  return dateFromParts(reference.year, reference.month - 1, 1);
}

/** Checks whether a weekly class meets on a candidate Budapest-local date. */
export function classOccursOnDate(
  event: ScheduleConflictClass,
  dateStr: string,
  referenceDate: string = dateStr,
  scheduleEvents: readonly ScheduleConflictClass[] = [event]
): boolean {
  const candidateOrdinal = ordinalForDate(dateStr);
  const candidateWeekday = weekdayForDate(dateStr);
  const candidateStart = minuteOfDay(event.start_time);
  const candidateEnd = minuteOfDay(event.end_time);
  if (candidateOrdinal === null || candidateWeekday === null || candidateStart === null || candidateEnd === null || candidateEnd <= candidateStart) return false;

  const dateStart = event.semester_start && parseDate(event.semester_start) ? event.semester_start : null;
  const dateEnd = event.semester_end && parseDate(event.semester_end) ? event.semester_end : null;
  if (isExam(event)) return dateStart === dateStr && dateEnd === dateStr;
  if (candidateWeekday !== event.day_of_week) return false;
  if (dateStart && dateStr < dateStart) return false;
  if (dateEnd && dateStr > dateEnd) return false;
  // A manual weekly class repeats blindly, but nobody teaches on a public
  // holiday. Neptun rows need no check: the feed omits that occurrence and the
  // importer's date ranges split around it.
  if (event.source !== 'neptun' && isHungarianPublicHoliday(dateStr)) return false;

  const weekOneStart = getSemesterWeekOneStart(event, referenceDate, scheduleEvents);
  const weekOneOrdinal = weekOneStart ? ordinalForDate(weekOneStart) : null;
  if (weekOneOrdinal === null) return false;
  const anchorWeekday = weekdayForDate(weekOneStart!);
  if (anchorWeekday === null) return false;
  const firstOccurrenceOrdinal = weekOneOrdinal + ((event.day_of_week - anchorWeekday + 7) % 7);
  const weeksSinceFirstOccurrence = candidateOrdinal - firstOccurrenceOrdinal;
  if (weeksSinceFirstOccurrence < 0 || weeksSinceFirstOccurrence % 7 !== 0) return false;
  const weekNumber = Math.floor(weeksSinceFirstOccurrence / 7) + 1;

  const frequency = getEventFrequency(event.csv_metadata);
  if (frequency === 'even' && weekNumber % 2 !== 0) return false;
  if (frequency === 'odd' && weekNumber % 2 === 0) return false;
  return true;
}

function normalizeKeyPart(value: string): string {
  return value.trim().toLocaleLowerCase('en-US').replace(/\s+/g, ' ');
}

/** Stable occurrence key that survives Neptun's replace-all row IDs. */
export function getClassConflictKey(event: ScheduleConflictClass, date: string): string {
  return [
    'class',
    event.source || 'manual',
    normalizeKeyPart(event.course_code || ''),
    normalizeKeyPart(event.course_name || ''),
    normalizeKeyPart(event.semester || ''),
    date,
    event.start_time.slice(0, 5),
    event.end_time.slice(0, 5),
    isExam(event) ? 'exam' : 'class',
  ].join(':');
}

function getItemPairConflictKey(a: ScheduleItem, b: ScheduleItem, date: string, start: number, end: number): string {
  const ids = [a.id, b.id].sort();
  const aIntervals = getItemIntervals(a).filter((interval) => interval.date === date).map((interval) => `${interval.startMinute}-${interval.endMinute}`).sort().join(',');
  const bIntervals = getItemIntervals(b).filter((interval) => interval.date === date).map((interval) => `${interval.startMinute}-${interval.endMinute}`).sort().join(',');
  const intervalParts = [aIntervals, bIntervals].sort();
  return `items:${ids[0]}:${ids[1]}:${intervalParts[0]}:${intervalParts[1]}:${date}:${start}-${end}`;
}

function intervalsOverlap(a: ScheduleInterval, b: ScheduleInterval): { startMinute: number; endMinute: number } | null {
  if (a.date !== b.date) return null;
  const startMinute = Math.max(a.startMinute, b.startMinute);
  const endMinute = Math.min(a.endMinute, b.endMinute);
  return startMinute < endMinute ? { startMinute, endMinute } : null;
}

function isCompletedTask(item: ScheduleItem): boolean {
  return item.type === 'task' && item.completed === true;
}

function itemAcknowledged(item: ScheduleItem, key: string): boolean {
  return item.acknowledgedConflictKeys.includes(key);
}

/** Return active clashes for personal items and class occurrences in a date range. */
export function getScheduleConflicts(
  items: ScheduleItem[],
  events: ScheduleConflictClass[],
  referenceDate: string,
  dates?: readonly string[]
): ScheduleConflict[] {
  const allowedDates = dates ? new Set(dates) : null;
  const conflicts: ScheduleConflict[] = [];
  const intensiveKeys = getIntensiveCourseKeys(events.filter((event) => !isExam(event)).map((event) => ({
    id: event.id,
    user_id: '',
    course_name: event.course_name,
    course_code: event.course_code,
    instructor: '',
    location: '',
    day_of_week: event.day_of_week,
    start_time: event.start_time,
    end_time: event.end_time,
    color: '#000000',
    semester: event.semester,
    semester_start: event.semester_start,
    semester_end: event.semester_end,
    event_type: event.event_type,
    attendance_type: event.attendance_type ?? null,
    csv_metadata: event.csv_metadata,
    created_at: '',
    updated_at: '',
  }) as ScheduleEvent));
  const activeItems = items.filter((item) => !isCompletedTask(item));
  const itemIntervals = new Map(activeItems.map((item) => [item.id, getItemIntervals(item)]));

  for (let i = 0; i < activeItems.length; i++) {
    const item = activeItems[i];
    for (let j = i + 1; j < activeItems.length; j++) {
      const other = activeItems[j];
      for (const a of itemIntervals.get(item.id) || []) {
        if (allowedDates && !allowedDates.has(a.date)) continue;
        for (const b of itemIntervals.get(other.id) || []) {
          const overlap = intervalsOverlap(a, b);
          if (!overlap) continue;
          const key = getItemPairConflictKey(item, other, a.date, overlap.startMinute, overlap.endMinute);
          const acknowledged = itemAcknowledged(item, key) || itemAcknowledged(other, key);
          conflicts.push({ key, itemId: item.id, otherItemId: other.id, otherTitle: other.title, otherType: other.type, date: a.date, ...overlap, needsAcknowledgment: !acknowledged });
        }
      }
    }
  }

  for (const item of activeItems) {
    for (const itemInterval of itemIntervals.get(item.id) || []) {
      if (allowedDates && !allowedDates.has(itemInterval.date)) continue;
      for (const event of events) {
        const isIntensive = intensiveKeys.has(resolveCourseKey({
          courseCode: event.course_code,
          courseName: event.course_name,
          semester: event.semester,
        }));
        if (!isConflictRelevantClass(event, isIntensive) || !classOccursOnDate(event, itemInterval.date, referenceDate, events)) continue;
        const eventInterval = getOccurrenceInterval(event);
        if (!eventInterval) continue;
        eventInterval.date = itemInterval.date;
        const overlap = intervalsOverlap(itemInterval, eventInterval);
        if (!overlap) continue;
        const key = getClassConflictKey(event, itemInterval.date);
        conflicts.push({
          key,
          itemId: item.id,
          classId: event.id,
          classKey: key,
          otherTitle: event.course_name,
          otherType: isExam(event) ? 'exam' : 'class',
          date: itemInterval.date,
          ...overlap,
          needsAcknowledgment: !itemAcknowledged(item, key),
        });
      }
    }
  }

  return conflicts.sort((a, b) => a.date.localeCompare(b.date) || a.startMinute - b.startMinute || a.itemId.localeCompare(b.itemId));
}

export function conflictIsAcknowledged(conflict: ScheduleConflict, items: ScheduleItem[]): boolean {
  const target = items.find((item) => item.id === conflict.itemId);
  if (target && itemAcknowledged(target, conflict.key)) return true;
  if (conflict.otherItemId) {
    const other = items.find((item) => item.id === conflict.otherItemId);
    return !!other && itemAcknowledged(other, conflict.key);
  }
  return false;
}
