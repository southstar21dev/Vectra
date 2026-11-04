import { describe, expect, it } from 'vitest';
import { getWeeksInMonth, resolveDefaultWeekIndex, getAcademicWeekInfo, getOfficialSemesterStart } from './use-week-navigation';
import { getIntensiveCourseKeys } from '@/features/course-tracker/attendance-calc';
import type { ScheduleEvent } from './use-schedule';

describe('getWeeksInMonth', () => {
  it('returns all Monday-to-Sunday weeks for September 2026', () => {
    const sep2026 = new Date(2026, 8, 1); // September 2026 (month is 0-indexed)
    const weeks = getWeeksInMonth(sep2026);

    expect(weeks.length).toBeGreaterThanOrEqual(4);
    // First week begins on or before Sep 1
    expect(weeks[0].startStr).toBe('2026-08-31'); // Monday Aug 31
    expect(weeks[0].endStr).toBe('2026-09-06'); // Sunday Sep 6
    expect(weeks[0].index).toBe(0);

    // Second week
    expect(weeks[1].startStr).toBe('2026-09-07');
    expect(weeks[1].endStr).toBe('2026-09-13');
  });

  it('correctly labels weeks and range strings', () => {
    const oct2026 = new Date(2026, 9, 1);
    const weeks = getWeeksInMonth(oct2026);
    expect(weeks.length).toBeGreaterThanOrEqual(4);
    expect(weeks[0].rangeLabel).toBeTruthy();
  });
});

describe('getAcademicWeekInfo', () => {
  it('labels the semester start week as Week 1', () => {
    // 2026-09-07 is a Monday
    const info = getAcademicWeekInfo('2026-09-07', '2026-09-07');
    expect(info).toEqual({ weekNumber: 1, label: 'Week 1', isStarted: true });
  });

  it('numbers weeks relative to the Monday of the semester start week', () => {
    // Semester starts Wednesday 2026-09-09; its week runs Mon 09-07 to Sun 09-13.
    const start = '2026-09-09';
    expect(getAcademicWeekInfo('2026-09-07', start)).toEqual({ weekNumber: 1, label: 'Week 1', isStarted: true });
    expect(getAcademicWeekInfo('2026-09-10', start)).toEqual({ weekNumber: 1, label: 'Week 1', isStarted: true });
    expect(getAcademicWeekInfo('2026-09-14', start)).toEqual({ weekNumber: 2, label: 'Week 2', isStarted: true });
    expect(getAcademicWeekInfo('2026-09-21', start)).toEqual({ weekNumber: 3, label: 'Week 3', isStarted: true });
  });

  it('labels dates before the semester start week as Pre-semester', () => {
    const info = getAcademicWeekInfo('2026-08-31', '2026-09-09');
    expect(info).toEqual({ weekNumber: 0, label: 'Pre-semester', isStarted: false });
  });

  it('returns null when semester start or date is missing/invalid', () => {
    expect(getAcademicWeekInfo('2026-09-07', null)).toBeNull();
    expect(getAcademicWeekInfo('2026-09-07', undefined)).toBeNull();
    expect(getAcademicWeekInfo('2026-09-07', 'not-a-date')).toBeNull();
    expect(getAcademicWeekInfo('', '2026-09-07')).toBeNull();
    expect(getAcademicWeekInfo('2026-9-7', '2026-09-07')).toBeNull();
  });
});

function event(partial: Partial<ScheduleEvent>): ScheduleEvent {
  return {
    id: 'e1',
    user_id: 'u1',
    course_name: 'Course',
    course_code: '',
    instructor: '',
    location: '',
    day_of_week: 1,
    start_time: '08:00',
    end_time: '09:30',
    color: '#4A90D9',
    semester: '2026/27/1',
    semester_start: null,
    semester_end: null,
    event_type: 'class',
    csv_metadata: null,
    created_at: '2026-01-01T00:00:00.000Z',
    updated_at: '2026-01-01T00:00:00.000Z',
    ...partial,
  } as ScheduleEvent;
}

describe('getOfficialSemesterStart', () => {
  it('ignores an intensive block that runs the week before the official start', () => {
    const events = [
      event({
        course_name: 'Business Communication Intensive',
        semester_start: '2026-08-31',
        semester_end: '2026-09-04',
      }),
      event({ course_name: 'Calculus', semester_start: '2026-09-07', semester_end: '2026-12-15' }),
    ];

    expect(getOfficialSemesterStart(events)).toBe('2026-09-07');
  });

  it('returns the earliest regular event start', () => {
    const events = [
      event({ course_name: 'Physics', semester_start: '2026-09-14', semester_end: '2026-12-15' }),
      event({ course_name: 'Calculus', semester_start: '2026-09-07', semester_end: '2026-12-15' }),
    ];

    expect(getOfficialSemesterStart(events)).toBe('2026-09-07');
  });

  it('falls back to intensive events when every event is intensive', () => {
    const events = [
      event({ course_name: 'Blokk képzés', semester_start: '2026-08-31', semester_end: '2026-09-04' }),
    ];

    expect(getOfficialSemesterStart(events)).toBe('2026-08-31');
  });

  it('anchors on the regular start, ignoring a concentrated intensive week (G10 shape)', () => {
    // One-week intensive block: 18 single-occurrence slots (Mon-Sat, 3/day),
    // each its own event with a 0-day span — indistinguishable per-event from
    // an irregular seminar, but concentrated as a course.
    const intensiveDates = [
      '2026-09-07', '2026-09-08', '2026-09-09', '2026-09-10', '2026-09-11', '2026-09-12',
    ];
    const intensiveEvents: ScheduleEvent[] = [];
    for (const date of intensiveDates) {
      for (const time of ['08:00', '09:50', '11:40']) {
        intensiveEvents.push(
          event({
            course_name: 'Organisational interpersonal skills dev',
            course_code: 'G10',
            semester_start: date,
            semester_end: date,
            start_time: time,
          })
        );
      }
    }
    const regular = event({
      course_name: 'Calculus',
      course_code: 'MATH101-E01',
      semester_start: '2026-09-14',
      semester_end: '2026-12-15',
    });

    // The concentrated intensive week must not move the anchor to Week 1.
    expect(getOfficialSemesterStart([...intensiveEvents, regular])).toBe('2026-09-14');
  });  it('does not treat spread-out irregular seminars (G04 shape) as an intensive block', () => {
    const g04Events = ['2026-09-15', '2026-10-13', '2026-11-03', '2026-12-01'].map((date) =>
      event({
        course_name: 'Organisational interpersonal skills dev',
        course_code: 'G04',
        semester_start: date,
        semester_end: date,
      })
    );

    // Not intensive → its earliest session anchors the numbering normally.
    expect(getOfficialSemesterStart(g04Events)).toBe('2026-09-15');
  });

  it('returns null when no event carries a semester start', () => {
    expect(getOfficialSemesterStart([event({})])).toBeNull();
    expect(getOfficialSemesterStart([])).toBeNull();
  });
});

describe('getIntensiveCourseKeys', () => {
  it('marks a course running in the pre-semester week and never again as intensive', () => {
    const g10Events = ['2026-09-07', '2026-09-08', '2026-09-09', '2026-09-10', '2026-09-11', '2026-09-12'].map(
      (date) =>
        event({
          course_name: 'Organisational interpersonal skills dev',
          course_code: 'G10',
          semester_start: date,
          semester_end: date,
        })
    );
    const regular = event({
      course_name: 'Calculus',
      course_code: 'MATH101-E01',
      semester_start: '2026-09-14',
      semester_end: '2026-12-15',
    });

    const keys = getIntensiveCourseKeys([...g10Events, regular]);
    expect(keys.size).toBe(1);
    expect(keys.has('organisational-interpersonal-skills-dev_2026-27-1')).toBe(true);
  });

  it('does not mark spread-out irregular seminars (G04 shape) as intensive', () => {
    const g04Events = ['2026-09-15', '2026-10-13', '2026-11-03', '2026-12-01'].map((date) =>
      event({
        course_name: 'Organisational interpersonal skills dev',
        course_code: 'G04',
        semester_start: date,
        semester_end: date,
      })
    );

    expect(getIntensiveCourseKeys(g04Events).size).toBe(0);
  });

  it('judges each semester against its own classes-start anchor', () => {
    // Past semester (2025/26/2): classes start 2026-02-02, intensive week 01-26..01-30.
    const pastIntensive = ['2026-01-26', '2026-01-27', '2026-01-28', '2026-01-29', '2026-01-30'].map((date) =>
      event({
        course_name: 'Org skills dev',
        course_code: 'ORG-G10',
        semester: '2025/26/2',
        semester_start: date,
        semester_end: date,
      })
    );
    const pastRegular = event({
      course_name: 'Calculus',
      course_code: 'MATH101',
      semester: '2025/26/2',
      semester_start: '2026-02-02',
      semester_end: '2026-05-29',
    });

    // Current semester (2026/27/1): classes start 2026-09-14, intensive week 09-07..09-11.
    const currentIntensive = ['2026-09-07', '2026-09-08', '2026-09-09', '2026-09-10', '2026-09-11'].map((date) =>
      event({
        course_name: 'Org skills dev',
        course_code: 'ORG-G10',
        semester: '2026/27/1',
        semester_start: date,
        semester_end: date,
      })
    );
    const currentRegular = event({
      course_name: 'Calculus',
      course_code: 'MATH101',
      semester: '2026/27/1',
      semester_start: '2026-09-14',
      semester_end: '2026-12-18',
    });

    const keys = getIntensiveCourseKeys([
      ...pastIntensive,
      pastRegular,
      ...currentIntensive,
      currentRegular,
    ]);

    // The current semester's intensive week must be detected even though an
    // older semester has an earlier classes start.
    expect(keys.has('org-skills-dev_2025-26-2')).toBe(true);
    expect(keys.has('org-skills-dev_2026-27-1')).toBe(true);
  });
});

describe('resolveDefaultWeekIndex', () => {
  it('resolves default week index to semester start week for past semesters', () => {
    // September 2024: 2024-09-01 was a Sunday.
    // Week 0: 2024-08-26 to 2024-09-01
    // Week 1: 2024-09-02 to 2024-09-08
    // Week 2: 2024-09-09 to 2024-09-15
    const sep2024 = new Date(2024, 8, 1);
    const weeks = getWeeksInMonth(sep2024);

    expect(weeks[0].startStr).toBe('2024-08-26');
    expect(weeks[2].startStr).toBe('2024-09-09');

    // A past semester that started on 2024-09-09 should default to Week 2, not Week 0
    const weekIdx = resolveDefaultWeekIndex(weeks, 'past', '2024-09-09', 0);
    expect(weekIdx).toBe(2);
  });

  it('resolves default week index to semester start week for upcoming semesters', () => {
    const sep2026 = new Date(2026, 8, 1);
    const weeks = getWeeksInMonth(sep2026);

    const weekIdx = resolveDefaultWeekIndex(weeks, 'upcoming', '2026-09-07', 0);
    expect(weekIdx).toBe(1);
  });

  it('falls back to 0 if navDateOffset is non-zero', () => {
    const sep2024 = new Date(2024, 8, 1);
    const weeks = getWeeksInMonth(sep2024);

    const weekIdx = resolveDefaultWeekIndex(weeks, 'past', '2024-09-09', 1);
    expect(weekIdx).toBe(0);
  });
});

