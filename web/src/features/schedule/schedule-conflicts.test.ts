import { describe, expect, it } from 'vitest';
import {
  classOccursOnDate,
  conflictIsAcknowledged,
  getItemIntervals,
  getScheduleConflicts,
  isConflictRelevantClass,
  type ScheduleConflictClass,
  type ScheduleItem,
} from './schedule-conflicts';

const shift = (overrides: Partial<ScheduleItem> = {}): ScheduleItem => ({
  id: 'shift-1',
  type: 'shift',
  title: 'Work',
  date: '2026-09-07',
  startTime: '09:00',
  endTime: '12:00',
  acknowledgedConflictKeys: [],
  ...overrides,
});

const scheduleEvent = (overrides: Partial<ScheduleConflictClass> = {}): ScheduleConflictClass => ({
  id: 'class-1',
  source: 'neptun',
  course_name: 'Course',
  course_code: 'CS101-G01',
  day_of_week: 1,
  start_time: '10:00',
  end_time: '11:00',
  semester: '2026/27/1',
  semester_start: '2026-09-07',
  semester_end: '2026-12-14',
  event_type: 'class',
  csv_metadata: {},
  ...overrides,
});

describe('getItemIntervals', () => {
  it('treats a touching endpoint as a non-overlapping half-open boundary', () => {
    const intervals = getItemIntervals(shift({ startTime: '12:00', endTime: '13:00' }));
    expect(intervals).toEqual([{ date: '2026-09-07', startMinute: 720, endMinute: 780 }]);
    expect(getItemIntervals(shift({ startTime: '11:59', endTime: '12:01' }))).toHaveLength(1);
  });

  it('splits overnight shifts into start-day and next-day intervals', () => {
    expect(getItemIntervals(shift({ startTime: '22:30', endTime: '01:15' }))).toEqual([
      { date: '2026-09-07', startMinute: 1350, endMinute: 1440 },
      { date: '2026-09-08', startMinute: 0, endMinute: 75 },
    ]);
  });

  it('rejects equal times, invalid times, dates, and overnight tasks', () => {
    expect(getItemIntervals(shift({ startTime: '09:00', endTime: '09:00' }))).toEqual([]);
    expect(getItemIntervals(shift({ startTime: '25:00', endTime: '26:00' }))).toEqual([]);
    expect(getItemIntervals(shift({ date: '2026-02-30' }))).toEqual([]);
    expect(getItemIntervals(shift({ type: 'task', startTime: '23:00', endTime: '01:00' }))).toEqual([]);
  });
});

describe('classOccursOnDate', () => {
  it('requires the class weekday and includes both semester bounds', () => {
    const event = scheduleEvent({ semester_start: '2026-09-07', semester_end: '2026-09-21' });
    expect(classOccursOnDate(event, '2026-09-07', '2026-09-01')).toBe(true);
    expect(classOccursOnDate(event, '2026-09-21', '2026-09-01')).toBe(true);
    expect(classOccursOnDate(event, '2026-09-14', '2026-09-01')).toBe(true);
    expect(classOccursOnDate(event, '2026-09-08', '2026-09-01')).toBe(false);
    expect(classOccursOnDate(event, '2026-09-28', '2026-09-01')).toBe(false);
  });

  it('uses the first matching weekday from the course anchor for odd/even weeks', () => {
    const event = scheduleEvent({
      semester_start: '2026-09-08', // Tue; first Monday is Week 1 on Sep 14
      semester_end: '2026-10-31',
      csv_metadata: { frequency: 'odd' },
    });
    expect(classOccursOnDate(event, '2026-09-14', '2026-09-01')).toBe(true);
    expect(classOccursOnDate(event, '2026-09-21', '2026-09-01')).toBe(false);
    expect(classOccursOnDate({ ...event, csv_metadata: { frequency: 'even' } }, '2026-09-21', '2026-09-01')).toBe(true);
  });

  it('uses the reference month start if the course has no semester-start date', () => {
    const event = scheduleEvent({ semester_start: null, semester_end: null, csv_metadata: { frequency: 'odd' } });
    // Reference date Sep 1 2026 is Tuesday; Sep 7 is the first Monday (Week 1).
    expect(classOccursOnDate(event, '2026-09-07', '2026-09-01')).toBe(true);
    expect(classOccursOnDate(event, '2026-09-14', '2026-09-01')).toBe(false);
  });

  it('uses the earliest matching class row to establish the semester week-one anchor', () => {
    const mondaySlot = scheduleEvent({
      id: 'monday',
      day_of_week: 1,
      course_code: 'CS101-G01',
      semester_start: '2026-09-07',
      csv_metadata: { frequency: 'odd' },
    });
    const fridaySlot = scheduleEvent({
      id: 'friday',
      day_of_week: 5,
      course_code: 'CS101-G01',
      start_time: '14:00',
      end_time: '15:00',
      semester_start: '2026-09-11',
      csv_metadata: { frequency: 'odd' },
    });
    expect(classOccursOnDate(fridaySlot, '2026-09-11', '2026-09-24', [mondaySlot, fridaySlot])).toBe(true);
    expect(classOccursOnDate(fridaySlot, '2026-09-18', '2026-09-24', [mondaySlot, fridaySlot])).toBe(false);
  });

  it('treats an exam as an individual imported date, not a weekly recurrence', () => {
    const exam = scheduleEvent({
      event_type: 'exam',
      semester_start: '2026-09-07',
      semester_end: '2026-09-07',
    });
    expect(classOccursOnDate(exam, '2026-09-07', '2026-09-01')).toBe(true);
    expect(classOccursOnDate(exam, '2026-09-14', '2026-09-01')).toBe(false);
  });

  it('treats an English-titled exam stored as a class as its individual date', () => {
    // Imported before the title check covered English titles, so the row still
    // says event_type 'class' — the title is what identifies it.
    const legacyExam = scheduleEvent({
      course_name: 'Elements of Accounting Midterm Exam',
      course_code: '',
      day_of_week: 1,
      semester_start: '2026-11-02',
      semester_end: '2026-11-02',
    });
    expect(classOccursOnDate(legacyExam, '2026-11-02', '2026-11-02')).toBe(true);
    expect(classOccursOnDate(legacyExam, '2026-11-09', '2026-11-02')).toBe(false);
  });
});

describe('isConflictRelevantClass', () => {
  it('uses explicit manual attendance type and treats unknown legacy classes conservatively', () => {
    expect(isConflictRelevantClass(scheduleEvent({ source: 'manual', attendance_type: 'lecture' }))).toBe(false);
    expect(isConflictRelevantClass(scheduleEvent({ source: 'manual', attendance_type: 'seminar' }))).toBe(true);
    expect(isConflictRelevantClass(scheduleEvent({ source: 'manual', attendance_type: 'lab' }))).toBe(true);
    expect(isConflictRelevantClass(scheduleEvent({ source: 'manual', attendance_type: 'unknown' }))).toBe(true);
    expect(isConflictRelevantClass(scheduleEvent({ source: 'manual', attendance_type: null, course_code: 'E01' }))).toBe(true);
  });

  it('treats legacy/manual rows without a source marker conservatively even if their code resembles a lecture', () => {
    expect(isConflictRelevantClass(scheduleEvent({ source: undefined, attendance_type: null, course_code: 'CS101-E01' }))).toBe(true);
  });

  it('does not infer manual unknown/legacy classes as lectures from a course code', () => {
    expect(isConflictRelevantClass(scheduleEvent({ source: 'manual', attendance_type: null, course_code: 'CS101-E01' }))).toBe(true);
    expect(isConflictRelevantClass(scheduleEvent({ source: 'manual', attendance_type: 'unknown', course_code: 'CS101-E01' }))).toBe(true);
  });

  it('infers Neptun lecture, seminar, and lab relevance and includes exams', () => {
    expect(isConflictRelevantClass(scheduleEvent({ course_code: 'CS101-E01' }))).toBe(false);
    expect(isConflictRelevantClass(scheduleEvent({ course_code: 'CS101-G01' }))).toBe(true);
    expect(isConflictRelevantClass(scheduleEvent({ course_code: 'CS101-L01' }))).toBe(true);
    expect(isConflictRelevantClass(scheduleEvent({ event_type: 'exam', course_code: '' }))).toBe(true);
    expect(isConflictRelevantClass(scheduleEvent({ course_code: 'UNKNOWN' }))).toBe(true);
    expect(
      isConflictRelevantClass(
        scheduleEvent({ course_name: 'Elements of Accounting Midterm Exam', course_code: 'E01' })
      )
    ).toBe(true);
  });
});

describe('getScheduleConflicts', () => {
  it('finds a class overlap only on an actual occurrence date and excludes a lecture', () => {
    const item = shift();
    const seminar = scheduleEvent();
    const lecture = scheduleEvent({ id: 'lecture', course_code: 'CS101-E01' });
    const conflicts = getScheduleConflicts([item], [seminar, lecture], '2026-09-07');
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0].itemId).toBe(item.id);
    expect(conflicts[0].classId).toBe(seminar.id);
    expect(conflicts[0].startMinute).toBe(600);
    expect(conflicts[0].endMinute).toBe(660);
  });

  it('does not report endpoint-only contact and ignores completed tasks', () => {
    const item = shift({ endTime: '10:00' });
    const seminar = scheduleEvent({ start_time: '10:00', end_time: '11:00' });
    const doneTask = shift({ id: 'task', type: 'task', completed: true, startTime: '09:30', endTime: '10:30' });
    expect(getScheduleConflicts([item, doneTask], [seminar], '2026-09-07')).toHaveLength(0);
  });

  it('detects task/shift overlaps and shares one stable key across both endpoints', () => {
    const a = shift({ id: 'a', startTime: '09:00', endTime: '11:00' });
    const b = shift({ id: 'b', type: 'task', startTime: '10:00', endTime: '12:00' });
    const [conflict] = getScheduleConflicts([a, b], [], '2026-09-07');
    expect(conflict.itemId).toBe('a');
    expect(conflict.otherItemId).toBe('b');
    expect(conflictIsAcknowledged(conflict, [a, b])).toBe(false);
    expect(conflictIsAcknowledged(conflict, [a, { ...b, acknowledgedConflictKeys: [conflict.key] }])).toBe(true);
  });

  it('uses stable Neptun class keys across row replacement while changing with occurrence time', () => {
    const item = shift();
    const oldRow = scheduleEvent({ id: 'old-neptun-row' });
    const newRow = scheduleEvent({ id: 'new-neptun-row' });
    const original = getScheduleConflicts([item], [oldRow], '2026-09-07')[0];
    const afterSync = getScheduleConflicts([item], [newRow], '2026-09-07')[0];
    const moved = getScheduleConflicts([item], [scheduleEvent({ id: 'moved', start_time: '10:30' })], '2026-09-07')[0];
    expect(afterSync.key).toBe(original.key);
    expect(moved.key).not.toBe(original.key);
  });

  it('detects a shift tail overlapping a class the following day', () => {
    const overnight = shift({ startTime: '23:00', endTime: '02:00' });
    const nextMorning = scheduleEvent({
      id: 'next-day',
      day_of_week: 2,
      start_time: '01:00',
      end_time: '01:30',
      semester_start: '2026-09-07',
    });
    expect(getScheduleConflicts([overnight], [nextMorning], '2026-09-07')).toHaveLength(1);
  });

  it('marks conflicts introduced by a later class refresh as needing acknowledgment', () => {
    const item = shift({ acknowledgedConflictKeys: [] });
    const newlySyncedClass = scheduleEvent({ id: 'new-class' });
    const conflicts = getScheduleConflicts([item], [newlySyncedClass], '2026-09-07');
    expect(conflicts).toHaveLength(1);
    expect(conflicts[0].needsAcknowledgment).toBe(true);
  });

  it('does not require a second acknowledgment when either side already acknowledged an item-pair clash', () => {
    const first = shift({ id: 'first' });
    const second = shift({ id: 'second', type: 'task', startTime: '10:00', endTime: '11:00' });
    const conflict = getScheduleConflicts([first, second], [], '2026-09-07')[0];
    const acknowledged = { ...first, acknowledgedConflictKeys: [conflict.key] };
    const refreshed = getScheduleConflicts([acknowledged, second], [], '2026-09-07')[0];
    expect(refreshed.needsAcknowledgment).toBe(false);
  });
});
