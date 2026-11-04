import { describe, it, expect, vi, beforeEach } from 'vitest';

// Unit tests must not touch the network: PocketBase has no server in tests, so
// the `course_tracker_data` singleton collection is stubbed. Reads/creates/
// updates are recorded so the no-upsert find-then-write path can be asserted.
const cloud = vi.hoisted(() => ({
  getFirstListItem: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
}));

vi.mock('@/lib/pocketbase', () => ({
  pb: {
    collection: vi.fn(() => ({
      getFirstListItem: cloud.getFirstListItem,
      create: cloud.create,
      update: cloud.update,
    })),
    filter: vi.fn((filter: string) => filter),
  },
}));

// Encryption also issues a fetch (to the crypto backend route); the sync
// tests only care about the tracker data, so pass values through.
vi.mock('@/lib/encryption', () => ({
  encryptField: async (value: string | null | undefined) => value,
  decryptField: async (value: string | null | undefined) => value,
  isCiphertext: (value: unknown) => typeof value === 'string' && value.startsWith('vc2:'),
}));

beforeEach(() => {
  resetCourseTrackerSyncForTests();
  cloud.getFirstListItem.mockReset();
  cloud.create.mockReset();
  cloud.update.mockReset();
  // Default: the user has no cloud row yet, so a sync creates one.
  cloud.getFirstListItem.mockRejectedValue(Object.assign(new Error('404'), { status: 404 }));
  cloud.create.mockResolvedValue({ id: 'row-1' });
  cloud.update.mockResolvedValue({ id: 'row-1' });
});

import { syncCourseTrackersFromScheduleEvents } from './schedule-reconciler';
import { resetCourseTrackerSyncForTests } from './course-tracker-sync';
import { serializeTrackerData } from './tracker-types';
import {
  saveCourseTrackerToOfflineCache,
  getCourseTrackerFromOfflineCache,
  applySetAttendanceStatus,
  applyUpsertTracker,
} from './course-tracker-store';
import type { StorageLike } from '@/lib/storage';
import { generateSessionsFromScheduleEvents, resolveCourseKey } from './attendance-calc';
import { createDefaultTracker, createEmptyTrackerData, sessionSlotKey } from './tracker-types';

// Realistic intensive-block course: the ICS feed emits a group-suffixed code.
const INTENSIVE_IDENTITY = {
  courseCode: 'X5VX18SUBM01M-G10',
  courseName: 'Business Communication Intensive',
  semester: '2026/27/1',
};

function intensiveScheduleEvents() {
  const base = {
    course_name: 'Business Communication Intensive',
    course_code: 'X5VX18SUBM01M-G10',
    day_of_week: 1,
    semester: '2026/27/1',
    semester_start: '2026-09-07',
    semester_end: '2026-09-18',
    event_type: 'seminar',
  };
  // Three seminar slots per intensive day.
  return [
    { ...base, start_time: '08:00', end_time: '09:30' },
    { ...base, start_time: '09:50', end_time: '11:20' },
    { ...base, start_time: '11:40', end_time: '13:10' },
  ];
}

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

describe('syncCourseTrackersFromScheduleEvents', () => {
  it('does not create or mutate attendance trackers for exam-only schedules', async () => {
    const storage = stubStorage();
    const userId = 'user-exams-only';
    const result = await syncCourseTrackersFromScheduleEvents([{
      course_name: 'Accounting',
      course_code: 'ACC101',
      day_of_week: 4,
      start_time: '10:00',
      end_time: '12:00',
      semester: '2026/27/1',
      semester_start: '2026-10-01',
      semester_end: '2026-10-01',
      event_type: 'exam',
    }], userId, storage);
    expect(result.trackers).toEqual({});
  });

  it('skips an English-titled exam stored as a class and drops the tracker an earlier sync seeded for it', async () => {
    const storage = stubStorage();
    const userId = 'user-legacy-exam';
    const exam = {
      course_name: 'Elements of Accounting Midterm Exam',
      course_code: '',
      day_of_week: 1,
      start_time: '09:50',
      end_time: '11:20',
      semester: '2026/27/1',
      semester_start: '2026-11-02',
      semester_end: '2026-11-02',
      // What a sync before exam titles were recognised stored for this exam.
      event_type: 'class',
    };

    // Seed the stale tracker a previous sync would have created for it.
    const staleTracker = createDefaultTracker({
      courseName: 'Elements of Accounting Midterm Exam',
      semester: '2026/27/1',
    });
    staleTracker.attendance.sessions = [
      { id: 's1', date: '2026-11-02', startTime: '09:50', endTime: '11:20', title: 'Lecture', status: 'unmarked', sessionType: 'lecture' },
      { id: 's2', date: '2026-11-02', startTime: '11:40', endTime: '13:10', title: 'Lecture', status: 'unmarked', sessionType: 'lecture' },
    ];
    saveCourseTrackerToOfflineCache(
      { version: 1, trackers: { [staleTracker.courseKey]: staleTracker } },
      userId,
      storage
    );

    // The stale tracker has to survive the round trip through the offline
    // cache, otherwise this test would pass without ever exercising the prune.
    expect(Object.keys(getCourseTrackerFromOfflineCache(userId, storage)?.trackers ?? {})).toHaveLength(1);

    const result = await syncCourseTrackersFromScheduleEvents([exam], userId, storage);

    expect(result.trackers).toEqual({});
    expect(getCourseTrackerFromOfflineCache(userId, storage)?.trackers).toEqual({});
  });

  it('keeps an exam-named tracker the student has actually worked on', async () => {
    const storage = stubStorage();
    const userId = 'user-exam-skills';
    const tracker = createDefaultTracker({ courseName: 'Exam Skills', semester: '2026/27/1' });
    tracker.attendance.sessions = [{
      id: 's1',
      date: '2026-10-05',
      startTime: '10:00',
      endTime: '12:00',
      title: 'Week 1',
      status: 'present',
      sessionType: 'seminar',
    }];
    saveCourseTrackerToOfflineCache(
      { version: 1, trackers: { [tracker.courseKey]: tracker } },
      userId,
      storage
    );

    // "Exam Skills" matches the exam-title cleanup, but a marked session means
    // this is the student's own work and must not be swept away with the
    // trackers an old sync fabricated for real exams.
    const result = await syncCourseTrackersFromScheduleEvents([], userId, storage);

    expect(Object.keys(result.trackers)).toEqual([tracker.courseKey]);
    expect(getCourseTrackerFromOfflineCache(userId, storage)?.trackers[tracker.courseKey]).toBeTruthy();
  });

  it('does not generate attendance sessions from an exam when manual class fallback details are provided', () => {
    expect(generateSessionsFromScheduleEvents({
      courseName: 'Accounting',
      courseCode: 'ACC101',
      dayOfWeek: 4,
      startTime: '10:00',
      endTime: '12:00',
      scheduleEvents: [{
        course_name: 'Accounting',
        course_code: 'ACC101',
        day_of_week: 4,
        start_time: '10:00',
        end_time: '12:00',
        event_type: 'exam',
      } as any],
    })).toEqual([]);
  });

  it('generates no sessions for an exam stored as a class', () => {
    // The generator is the last line of defence. This row is the real one: an
    // English-titled midterm the importer stored as event_type 'class', which
    // used to turn one exam into fourteen weekly "lecture" slots.
    const exam = {
      course_name: 'Elements of Accounting Midterm Exam (Félévközi részvizsga)',
      course_code: '',
      day_of_week: 1,
      start_time: '09:50',
      end_time: '11:20',
      semester_start: '2026-11-02',
      semester_end: '2026-11-02',
      event_type: 'class',
    };

    expect(generateSessionsFromScheduleEvents({
      courseName: exam.course_name,
      courseCode: exam.course_code,
      semester: '2026/27/1',
      scheduleEvents: [exam as any],
    })).toEqual([]);
  });

  it('automatically populates sessions for all courses in a synced schedule', async () => {
    const storage = stubStorage();
    const userId = 'user-auto';

    const events = [
      {
        course_name: 'Calculus I',
        course_code: 'MATH101-E',
        day_of_week: 1,
        start_time: '08:15',
        end_time: '09:45',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lecture',
      },
      {
        course_name: 'Calculus I',
        course_code: 'MATH101-G',
        day_of_week: 3,
        start_time: '10:00',
        end_time: '11:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'seminar',
      },
      {
        course_name: 'Algorithms',
        course_code: 'CS201',
        day_of_week: 2,
        start_time: '14:00',
        end_time: '15:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lecture',
      },
    ];

    const result = await syncCourseTrackersFromScheduleEvents(events, userId, storage);
    
    // Check Calculus I (synced Lecture + Seminar = 28 sessions into unified tracker)
    const mathTracker = result.trackers['math101_2025-26-1'];
    expect(mathTracker).toBeDefined();
    expect(mathTracker.attendance.sessions.length).toBe(28);

    // Check Algorithms (14 sessions)
    const csTracker = result.trackers['cs201_2025-26-1'];
    expect(csTracker).toBeDefined();
    expect(csTracker.attendance.sessions.length).toBe(14);
  });

  it('preserves user-marked attendance statuses across repeated schedule syncs', async () => {
    const storage = stubStorage();
    const userId = 'user-preserve';

    const events = [
      {
        course_name: 'Database Systems',
        course_code: 'CS301',
        day_of_week: 1,
        start_time: '10:00',
        end_time: '11:30',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-12-15',
        event_type: 'lecture',
      },
    ];

    // Initial sync
    const firstSync = await syncCourseTrackersFromScheduleEvents(events, userId, storage);
    const key = 'cs301_2025-26-1';
    expect(firstSync.trackers[key]).toBeDefined();

    // Mark week 1 as present and add assessment
    firstSync.trackers[key].attendance.sessions[0].status = 'present';
    firstSync.trackers[key].points.assessments.push({
      id: 'test-quiz',
      name: 'Quiz 1',
      earnedPoints: 18,
      maxPoints: 20,
    });
    saveCourseTrackerToOfflineCache(firstSync, userId, storage);

    // Re-sync with the same schedule feed
    const secondSync = await syncCourseTrackersFromScheduleEvents(events, userId, storage);
    const updatedTracker = secondSync.trackers[key];

    expect(updatedTracker.attendance.sessions[0].status).toBe('present');
    expect(updatedTracker.points.assessments.length).toBe(1);
    expect(updatedTracker.points.assessments[0].name).toBe('Quiz 1');

    // Deselect attendance (toggle to unmarked)
    updatedTracker.attendance.sessions[0].status = 'unmarked';
    saveCourseTrackerToOfflineCache(secondSync, userId, storage);

    const reloaded = getCourseTrackerFromOfflineCache(userId, storage);
    expect(reloaded?.trackers[key]?.attendance.sessions[0].status).toBe('unmarked');
  });
});

describe('deleted session tombstones', () => {
  it('does not regenerate a deleted slot during a schedule sync', async () => {
    const storage = stubStorage();
    const synced = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-intensive', storage);
    const key = resolveCourseKey(INTENSIVE_IDENTITY);
    const first = synced.trackers[key].attendance.sessions[0];
    const slotKey = sessionSlotKey(first);
    const originalCount = synced.trackers[key].attendance.sessions.length;

    saveCourseTrackerToOfflineCache(
      applyUpsertTracker(synced, {
        ...synced.trackers[key],
        attendance: {
          ...synced.trackers[key].attendance,
          sessions: synced.trackers[key].attendance.sessions.filter((s) => s.id !== first.id),
          removedSessionKeys: [slotKey],
        },
      }),
      'user-intensive',
      storage
    );

    const resynced = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-intensive', storage);
    const sessions = resynced.trackers[key].attendance.sessions;
    expect(sessions.length).toBe(originalCount - 1);
    expect(sessions.some((s) => s.id === first.id || sessionSlotKey(s) === slotKey)).toBe(false);
  });
});

describe('course identity resolution (intensive classes)', () => {
  it('check-in updates the tracker created by the schedule sync (no fork)', async () => {
    const storage = stubStorage();
    const synced = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-intensive', storage);
    const canonicalKey = resolveCourseKey(INTENSIVE_IDENTITY);
    expect(synced.trackers[canonicalKey]).toBeDefined();

    const updated = applySetAttendanceStatus(synced, INTENSIVE_IDENTITY, '2026-09-07', 'present', {
      startTime: '08:00',
      sessionType: 'seminar',
    });

    const sessions = updated.trackers[canonicalKey].attendance.sessions.filter((s) => s.date === '2026-09-07');
    expect(sessions.find((s) => s.startTime === '08:00')?.status).toBe('present');
    expect(Object.keys(updated.trackers).length).toBe(1);
  });

  it('normalizes unpadded times (8:00 vs 08:00) so a check-in marks only its own session', async () => {
    const base = {
      course_name: 'Business Communication Intensive',
      course_code: 'X5VX18SUBM01M-G10',
      day_of_week: 1,
      semester: '2026/27/1',
      semester_start: '2026-09-07',
      semester_end: '2026-09-18',
      event_type: 'seminar',
    };
    // Unpadded hours, like some ICS feeds emit.
    const unpaddedEvents = [
      { ...base, start_time: '8:00', end_time: '9:30' },
      { ...base, start_time: '9:50', end_time: '11:20' },
      { ...base, start_time: '11:40', end_time: '13:10' },
    ];
    const storage = stubStorage();
    const synced = await syncCourseTrackersFromScheduleEvents(unpaddedEvents, 'user-unpadded', storage);
    const canonicalKey = resolveCourseKey(INTENSIVE_IDENTITY);

    let data = applySetAttendanceStatus(synced, INTENSIVE_IDENTITY, '2026-09-07', 'present', {
      startTime: '8:00',
      sessionType: 'seminar',
    });

    // Stored canonically (HH:mm), so regeneration matches the exact slot.
    const created = data.trackers[canonicalKey].attendance.sessions.find((s) => s.date === '2026-09-07');
    expect(created?.startTime).toBe('08:00');

    // Regenerate (modal auto-populate) with the same unpadded feed.
    const tracker = data.trackers[canonicalKey];
    const regenerated = generateSessionsFromScheduleEvents({
      courseName: INTENSIVE_IDENTITY.courseName,
      courseCode: INTENSIVE_IDENTITY.courseCode,
      semester: INTENSIVE_IDENTITY.semester,
      scheduleEvents: unpaddedEvents as any,
      existingSessions: tracker.attendance.sessions,
    });
    data = applyUpsertTracker(data, {
      ...tracker,
      attendance: { ...tracker.attendance, sessions: regenerated },
    });

    const sep7 = data.trackers[canonicalKey].attendance.sessions.filter((s) => s.date === '2026-09-07');
    const marked = sep7.filter((s) => s.status === 'present');
    expect(marked).toHaveLength(1);
    expect(marked[0].startTime).toBe('08:00');
    expect(sep7).toHaveLength(3);
  });

  it('auto-populating the modal does not copy a marked session to sibling sessions on the same date', async () => {
    const storage = stubStorage();
    const synced = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-intensive-siblings', storage);
    const canonicalKey = resolveCourseKey(INTENSIVE_IDENTITY);

    // Check in on the 08:00 session only.
    let data = applySetAttendanceStatus(synced, INTENSIVE_IDENTITY, '2026-09-07', 'present', {
      startTime: '08:00',
      sessionType: 'seminar',
    });

    // Modal open: regenerate the full session list, preserving existing marks.
    const tracker = data.trackers[canonicalKey];
    const regenerated = generateSessionsFromScheduleEvents({
      courseName: INTENSIVE_IDENTITY.courseName,
      courseCode: INTENSIVE_IDENTITY.courseCode,
      semester: INTENSIVE_IDENTITY.semester,
      scheduleEvents: intensiveScheduleEvents() as any,
      existingSessions: tracker.attendance.sessions,
    });
    data = applyUpsertTracker(data, {
      ...tracker,
      attendance: { ...tracker.attendance, sessions: regenerated },
    });

    const sep7 = data.trackers[canonicalKey].attendance.sessions.filter((s) => s.date === '2026-09-07');
    expect(sep7).toHaveLength(3); // all three slots still exist
    const marked = sep7.filter((s) => s.status === 'present');
    expect(marked).toHaveLength(1);
    expect(marked[0].startTime).toBe('08:00');
    // Distinct ids — the 08:00 mark must not bleed onto its siblings.
    expect(new Set(sep7.map((s) => s.id)).size).toBe(3);
  });

  it('isolates attendance between multiple sessions of a course on the same day', async () => {
    const storage = stubStorage();
    const userId = 'user-same-day';

    const events = [
      {
        course_name: 'Operating Systems',
        course_code: 'CS401-E',
        day_of_week: 1,
        start_time: '08:15',
        end_time: '09:45',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-09-15',
        event_type: 'lecture',
      },
      {
        course_name: 'Operating Systems',
        course_code: 'CS401-L',
        day_of_week: 1,
        start_time: '10:15',
        end_time: '11:45',
        semester: '2025/26/1',
        semester_start: '2025-09-01',
        semester_end: '2025-09-15',
        event_type: 'lab',
      },
    ];

    const result = await syncCourseTrackersFromScheduleEvents(events, userId, storage);
    const key = 'cs401_2025-26-1';
    const tracker = result.trackers[key];
    expect(tracker).toBeDefined();

    const sep1Sessions = tracker.attendance.sessions.filter((s) => s.date === '2025-09-01');
    expect(sep1Sessions.length).toBe(2);

    const morningSession = sep1Sessions.find((s) => s.startTime === '08:15')!;
    const afternoonSession = sep1Sessions.find((s) => s.startTime === '10:15')!;

    expect(morningSession).toBeDefined();
    expect(afternoonSession).toBeDefined();

    // Mark morning session as present
    morningSession.status = 'present';
    saveCourseTrackerToOfflineCache(result, userId, storage);

    const cached = getCourseTrackerFromOfflineCache(userId, storage);
    const cachedTracker = cached?.trackers[key];
    const cachedMorning = cachedTracker?.attendance.sessions.find((s) => s.date === '2025-09-01' && s.startTime === '08:15');
    const cachedAfternoon = cachedTracker?.attendance.sessions.find((s) => s.date === '2025-09-01' && s.startTime === '10:15');

    expect(cachedMorning?.status).toBe('present');
    expect(cachedAfternoon?.status).toBe('unmarked');
  });
});

describe('cloud sync (PocketBase singleton row)', () => {
  it('creates the singleton row when the user has none yet', async () => {
    const storage = stubStorage();
    await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-cloud-create', storage);

    expect(cloud.create).toHaveBeenCalledTimes(1);
    expect(cloud.update).not.toHaveBeenCalled();
    const payload = cloud.create.mock.calls[0][0] as { user: string; data: string; updated_at: string };
    expect(payload.user).toBe('user-cloud-create');
    expect(payload.data).toBeTruthy();
    expect(payload.updated_at).toBeTruthy();
  });

  it('updates the existing row instead of creating a second one', async () => {
    cloud.getFirstListItem.mockResolvedValue({ id: 'row-existing', data: null });
    const storage = stubStorage();
    await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-cloud-update', storage);

    expect(cloud.update).toHaveBeenCalledTimes(1);
    expect(cloud.update.mock.calls[0][0]).toBe('row-existing');
    expect(cloud.create).not.toHaveBeenCalled();
  });

  it('keeps the offline cache when the cloud read fails', async () => {
    const storage = stubStorage();
    const userId = 'user-offline';
    // A cache that exists but holds no trackers: the reconciler still attempts
    // the cloud read (it only skips it when the cache has trackers), so this
    // exercises the real read-failure path.
    saveCourseTrackerToOfflineCache(createEmptyTrackerData(), userId, storage);
    cloud.getFirstListItem.mockRejectedValue(new Error('network down'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const result = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), userId, storage);

    // The failing read is swallowed on the warning path and the sync proceeds
    // from the offline cache instead of throwing or losing local state.
    expect(cloud.getFirstListItem).toHaveBeenCalled();
    expect(warn).toHaveBeenCalled();
    const key = resolveCourseKey(INTENSIVE_IDENTITY);
    expect(result.trackers[key]).toBeDefined();
    expect(getCourseTrackerFromOfflineCache(userId, storage)?.trackers[key]).toBeDefined();
    warn.mockRestore();
  });
});

describe('cloud-safe schedule sync', () => {
  it('never seeds a signed-in sync from the anonymous cache', async () => {
    const storage = stubStorage();
    const anon = createDefaultTracker({ courseName: 'Someone Else', semester: '2026/27/1' });
    saveCourseTrackerToOfflineCache({ version: 1, trackers: { stranger: anon } }, null, storage);

    const result = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-anon', storage);
    expect(result.trackers.stranger).toBeUndefined();
  });

  it('merges the cloud copy first, keeping another device\'s trackers and their updatedAt', async () => {
    const storage = stubStorage();
    const key = resolveCourseKey(INTENSIVE_IDENTITY);
    // First sync builds the tracker; reuse it as "another device's" cloud copy.
    const seeded = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), null, stubStorage());
    const remoteOnly = createDefaultTracker({ courseName: 'Remote Course', semester: '2026/27/1' });
    remoteOnly.updatedAt = '2020-01-01T00:00:00.000Z';
    const cloudDoc = { version: 1 as const, trackers: { ...seeded.trackers, remote: remoteOnly } };
    cloud.getFirstListItem.mockResolvedValue({ id: 'row-1', data: serializeTrackerData(cloudDoc) });

    const result = await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-merge', storage);

    expect(result.trackers.remote).toBeDefined();
    // Unchanged trackers keep their timestamp, so this sync wins no merge it did not earn.
    expect(result.trackers[key].updatedAt).toBe(seeded.trackers[key].updatedAt);
    expect(result.trackers.remote.updatedAt).toBe('2020-01-01T00:00:00.000Z');
    // Nothing changed against the cloud copy: no write at all.
    expect(cloud.update).not.toHaveBeenCalled();
    expect(cloud.create).not.toHaveBeenCalled();
  });

  it('holds the upload back when the cloud copy could not be read', async () => {
    const storage = stubStorage();
    cloud.getFirstListItem.mockRejectedValue(new Error('network down'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-gated', storage);

    expect(cloud.update).not.toHaveBeenCalled();
    expect(cloud.create).not.toHaveBeenCalled();
    warn.mockRestore();
  });

  it('holds the upload back when the cloud copy is unreadable', async () => {
    const storage = stubStorage();
    cloud.getFirstListItem.mockResolvedValue({ id: 'row-1', data: 'vc2:garbage' });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await syncCourseTrackersFromScheduleEvents(intensiveScheduleEvents(), 'user-unreadable', storage);

    expect(cloud.update).not.toHaveBeenCalled();
    warn.mockRestore();
  });
});
