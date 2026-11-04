import { describe, expect, it } from 'vitest';
import { extractGroupCode, isIntensiveClass, getClassType, isIntensiveCourseEvents, getWeekBeforeStart } from './utils';

describe('extractGroupCode', () => {
  it('extracts standard group codes from course codes', () => {
    expect(extractGroupCode('INB_G01')).toBe('G01');
    expect(extractGroupCode('MATH_E02')).toBe('E02');
    expect(extractGroupCode('PHYS_L01')).toBe('L01');
    expect(extractGroupCode('CS101-G03')).toBe('G03');
    expect(extractGroupCode('DATA-E01')).toBe('E01');
    expect(extractGroupCode('LAB-L04')).toBe('L04');
  });

  it('extracts single digit group codes formatted as 2 digits', () => {
    expect(extractGroupCode('G1')).toBe('G01');
    expect(extractGroupCode('E2')).toBe('E02');
    expect(extractGroupCode('L3')).toBe('L03');
    expect(extractGroupCode('PROG_G2')).toBe('G02');
  });

  it('extracts isolated group codes', () => {
    expect(extractGroupCode('G01')).toBe('G01');
    expect(extractGroupCode('E01')).toBe('E01');
    expect(extractGroupCode('L01')).toBe('L01');
    expect(extractGroupCode('g01')).toBe('G01');
  });

  it('returns null when no group code is present', () => {
    expect(extractGroupCode(null)).toBe(null);
    expect(extractGroupCode(undefined)).toBe(null);
    expect(extractGroupCode('')).toBe(null);
    expect(extractGroupCode('INB-101')).toBe(null);
    expect(extractGroupCode('CALCULUS')).toBe(null);
  });
});

describe('isIntensiveClass', () => {
  it('detects intensive classes from keywords', () => {
    expect(isIntensiveClass({ course_name: 'Intensive Workshop' })).toBe(true);
    expect(isIntensiveClass({ course_name: 'Blokk képzés' })).toBe(true);
    expect(isIntensiveClass({ event_type: 'intensive' })).toBe(true);
    expect(isIntensiveClass({ course_code: 'INTENSIVE_G01' })).toBe(true);
  });

  it('does not match plain course names/codes without intensive keywords', () => {
    expect(
      isIntensiveClass({
        course_name: 'Organisational interpersonal skills dev',
        course_code: 'G04',
        semester_start: '2026-09-15',
        semester_end: '2026-09-15',
      })
    ).toBe(false);
  });
});

describe('getWeekBeforeStart', () => {
  it('returns the Monday–Sunday week right before the anchor week', () => {
    // 2026-09-07 is a Monday → its week is 09-07..09-13, so the week before is 08-31..09-06.
    expect(getWeekBeforeStart('2026-09-07')).toEqual({ startStr: '2026-08-31', endStr: '2026-09-06' });
  });

  it('returns the pre-week for a mid-week anchor', () => {
    // 2026-09-09 is a Wednesday → same week Monday is 09-07 → pre-week is 08-31..09-06.
    expect(getWeekBeforeStart('2026-09-09')).toEqual({ startStr: '2026-08-31', endStr: '2026-09-06' });
  });

  it('returns null for missing/invalid anchors', () => {
    expect(getWeekBeforeStart(null)).toBeNull();
    expect(getWeekBeforeStart(undefined)).toBeNull();
    expect(getWeekBeforeStart('not-a-date')).toBeNull();
  });
});

describe('isIntensiveCourseEvents', () => {
  // Classes start Monday 2026-09-14 → the intensive week is 09-07..09-13.
  const OFFICIAL_START = '2026-09-14';

  it('detects a course that runs in the pre-semester week and never again', () => {
    // One-week intensive block: many single-occurrence slots (Mon-Sat, 3 per day),
    // each its own single-date event, all inside the week before classes start.
    const intensiveDates = [
      '2026-09-07', '2026-09-08', '2026-09-09', '2026-09-10', '2026-09-11', '2026-09-12',
    ];
    const events = [];
    for (const date of intensiveDates) {
      for (const _time of ['08:00', '09:50', '11:40']) {
        events.push({
          course_name: 'Organisational interpersonal skills dev',
          course_code: 'G10',
          semester_start: date,
          semester_end: date,
          event_type: 'seminar',
        });
      }
    }
    expect(isIntensiveCourseEvents(events, OFFICIAL_START)).toBe(true);
  });

  it('does NOT detect a course with a pre-semester session that continues after classes start', () => {
    const events = [
      { course_name: 'Business Skills', course_code: 'BS101', semester_start: '2026-09-08', semester_end: '2026-09-08' },
      { course_name: 'Business Skills', course_code: 'BS101', semester_start: '2026-09-15', semester_end: '2026-12-15' },
    ];
    expect(isIntensiveCourseEvents(events, OFFICIAL_START)).toBe(false);
  });

  it('does NOT detect irregular seminars spread across the semester (G04 shape)', () => {
    const events = ['2026-09-15', '2026-10-13', '2026-11-03', '2026-12-01'].map((date) => ({
      course_name: 'Organisational interpersonal skills dev',
      course_code: 'G04',
      semester_start: date,
      semester_end: date,
      event_type: 'seminar',
    }));
    expect(isIntensiveCourseEvents(events, OFFICIAL_START)).toBe(false);
  });

  it('detects keyword-based intensive courses regardless of dates', () => {
    expect(
      isIntensiveCourseEvents(
        [{ course_name: 'Blokk képzés', semester_start: '2026-09-07', semester_end: '2026-12-18' }],
        OFFICIAL_START
      )
    ).toBe(true);
  });

  it('does NOT detect a full-semester weekly course', () => {
    expect(
      isIntensiveCourseEvents(
        [{
          course_name: 'Calculus I',
          course_code: 'G01',
          semester_start: '2026-09-14',
          semester_end: '2026-12-18',
        }],
        OFFICIAL_START
      )
    ).toBe(false);
  });
});

describe('getClassType', () => {
  it('returns Seminar for group-suffixed seminar codes', () => {
    expect(getClassType('G10')?.label).toBe('Seminar');
    expect(getClassType('7SO20NGMA01M-G04')?.label).toBe('Seminar');
    expect(getClassType('G01')?.label).toBe('Seminar');
  });

  it('returns Intensive only for codes that carry an intensive keyword', () => {
    expect(getClassType('INTENSIVE_G01')?.label).toBe('Intensive');
  });
});

