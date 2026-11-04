import { describe, it, expect } from 'vitest';
import {
  generateScheduleSessions,
  generateSessionsFromScheduleEvents,
  calculateAttendanceStats,
  getAttendanceRiskTier,
  deduplicateSessions,
  resolveCourseKey,
  isSameCourse,
} from './attendance-calc';
import type { AttendanceSession } from './tracker-types';
import type { ScheduleEvent } from '@/features/schedule/use-schedule';

describe('attendance-calc', () => {
  it('never generates attendance sessions for exam-only schedule events', () => {
    const exam: ScheduleEvent = {
      id: 'exam-1',
      course_name: 'Accounting',
      course_code: 'ACC101',
      day_of_week: 4,
      start_time: '10:00',
      end_time: '12:00',
      semester: '2026/27/1',
      semester_start: '2026-10-01',
      semester_end: '2026-10-01',
      event_type: 'exam',
    };
    expect(generateSessionsFromScheduleEvents({
      courseName: 'Accounting',
      courseCode: 'ACC101',
      semester: '2026/27/1',
      dayOfWeek: 4,
      startTime: '10:00',
      endTime: '12:00',
      scheduleEvents: [exam],
    })).toEqual([]);
  });

  it('respects explicit manual attendance type over the inferred course-code type', () => {
    const manualUnknown: ScheduleEvent = {
      id: 'manual-unknown',
      course_name: 'Unknown class',
      course_code: 'CS101-E01',
      attendance_type: 'unknown',
      day_of_week: 1,
      start_time: '10:00',
      end_time: '12:00',
      semester_start: '2026-09-07',
      semester_end: '2026-09-07',
      event_type: 'class',
    };
    const sessions = generateSessionsFromScheduleEvents({
      courseName: 'Unknown class',
      courseCode: 'CS101-E01',
      scheduleEvents: [manualUnknown],
    });
    expect(sessions[0].sessionType).toBe('other');
  });

  it('generates weekly calendar sessions given schedule parameters', () => {
    const sessions = generateScheduleSessions({
      dayOfWeek: 1, // Monday
      startTime: '08:15',
      endTime: '09:45',
      semesterStart: '2025-09-01', // Mon Sep 01 2025
      semesterEnd: '2025-10-01',   // 1 month ~ 5 Mondays
      sessionType: 'lecture',
      frequency: 'weekly',
    });

    expect(sessions.length).toBeGreaterThanOrEqual(4);
    expect(sessions[0].date).toBe('2025-09-01');
    expect(sessions[0].startTime).toBe('08:15');
    expect(sessions[0].status).toBe('unmarked');
  });

  it('respects even/odd week frequency filters', () => {
    const weekly = generateScheduleSessions({
      dayOfWeek: 1,
      startTime: '10:00',
      endTime: '12:00',
      semesterStart: '2025-09-01',
      semesterEnd: '2025-10-01',
      frequency: 'weekly',
    });
    const even = generateScheduleSessions({
      dayOfWeek: 1,
      startTime: '10:00',
      endTime: '12:00',
      semesterStart: '2025-09-01',
      semesterEnd: '2025-10-01',
      frequency: 'even',
    });
    expect(even.length).toBeLessThan(weekly.length);
  });

  it('preserves existing marked session statuses when regenerating', () => {
    const existing: AttendanceSession[] = [
      {
        id: 's1',
        date: '2025-09-01',
        title: 'Week 1',
        sessionType: 'lecture',
        status: 'present',
        notes: 'Intro chapter',
      },
    ];

    const merged = generateScheduleSessions({
      dayOfWeek: 1,
      startTime: '08:15',
      endTime: '09:45',
      semesterStart: '2025-09-01',
      semesterEnd: '2025-10-01',
      existingSessions: existing,
    });

    const first = merged.find((s) => s.date === '2025-09-01');
    expect(first?.status).toBe('present');
    expect(first?.notes).toBe('Intro chapter');
  });

  it('calculates attendance statistics correctly', () => {
    const sessions: AttendanceSession[] = [
      { id: '1', date: '2025-09-01', title: 'W1', sessionType: 'lecture', status: 'present' },
      { id: '2', date: '2025-09-08', title: 'W2', sessionType: 'lecture', status: 'present' },
      { id: '3', date: '2025-09-15', title: 'W3', sessionType: 'lecture', status: 'absent' },
      { id: '4', date: '2025-09-22', title: 'W4', sessionType: 'lecture', status: 'excused' },
      { id: '5', date: '2025-09-29', title: 'W5', sessionType: 'lecture', status: 'cancelled' },
      { id: '6', date: '2025-10-06', title: 'W6', sessionType: 'lecture', status: 'unmarked' },
    ];

    const stats = calculateAttendanceStats(sessions, { maxAbsencesAllowed: 3, minAttendancePercent: 70 });
    expect(stats.totalSessions).toBe(6);
    expect(stats.presentCount).toBe(2);
    expect(stats.absentCount).toBe(1);
    expect(stats.excusedCount).toBe(1);
    expect(stats.cancelledCount).toBe(1);
    expect(stats.activeCount).toBe(3); // present (2) + absent (1)
    // Excused and cancelled sessions never count against attendance, so the
    // denominator is 6 - 1 - 1 = 4 counted sessions → 2 / 4 = 50%.
    expect(stats.countedSessions).toBe(4);
    expect(stats.attendancePercent).toBeCloseTo(50, 1); // 2 / 4 = 50%
    expect(stats.absencesRemaining).toBe(2); // 3 - 1
  });

  it('measures attendance against every counted session, not just the marked ones', () => {
    // Week 4 of the semester: 12 sessions on the timetable, 4 attended so far.
    const sessions: AttendanceSession[] = Array.from({ length: 12 }, (_, i) => ({
      id: `s${i + 1}`,
      date: `2025-09-${String(i + 1).padStart(2, '0')}`,
      title: `Week ${i + 1}`,
      sessionType: 'lecture' as const,
      status: i < 4 ? ('present' as const) : ('unmarked' as const),
    }));

    const stats = calculateAttendanceStats(
      sessions,
      { maxAbsencesAllowed: 3, minAttendancePercent: null },
      { today: '2025-12-01' }
    );

    expect(stats.countedSessions).toBe(12);
    expect(stats.attendancePercent).toBeCloseTo(33.3, 1); // 4 / 12 = 33%
  });

  it('leaves unmarked future sessions out of the denominator', () => {
    const sessions: AttendanceSession[] = Array.from({ length: 24 }, (_, i) => ({
      id: `s${i + 1}`,
      date: `2025-09-${String(i + 1).padStart(2, '0')}`,
      title: `Week ${i + 1}`,
      sessionType: 'lecture' as const,
      status: 'unmarked' as const,
    }));

    // Nothing has happened yet: nothing to measure against.
    const before = calculateAttendanceStats(sessions, { maxAbsencesAllowed: null, minAttendancePercent: null }, { today: '2025-08-31' });
    expect(before.countedSessions).toBe(0);
    expect(before.unmarkedCount).toBe(24);

    // Day 5 with one class attended: past + today's sessions count, later ones do not.
    sessions[0] = { ...sessions[0], status: 'present' };
    sessions[19] = { ...sessions[19], status: 'absent' }; // a marked future session still counts
    const mid = calculateAttendanceStats(sessions, { maxAbsencesAllowed: null, minAttendancePercent: null }, { today: '2025-09-05' });
    expect(mid.countedSessions).toBe(6);
    expect(mid.attendancePercent).toBeCloseTo(16.7, 1);
  });

  it('reports no percentage when there is nothing to count', () => {
    const stats = calculateAttendanceStats(
      [
        { id: '1', date: '2025-09-01', title: 'W1', sessionType: 'lecture', status: 'cancelled' },
        { id: '2', date: '2025-09-08', title: 'W2', sessionType: 'lecture', status: 'excused' },
      ],
      { maxAbsencesAllowed: null, minAttendancePercent: null }
    );

    expect(stats.countedSessions).toBe(0);
    expect(stats.attendancePercent).toBe(0);
    expect(stats.isBelowMinPercent).toBe(false);
  });

  it('evaluates attendance risk tiers correctly', () => {
    const tier = (input: Partial<Parameters<typeof getAttendanceRiskTier>[0]> = {}) =>
      getAttendanceRiskTier({
        absentCount: 0,
        maxAbsencesAllowed: 3,
        attendancePercent: 100,
        minAttendancePercent: 70,
        activeCount: 10,
        ...input,
      });

    expect(tier()).toBe('safe');
    expect(tier({ absentCount: 1 })).toBe('safe');
    expect(tier({ absentCount: 2 })).toBe('warning'); // one absence away
    expect(tier({ absentCount: 3 })).toBe('warning'); // at limit, not over
    expect(tier({ absentCount: 4 })).toBe('critical'); // over quota
    expect(tier({ activeCount: 0, absentCount: 0 })).toBe('unknown');
  });

  it('flags below-minimum attendance and zero-quota breaches', () => {
    expect(
      getAttendanceRiskTier({
        absentCount: 1,
        maxAbsencesAllowed: 5,
        attendancePercent: 57,
        minAttendancePercent: 75,
        activeCount: 5,
      })
    ).toBe('critical');

    expect(
      getAttendanceRiskTier({
        absentCount: 1,
        maxAbsencesAllowed: 0,
        attendancePercent: 0,
        minAttendancePercent: null,
        activeCount: 5,
      })
    ).toBe('critical');
  });

  it('never regenerates sessions whose slot key was tombstoned', () => {
    const events: ScheduleEvent[] = [
      {
        id: 'e1',
        course_name: 'Physics I',
        course_code: 'PHY101',
        day_of_week: 1,
        start_time: '08:15',
        end_time: '09:45',
        semester_start: '2025-09-01',
        semester_end: '2025-12-08',
        source: 'neptun',
        event_type: 'lecture',
      },
    ];

    const all = generateSessionsFromScheduleEvents({
      courseName: 'Physics I',
      courseCode: 'PHY101',
      scheduleEvents: events,
    });
    const removed = all[0];
    const removedKey = `${removed.date}_${removed.startTime}_${removed.sessionType}`;

    const regenerated = generateSessionsFromScheduleEvents({
      courseName: 'Physics I',
      courseCode: 'PHY101',
      scheduleEvents: events,
      existingSessions: all.filter((s) => s.id !== removed.id),
      removedSessionKeys: [removedKey],
    });

    expect(regenerated.length).toBe(all.length - 1);
    expect(regenerated.some((s) => s.date === removed.date && s.startTime === removed.startTime)).toBe(false);
  });

  it('keeps a tombstoned marked absence deleted instead of restoring it unmarked', () => {
    const events: ScheduleEvent[] = [
      {
        id: 'e1',
        course_name: 'Physics I',
        course_code: 'PHY101',
        day_of_week: 1,
        start_time: '08:15',
        end_time: '09:45',
        semester_start: '2025-09-01',
        semester_end: '2025-12-08',
        source: 'neptun',
        event_type: 'lecture',
      },
    ];

    const all = generateSessionsFromScheduleEvents({
      courseName: 'Physics I',
      courseCode: 'PHY101',
      scheduleEvents: events,
    });
    const marked = { ...all[0], status: 'absent' as const };
    const removedKey = `${marked.date}_${marked.startTime}_${marked.sessionType}`;

    const regenerated = generateSessionsFromScheduleEvents({
      courseName: 'Physics I',
      courseCode: 'PHY101',
      scheduleEvents: events,
      existingSessions: [],
      removedSessionKeys: [removedKey],
    });

    expect(regenerated.some((s) => s.date === marked.date && s.status !== 'unmarked')).toBe(false);
    expect(regenerated.length).toBe(all.length - 1);
  });

  it('deduplicates sessions cleanly and preserves marked status', () => {
    const bloated: AttendanceSession[] = [
      { id: '1', date: '2025-09-01', title: 'Lecture', sessionType: 'lecture', status: 'unmarked' },
      { id: '2', date: '2025-09-01', title: 'Lecture', sessionType: 'lecture', status: 'present' },
      { id: '3', date: '2025-09-01', title: 'Lecture', sessionType: 'lecture', status: 'unmarked' },
      { id: '4', date: '2025-09-08', title: 'Lecture 2', sessionType: 'lecture', status: 'absent' },
      { id: '5', date: '2025-09-08', title: 'Lecture 2', sessionType: 'lecture', status: 'unmarked' },
    ];

    const deduplicated = deduplicateSessions(bloated);
    expect(deduplicated.length).toBe(2);
    expect(deduplicated[0].date).toBe('2025-09-01');
    expect(deduplicated[0].status).toBe('present');
    expect(deduplicated[1].date).toBe('2025-09-08');
    expect(deduplicated[1].status).toBe('absent');
  });

  it('deduplicates multi-instance calendar events (like Neptun recurring imports)', () => {
    // 14 individual event instances for the same Monday lecture
    const neptunEvents: ScheduleEvent[] = Array.from({ length: 14 }, (_, i) => ({
      id: `nep-${i}`,
      course_name: 'Physics I',
      course_code: 'PHY101',
      day_of_week: 1, // Monday
      start_time: '08:15',
      end_time: '09:45',
      semester_start: '2025-09-01',
      semester_end: '2025-12-08',
      source: 'neptun',
      event_type: 'lecture',
    }));

    const result = generateSessionsFromScheduleEvents({
      courseName: 'Physics I',
      courseCode: 'PHY101',
      scheduleEvents: neptunEvents,
      existingSessions: [],
    });

    // Must generate exactly 14 weekly sessions, NOT 14 * 14 = 196!
    expect(result.length).toBe(14);
    const uniqueDates = new Set(result.map((s) => s.date));
    expect(uniqueDates.size).toBe(14);
    expect(result[0].title).toBe('Week 1');
    expect(result[13].title).toBe('Week 14');
  });

  it('clamps and isolates single semester when schedule contains 5-year historical events', () => {
    // 5 years worth of events across semesters 2021 to 2026
    const multiYearEvents: ScheduleEvent[] = [
      {
        id: 'old-1',
        course_name: 'Economics',
        course_code: 'ECO101',
        day_of_week: 1,
        start_time: '10:00',
        end_time: '11:30',
        semester: '2021/22/1',
        semester_start: '2021-09-01',
        semester_end: '2021-12-15',
        source: 'neptun',
      },
      {
        id: 'current-1',
        course_name: 'Economics',
        course_code: 'ECO101',
        day_of_week: 1,
        start_time: '10:00',
        end_time: '11:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        source: 'neptun',
      },
    ];

    const result = generateSessionsFromScheduleEvents({
      courseName: 'Economics',
      courseCode: 'ECO101',
      semester: '2025/26/1',
      scheduleEvents: multiYearEvents,
      existingSessions: [],
    });

    // Should generate exactly 14 weekly sessions for the 2025/26/1 semester, NOT 260 weeks across 5 years!
    expect(result.length).toBe(14);
    expect(result[0].date).toBe('2025-09-01');
    expect(result[0].title).toBe('Week 1');
    expect(result[13].title).toBe('Week 14');
  });

  it('syncs lecture, seminar, and lab sessions of the same class together', () => {
    const mixedEvents: ScheduleEvent[] = [
      {
        id: 'lec-1',
        course_name: 'Computer Science',
        course_code: 'CS101-E',
        day_of_week: 1, // Monday
        start_time: '08:15',
        end_time: '09:45',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lecture',
      },
      {
        id: 'sem-1',
        course_name: 'Computer Science',
        course_code: 'CS101-G',
        day_of_week: 3, // Wednesday
        start_time: '10:00',
        end_time: '11:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'seminar',
      },
      {
        id: 'lab-1',
        course_name: 'Computer Science',
        course_code: 'CS101-L',
        day_of_week: 5, // Friday
        start_time: '12:00',
        end_time: '13:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lab',
      },
    ];

    const result = generateSessionsFromScheduleEvents({
      courseName: 'Computer Science',
      semester: '2025/26/1',
      scheduleEvents: mixedEvents,
      existingSessions: [],
    });

    // 14 weeks * 3 meeting types = 42 total sessions
    expect(result.length).toBe(42);

    const lectures = result.filter((s) => s.sessionType === 'lecture');
    const seminars = result.filter((s) => s.sessionType === 'seminar');
    const labs = result.filter((s) => s.sessionType === 'lab');

    expect(lectures.length).toBe(14);
    expect(seminars.length).toBe(14);
    expect(labs.length).toBe(14);

    expect(lectures[0].title).toBe('Week 1');
    expect(seminars[0].title).toBe('Week 1');
    expect(labs[0].title).toBe('Week 1');
  });

  it('never grabs other classes occurring on the same day', () => {
    const mondayEvents: ScheduleEvent[] = [
      {
        id: 'math-1',
        course_name: 'Linear Algebra',
        course_code: 'MATH101',
        day_of_week: 1, // Monday 08:15
        start_time: '08:15',
        end_time: '09:45',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lecture',
      },
      {
        id: 'chem-1',
        course_name: 'General Chemistry',
        course_code: 'CHEM101',
        day_of_week: 1, // Monday 12:00 (SAME DAY, DIFFERENT SUBJECT)
        start_time: '12:00',
        end_time: '13:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lecture',
      },
    ];

    const result = generateSessionsFromScheduleEvents({
      courseName: 'Linear Algebra',
      courseCode: 'MATH101',
      semester: '2025/26/1',
      scheduleEvents: mondayEvents,
      existingSessions: [],
    });

    // Must ONLY generate 14 sessions for Linear Algebra, completely ignoring Chemistry
    expect(result.length).toBe(14);
    expect(result.every((s) => s.startTime === '08:15')).toBe(true);
    expect(result.some((s) => s.startTime === '12:00')).toBe(false);
  });

  it('correctly handles 1-week intensive block courses with multiple sessions without multiplying to 14 weeks', () => {
    // 18 slots across 6 days (Mon-Sat, 3 slots per day) for a 1-week intensive block course
    const blockEvents: ScheduleEvent[] = [];
    const days = [1, 2, 3, 4, 5, 6];
    const times = [
      { start: '08:00', end: '09:30' },
      { start: '09:50', end: '11:20' },
      { start: '11:40', end: '13:10' },
    ];
    for (const d of days) {
      for (const t of times) {
        blockEvents.push({
          id: `block-${d}-${t.start}`,
          course_name: 'Organisational interpersonal skills dev',
          course_code: 'G10',
          day_of_week: d,
          start_time: t.start,
          end_time: t.end,
          semester_start: '2026-09-07',
          semester_end: '2026-09-12',
          source: 'neptun',
          event_type: 'seminar',
        });
      }
    }

    const result = generateSessionsFromScheduleEvents({
      courseName: 'Organisational interpersonal skills dev',
      courseCode: 'G10',
      scheduleEvents: blockEvents,
      existingSessions: [],
    });

    // Must generate exactly 18 sessions (1 week * 18 sessions), NOT 18 * 14 = 252 sessions!
    expect(result.length).toBe(18);
  });

  it('correctly handles irregular seminar courses (like G04) meeting only on specific dates without multiplying to 14 weeks', () => {
    // 4 sessions across the semester
    const dates = ['2026-09-15', '2026-10-13', '2026-11-03', '2026-12-01'];
    const g04Events: ScheduleEvent[] = dates.map((d) => ({
      id: `g04-${d}`,
      course_name: 'Organisational interpersonal skills dev',
      course_code: 'G04',
      day_of_week: 2, // Tuesday
      start_time: '08:00',
      end_time: '09:30',
      semester_start: d,
      semester_end: d,
      source: 'neptun',
      event_type: 'seminar',
    }));

    const result = generateSessionsFromScheduleEvents({
      courseName: 'Organisational interpersonal skills dev',
      courseCode: 'G04',
      scheduleEvents: g04Events,
      existingSessions: [],
    });

    // Must generate exactly 4 sessions corresponding to the 4 actual meeting dates
    expect(result.length).toBe(4);
    expect(result.map((s) => s.date).sort()).toEqual(dates.sort());
  });

  it('never generates phantom sessions when no schedule events match and no dayOfWeek is provided', () => {
    // Simulate opening Course Hub for a course that has no matching schedule
    // events — previously this fell back to 14 phantom Monday 08:15 lectures
    // (the "ghost sessions" bug).
    const unrelatedEvents: ScheduleEvent[] = [
      {
        id: 'other-1',
        course_name: 'Completely Different Course',
        course_code: 'OTHER101',
        day_of_week: 3,
        start_time: '10:00',
        end_time: '11:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lecture',
      },
    ];

    const result = generateSessionsFromScheduleEvents({
      courseName: 'My Unmatched Course',
      courseCode: 'NOMATCH999',
      semester: '2025/26/1',
      scheduleEvents: unrelatedEvents,
      existingSessions: [],
      // Note: no dayOfWeek — Course Hub opened without schedule metadata
    });

    // Must produce ZERO sessions, NOT 14 phantom Monday 08:15 lectures
    expect(result.length).toBe(0);
  });

  it('preserves user-marked sessions when no schedule slots match', () => {
    const existing: AttendanceSession[] = [
      {
        id: 'custom-1',
        date: '2025-09-10',
        title: 'Custom Session',
        sessionType: 'lecture',
        status: 'present',
        isCustom: true,
      },
      {
        id: 'unmarked-1',
        date: '2025-09-17',
        title: 'Week 3',
        sessionType: 'lecture',
        status: 'unmarked',
      },
    ];

    const result = generateSessionsFromScheduleEvents({
      courseName: 'No Match Course',
      scheduleEvents: [],
      existingSessions: existing,
    });

    // Should preserve the custom/marked session, drop the unmarked one
    expect(result.length).toBe(1);
    expect(result[0].date).toBe('2025-09-10');
    expect(result[0].status).toBe('present');
  });

  it('diagnoses key resolution and session generation for Financial Markets and Legal Studies', () => {
    const key1 = resolveCourseKey({ courseName: 'Basics of Financial Markets', courseCode: 'E01', semester: '2026/27/1' });
    const key2 = resolveCourseKey({ courseName: 'Introduction to Legal Studies and Taxation', courseCode: 'E01', semester: '2026/27/1' });
    const key3 = resolveCourseKey({ courseName: 'Introduction to Legal Studies and Taxation', courseCode: 'G01', semester: '2026/27/1' });

    console.log('KEY 1 (Financial Markets E01):', key1);
    console.log('KEY 2 (Legal Studies E01):', key2);
    console.log('KEY 3 (Legal Studies G01):', key3);

    const same = isSameCourse(
      { course_name: 'Basics of Financial Markets', course_code: 'E01' },
      { course_name: 'Introduction to Legal Studies and Taxation', course_code: 'E01' }
    );
    console.log('isSameCourse(c1, c2):', same);

    const events: ScheduleEvent[] = [
      {
        id: 'fin-lec',
        course_name: 'Basics of Financial Markets',
        course_code: 'E01',
        day_of_week: 1,
        start_time: '08:00',
        end_time: '09:30',
        semester: '2026/27/1',
        semester_start: '2026-09-14',
        semester_end: '2026-12-15',
        event_type: 'class',
      },
      {
        id: 'law-lec',
        course_name: 'Introduction to Legal Studies and Taxation',
        course_code: 'E01',
        day_of_week: 1,
        start_time: '17:20',
        end_time: '18:50',
        semester: '2026/27/1',
        semester_start: '2026-09-14',
        semester_end: '2026-12-15',
        event_type: 'class',
      },
      {
        id: 'law-sem',
        course_name: 'Introduction to Legal Studies and Taxation',
        course_code: 'G01',
        day_of_week: 2,
        start_time: '19:10',
        end_time: '20:40',
        semester: '2026/27/1',
        semester_start: '2026-09-15',
        semester_end: '2026-12-15',
        event_type: 'class',
      },
    ];

    const gen = generateSessionsFromScheduleEvents({
      courseName: 'Introduction to Legal Studies and Taxation',
      courseCode: 'E01',
      semester: '2026/27/1',
      dayOfWeek: 1,
      startTime: '17:20',
      endTime: '18:50',
      scheduleEvents: events,
      existingSessions: [],
    });

    console.log('GENERATED SESSIONS FOR LEGAL STUDIES (count ' + gen.length + '):',
      gen.slice(0, 4).map(s => `${s.date} ${s.startTime} ${s.sessionType}`)
    );
  });
});

