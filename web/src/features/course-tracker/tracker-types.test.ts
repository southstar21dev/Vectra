import { describe, it, expect } from 'vitest';
import {
  parseTrackerData,
  serializeTrackerData,
  createEmptyTrackerData,
  normalizeCourseKey,
  createDefaultTracker,
  DEFAULT_GRADING_SCALE,
  type CourseTrackerData,
} from './tracker-types';

describe('tracker-types and schemas', () => {
  it('creates empty tracker data structure', () => {
    const empty = createEmptyTrackerData();
    expect(empty.version).toBe(1);
    expect(empty.trackers).toEqual({});
  });

  it('normalizes course key consistently', () => {
    expect(normalizeCourseKey('CS101', '2025-2026-1')).toBe('cs101_2025-2026-1');
    expect(normalizeCourseKey(' Algorithms & Data Structures ', ' 2025-2026-1 ')).toBe('algorithms-data-structures_2025-2026-1');
  });

  it('serializes and parses valid tracker data', () => {
    const defaultTracker = createDefaultTracker({
      courseName: 'Algorithms',
      courseCode: 'CS201',
      semester: '2025-2026-1',
    });
    const data: CourseTrackerData = {
      version: 1,
      trackers: {
        [defaultTracker.courseKey]: defaultTracker,
      },
    };

    const serialized = serializeTrackerData(data);
    const parsed = parseTrackerData(serialized);
    expect(parsed).not.toBeNull();
    expect(parsed?.trackers[defaultTracker.courseKey]?.courseName).toBe('Algorithms');
  });

  it('returns null on corrupted data', () => {
    expect(parseTrackerData('invalid json')).toBeNull();
    expect(parseTrackerData('{"version": 2}')).toBeNull();
  });

  it('treats legacy trackers without settingsConfirmed as unconfirmed', () => {
    const legacy = {
      version: 1,
      trackers: {
        cs201: {
          id: 't1',
          courseKey: 'cs201',
          courseName: 'Algorithms',
          courseCode: 'CS201',
          semester: '',
          attendance: {
            maxAbsencesAllowed: 3,
            minAttendancePercent: 70,
            sessions: [],
            removedSessionKeys: [],
          },
          points: {
            useWeightedCategories: false,
            gradingScale: [...DEFAULT_GRADING_SCALE],
            assessments: [],
          },
          updatedAt: '2026-01-01T00:00:00.000Z',
        },
      },
    };

    const parsed = parseTrackerData(JSON.stringify(legacy));
    expect(parsed?.trackers.cs201.settingsConfirmed).toBe(false);
    // New trackers start unconfirmed too, so the syllabus banner shows once.
    expect(createDefaultTracker({ courseName: 'Algorithms' }).settingsConfirmed).toBe(false);
    // Trackers saved before course-total points existed default to 100.
    expect(parsed?.trackers.cs201.points.totalPoints).toBe(100);
    expect(createDefaultTracker({ courseName: 'Algorithms' }).points.totalPoints).toBe(100);
  });

  it('drops stored assessment categories and parses category-less assessments', () => {
    const legacy = {
      version: 1,
      trackers: {
        cs201: {
          id: 't1',
          courseKey: 'cs201',
          courseName: 'Algorithms',
          courseCode: 'CS201',
          semester: '',
          attendance: {
            maxAbsencesAllowed: 3,
            minAttendancePercent: 70,
            sessions: [],
            removedSessionKeys: [],
          },
          points: {
            useWeightedCategories: false,
            gradingScale: [...DEFAULT_GRADING_SCALE],
            assessments: [
              { id: 'a1', name: 'Midterm', category: 'midterm', earnedPoints: 18, maxPoints: 20 },
              { id: 'a2', name: 'Homework', earnedPoints: 10, maxPoints: 10 },
            ],
          },
          updatedAt: '2026-01-01T00:00:00.000Z',
        },
      },
    };

    const parsed = parseTrackerData(JSON.stringify(legacy));
    const assessments = parsed?.trackers.cs201.points.assessments;
    expect(assessments).toHaveLength(2);
    expect(assessments?.[0]).not.toHaveProperty('category');
    expect(assessments?.[1]?.name).toBe('Homework');
  });
});
