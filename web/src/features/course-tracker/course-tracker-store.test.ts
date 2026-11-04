import { describe, it, expect, vi } from 'vitest';

// The store layer is pure/offline, but stub PocketBase so nothing in the import
// graph can reach the network (no server exists in tests).
vi.mock('@/lib/pocketbase', () => ({
  pb: {
    collection: vi.fn(() => ({
      getFirstListItem: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
    })),
    filter: vi.fn((filter: string) => filter),
  },
}));

// Encryption also issues a fetch (to the crypto backend route); the sync
// tests only care about the tracker data, so pass values through.
vi.mock('@/lib/encryption', () => ({
  encryptField: async (value: string | null | undefined) => value,
  decryptField: async (value: string | null | undefined) => value,
}));

import {
  getCourseTrackerOfflineCacheKey,
  saveCourseTrackerToOfflineCache,
  getCourseTrackerFromOfflineCache,
  mergeTrackerData,
  applySetAttendanceStatus,
  applyUpsertTracker,
} from './course-tracker-store';
import type { StorageLike } from '@/lib/storage';
import { resolveCourseKey } from './attendance-calc';
import { createEmptyTrackerData, createDefaultTracker, sessionSlotKey, type CourseTracker } from './tracker-types';

// Realistic intensive-block course: the ICS feed emits a group-suffixed code.
const INTENSIVE_IDENTITY = {
  courseCode: 'X5VX18SUBM01M-G10',
  courseName: 'Business Communication Intensive',
  semester: '2026/27/1',
};

function stubStorage(initial: Record<string, string> = {}): NonNullable<StorageLike> {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
    removeItem: (key) => {
      map.delete(key);
    },
  };
}

function trackerWithStatus(
  courseName: string,
  courseCode: string,
  updatedAt: string,
  status: string
): CourseTracker {
  const tracker = createDefaultTracker({ courseName, courseCode });
  tracker.attendance.sessions.push({
    id: 's1',
    date: '2026-09-07',
    title: 'Class Session',
    sessionType: 'lecture',
    status: status as CourseTracker['attendance']['sessions'][number]['status'],
  });
  tracker.updatedAt = updatedAt;
  return tracker;
}

describe('useCourseTracker offline caching', () => {
  it('saves and retrieves tracker data from storage', () => {
    const storage = stubStorage();
    const userId = 'user-123';
    const tracker = createDefaultTracker({ courseName: 'Linear Algebra', semester: '2025-2026-1' });
    const data = createEmptyTrackerData();
    data.trackers[tracker.courseKey] = tracker;

    saveCourseTrackerToOfflineCache(data, userId, storage);
    const cached = getCourseTrackerFromOfflineCache(userId, storage);

    expect(cached).not.toBeNull();
    expect(cached?.trackers[tracker.courseKey]?.courseName).toBe('Linear Algebra');
  });

  it('handles empty or missing storage gracefully', () => {
    const storage = stubStorage();
    const cached = getCourseTrackerFromOfflineCache('non-existent-user', storage);
    expect(cached).toBeNull();
    expect(getCourseTrackerFromOfflineCache('user', null)).toBeNull();
  });

  it('formats cache key correctly with and without userId', () => {
    expect(getCourseTrackerOfflineCacheKey('usr1')).toBe('vectra_course_tracker_cache_usr1');
    expect(getCourseTrackerOfflineCacheKey(null)).toBe('vectra_course_tracker_cache_anonymous');
  });
});

describe('mergeTrackerData recency resolution', () => {
  it('keeps a fresher local optimistic update over a stale cloud row', () => {
    const local = createEmptyTrackerData();
    const localTracker = trackerWithStatus('Calculus I', 'MATH101', '2026-09-07T10:00:00.000Z', 'present');
    local.trackers[localTracker.courseKey] = localTracker;

    // Cloud row still holds the pre-check-in state (the save is in flight).
    const cloud = createEmptyTrackerData();
    const cloudTracker = trackerWithStatus('Calculus I', 'MATH101', '2026-09-07T09:00:00.000Z', 'unmarked');
    cloud.trackers[cloudTracker.courseKey] = cloudTracker;

    const merged = mergeTrackerData(local, cloud);
    const mergedTracker = merged.trackers[localTracker.courseKey];
    expect(mergedTracker.attendance.sessions[0].status).toBe('present');
  });

  it('keeps a fresher cloud row over an older local row', () => {
    const local = createEmptyTrackerData();
    const localTracker = trackerWithStatus('Calculus I', 'MATH101', '2026-09-07T08:00:00.000Z', 'present');
    local.trackers[localTracker.courseKey] = localTracker;

    // Change made on another device after this one's last local edit.
    const cloud = createEmptyTrackerData();
    const cloudTracker = trackerWithStatus('Calculus I', 'MATH101', '2026-09-07T11:00:00.000Z', 'absent');
    cloud.trackers[cloudTracker.courseKey] = cloudTracker;

    const merged = mergeTrackerData(local, cloud);
    const mergedTracker = merged.trackers[localTracker.courseKey];
    expect(mergedTracker.attendance.sessions[0].status).toBe('absent');
  });

  it('keeps trackers that exist on only one side', () => {
    const local = createEmptyTrackerData();
    const localOnly = createDefaultTracker({ courseName: 'Physics', courseCode: 'PHY201' });
    local.trackers[localOnly.courseKey] = localOnly;

    const cloud = createEmptyTrackerData();
    const cloudOnly = createDefaultTracker({ courseName: 'Chemistry', courseCode: 'CHEM101' });
    cloud.trackers[cloudOnly.courseKey] = cloudOnly;

    const merged = mergeTrackerData(local, cloud);
    expect(merged.trackers[localOnly.courseKey]).toBeDefined();
    expect(merged.trackers[cloudOnly.courseKey]).toBeDefined();
  });

  it('keeps local tracker when timestamps are equal', () => {
    const local = createEmptyTrackerData();
    const localTracker = trackerWithStatus('Linear Algebra', 'MATH202', '2026-09-07T10:00:00.000Z', 'excused');
    local.trackers[localTracker.courseKey] = localTracker;

    const cloud = createEmptyTrackerData();
    const cloudTracker = trackerWithStatus('Linear Algebra', 'MATH202', '2026-09-07T10:00:00.000Z', 'unmarked');
    cloud.trackers[cloudTracker.courseKey] = cloudTracker;

    const merged = mergeTrackerData(local, cloud);
    expect(merged.trackers[localTracker.courseKey].attendance.sessions[0].status).toBe('excused');
  });
});

describe('deleted session tombstones', () => {
  it('clears the tombstone when the student marks the same slot again', () => {
    let data = createEmptyTrackerData();
    data = applySetAttendanceStatus(data, INTENSIVE_IDENTITY, '2026-09-07', 'present', {
      startTime: '08:00',
      sessionType: 'seminar',
    });
    const key = resolveCourseKey(INTENSIVE_IDENTITY);
    const session = data.trackers[key].attendance.sessions[0];
    const slotKey = sessionSlotKey(session);

    // Simulate a delete: session removed, slot key tombstoned.
    const deleted = applyUpsertTracker(data, {
      ...data.trackers[key],
      attendance: {
        ...data.trackers[key].attendance,
        sessions: [],
        removedSessionKeys: [slotKey],
      },
    });
    expect(deleted.trackers[key].attendance.removedSessionKeys).toContain(slotKey);

    // Marking the slot again is an explicit intent → tombstone is cleared.
    const remarked = applySetAttendanceStatus(deleted, INTENSIVE_IDENTITY, '2026-09-07', 'present', {
      startTime: '08:00',
      sessionType: 'seminar',
    });
    expect(remarked.trackers[key].attendance.removedSessionKeys).not.toContain(slotKey);
    expect(
      remarked.trackers[key].attendance.sessions.some(
        (s) => s.date === '2026-09-07' && s.status === 'present'
      )
    ).toBe(true);
  });
});

describe('course identity resolution (intensive classes)', () => {
  it('derives one canonical key from a group-suffixed intensive code + semester', () => {
    expect(resolveCourseKey(INTENSIVE_IDENTITY)).toBe('x5vx18subm01m_2026-27-1');
    // Same course, different spelling of semester → same key
    expect(resolveCourseKey({ ...INTENSIVE_IDENTITY, semester: '2026-27-1' })).toBe('x5vx18subm01m_2026-27-1');
  });

  it('card check-in and modal resolve the same tracker when none exists yet', () => {
    let data = createEmptyTrackerData();

    // Card check-in (first ever) creates the tracker under the canonical key.
    data = applySetAttendanceStatus(data, INTENSIVE_IDENTITY, '2026-09-07', 'present', {
      startTime: '08:00',
      endTime: '09:30',
      sessionType: 'seminar',
    });

    const canonicalKey = resolveCourseKey(INTENSIVE_IDENTITY);
    const created = data.trackers[canonicalKey];
    expect(created).toBeDefined();
    expect(created.courseKey).toBe(canonicalKey);
    expect(created.courseName).toBe('Business Communication Intensive');
    expect(created.attendance.sessions).toHaveLength(1);
    expect(created.attendance.sessions[0].status).toBe('present');

    // The modal's getOrCreateTracker looks up the same key → same tracker.
    expect(Object.keys(data.trackers).length).toBe(1); // no parallel tracker
    expect(data.trackers[canonicalKey].attendance.sessions[0].status).toBe('present');
  });

  it('adopts and rekeys a tracker left under a mangled key by older versions', () => {
    let data = createEmptyTrackerData();

    // Old buggy versions created the tracker under a mangled key.
    const legacy = createDefaultTracker({
      courseName: 'Business Communication Intensive',
      courseCode: 'X5VX18SUBM01M-G10',
    });
    legacy.courseKey = 'x5vx18subm01m2026271';
    legacy.attendance.sessions.push({
      id: 's-legacy',
      date: '2026-09-07',
      startTime: '08:00',
      title: 'Week 1',
      sessionType: 'seminar',
      status: 'present',
    });
    data.trackers[legacy.courseKey] = legacy;

    const canonicalKey = resolveCourseKey(INTENSIVE_IDENTITY);
    data = applySetAttendanceStatus(data, INTENSIVE_IDENTITY, '2026-09-07', 'absent', {
      startTime: '08:00',
      sessionType: 'seminar',
    });

    // Rekeyed to canonical, legacy key removed, status updated in place.
    expect(data.trackers[canonicalKey]).toBeDefined();
    expect(data.trackers[legacy.courseKey]).toBeUndefined();
    expect(data.trackers[canonicalKey].attendance.sessions.find((s) => s.id === 's-legacy')?.status).toBe('absent');
    expect(data.trackers[canonicalKey].attendance.sessions).toHaveLength(1);
  });

  it('upserting (modal auto-populate) also heals a legacy key', () => {
    const data = createEmptyTrackerData();
    const legacy = createDefaultTracker({
      courseName: 'Business Communication Intensive',
      courseCode: 'X5VX18SUBM01M-G10',
      semester: '2026/27/1',
    });
    legacy.courseKey = 'x5vx18subm01m';
    data.trackers[legacy.courseKey] = legacy;

    const next = applyUpsertTracker(data, { ...legacy, courseKey: legacy.courseKey });
    const canonicalKey = resolveCourseKey(INTENSIVE_IDENTITY);
    expect(next.trackers[canonicalKey]).toBeDefined();
    expect(next.trackers[canonicalKey].courseKey).toBe(canonicalKey);
    expect(next.trackers[legacy.courseKey]).toBeUndefined();
  });
});
