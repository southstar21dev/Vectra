import { formatYMD } from '@/lib/date-utils';
import { createOfflineCache } from '@/lib/offline-cache';
import { genId } from '@/lib/id';
import { resolveStorage, type StorageLike } from '@/lib/storage';
import {
  createEmptyTrackerData,
  createDefaultTracker,
  parseTrackerData,
  serializeTrackerData,
  sessionSlotKey,
  type AttendanceSession,
  type AttendanceStatus,
  type CourseIdentity,
  type CourseTracker,
  type CourseTrackerData,
  type SessionType,
} from './tracker-types';
import {
  deduplicateSessions,
  getBaseCourseCode,
  getBaseCourseTitle,
  normalizeTime,
  resolveCourseKey,
} from './attendance-calc';

const trackerCache = createOfflineCache<CourseTrackerData>('vectra_course_tracker_cache', {
  serialize: serializeTrackerData,
  deserialize: (raw) => parseTrackerData(raw) ?? createEmptyTrackerData(),
});

export const getCourseTrackerOfflineCacheKey = (userId?: string | null): string => trackerCache.getKey(userId);

export function saveCourseTrackerToOfflineCache(
  data: CourseTrackerData,
  userId?: string | null,
  storage?: StorageLike | null
): void {
  if (storage !== undefined) {
    try {
      const s = resolveStorage(storage);
      if (!s) return;
      const serialized = serializeTrackerData(data);
      s.setItem(trackerCache.getKey(userId), serialized);
    } catch (err) {
      console.error('Failed to save course tracker data to offline cache:', err);
    }
    return;
  }
  trackerCache.write(data, userId);
}

export function getCourseTrackerFromOfflineCache(
  userId?: string | null,
  storage?: StorageLike | null
): CourseTrackerData | null {
  if (storage !== undefined) {
    try {
      const s = resolveStorage(storage);
      if (!s) return null;
      const raw = s.getItem(trackerCache.getKey(userId));
      return raw ? parseTrackerData(raw) : null;
    } catch {
      return null;
    }
  }
  return trackerCache.read(userId);
}

function trackerUpdatedAtMs(tracker: CourseTracker): number {
  const ts = tracker.updatedAt ? Date.parse(tracker.updatedAt) : NaN;
  return Number.isFinite(ts) ? ts : 0;
}

/**
 * Resolves local-vs-cloud tracker data by keeping the newest version of each
 * tracker (by `updatedAt`). Trackers present on only one side are kept as-is.
 *
 * The cloud row is the cross-device source of truth, but it can lag behind a
 * just-applied local optimistic update while the cloud save is still in flight
 * (check-in → event → refetch happens before the mutation's upsert lands).
 * Without this, a stale cloud snapshot reverts the check-in and clobbers the
 * offline cache, so the Course Hub modal keeps showing the old status.
 */
export function mergeTrackerData(local: CourseTrackerData, cloud: CourseTrackerData): CourseTrackerData {
  const trackers: Record<string, CourseTracker> = { ...cloud.trackers };
  for (const [key, localTracker] of Object.entries(local.trackers)) {
    const cloudTracker = trackers[key];
    if (!cloudTracker) {
      trackers[key] = localTracker;
      continue;
    }
    if (trackerUpdatedAtMs(localTracker) >= trackerUpdatedAtMs(cloudTracker)) {
      trackers[key] = localTracker;
    }
  }
  return { version: 1, trackers };
}

// ---------------------------------------------------------------------------
// Pure tracker-store layer
//
// The tracker map is keyed by `resolveCourseKey(identity)` — one canonical key
// per course+semester, identical for the sync, the check-in button and the
// modal. Older versions stored trackers under mangled keys (e.g. applying
// getBaseCourseCode to the semester-suffixed key produced garbage like
// "x5vx18subm01mg102026271"); the lookup below heals those by adopting the
// legacy tracker and rekeying it to the canonical key on the next write.
// ---------------------------------------------------------------------------

/** Splits a canonical key into its base identifier and semester suffix. */
function splitCourseKey(courseKey: string): { base: string; semester: string } {
  const idx = courseKey.lastIndexOf('_');
  if (idx > 0 && idx < courseKey.length - 1) {
    return { base: courseKey.slice(0, idx), semester: courseKey.slice(idx + 1) };
  }
  return { base: courseKey, semester: '' };
}

function isDateLike(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value);
}

/**
 * Locates a tracker stored under a legacy (mangled) key that refers to the same
 * course+semester as `canonicalKey`, so it can be adopted and rekeyed.
 * Never adopts a tracker from a different semester.
 */
function findLegacyTracker(
  trackers: Record<string, CourseTracker>,
  canonicalKey: string,
  identity: CourseIdentity
): { key: string; tracker: CourseTracker } | null {
  const { semester } = splitCourseKey(canonicalKey);
  const baseCode = getBaseCourseCode(identity.courseCode);
  const baseTitle = getBaseCourseTitle(identity.courseName);
  if (!baseCode && !baseTitle) return null;

  for (const [key, tracker] of Object.entries(trackers)) {
    if (key === canonicalKey) continue;
    // A legacy artifact has no semester; a real key from another semester must
    // not be hijacked.
    const storedSem = splitCourseKey(key).semester;
    if (storedSem && storedSem !== semester) continue;

    // Require base code >= 4 chars to avoid false matches on short group
    // prefixes like "e01" which are shared across different courses.
    const codeMatch =
      baseCode && baseCode.length >= 4 && !!tracker.courseCode && getBaseCourseCode(tracker.courseCode) === baseCode;
    const titleMatch =
      baseTitle &&
      baseTitle.length >= 3 &&
      !!tracker.courseName &&
      getBaseCourseTitle(tracker.courseName) === baseTitle;
    if (codeMatch || titleMatch) return { key, tracker };
  }
  return null;
}

/**
 * Finds a tracker by its canonical key — or adopts a legacy one and reports the
 * key to remove. Creates a fresh tracker (under the canonical key) when neither
 * exists.
 */
export function getOrCreateTrackerFromStore(
  trackers: Record<string, CourseTracker>,
  canonicalKey: string,
  identity: CourseIdentity
): { key: string; tracker: CourseTracker; legacyKey?: string } {
  const existing = trackers[canonicalKey];
  if (existing) return { key: canonicalKey, tracker: existing };

  const legacy = findLegacyTracker(trackers, canonicalKey, identity);
  if (legacy) return { key: canonicalKey, tracker: legacy.tracker, legacyKey: legacy.key };

  const created = createDefaultTracker({
    courseName: identity.courseName || canonicalKey,
    courseCode: identity.courseCode ?? undefined,
    semester: identity.semester ?? undefined,
    instructor: identity.instructor ?? undefined,
    location: identity.location ?? undefined,
    color: identity.color ?? undefined,
  });
  created.courseKey = canonicalKey;
  return { key: canonicalKey, tracker: created };
}

/** Read-only lookup: exact canonical key first, then legacy adoption. */
export function findTrackerInStore(
  trackers: Record<string, CourseTracker>,
  canonicalKey: string,
  identity?: CourseIdentity
): { key: string; tracker: CourseTracker | null; legacyKey?: string } {
  if (!canonicalKey) return { key: canonicalKey, tracker: null };
  const existing = trackers[canonicalKey];
  if (existing) return { key: canonicalKey, tracker: existing };
  if (identity) {
    const legacy = findLegacyTracker(trackers, canonicalKey, identity);
    if (legacy) return { key: canonicalKey, tracker: legacy.tracker, legacyKey: legacy.key };
  }
  return { key: canonicalKey, tracker: null };
}

/** Places a tracker at its canonical key, dropping any legacy key it replaced. */
export function commitTracker(
  trackers: Record<string, CourseTracker>,
  key: string,
  tracker: CourseTracker,
  legacyKey?: string
): Record<string, CourseTracker> {
  const next = { ...trackers };
  if (legacyKey && legacyKey !== key) delete next[legacyKey];
  next[key] = tracker;
  return next;
}

/** Pure: mark attendance for the session identified by `identifier` (a session id or a date). */
export function applySetAttendanceStatus(
  data: CourseTrackerData,
  identity: CourseIdentity,
  identifier: string,
  status: AttendanceStatus,
  options?: {
    startTime?: string;
    endTime?: string;
    title?: string;
    sessionType?: SessionType;
  }
): CourseTrackerData {
  const canonicalKey = resolveCourseKey(identity);
  const { tracker, legacyKey } = getOrCreateTrackerFromStore(data.trackers, canonicalKey, identity);
  const sessions = deduplicateSessions(tracker.attendance?.sessions || []);

  // Times are normalized to zero-padded HH:mm so `8:00` and `08:00` never
  // produce divergent session slots, ids, or dedupe keys.
  const normStart = normalizeTime(options?.startTime);
  const normEnd = normalizeTime(options?.endTime);
  const identifierIsDate = isDateLike(identifier);

  // 1. Exact session-id match (modal rows, existing card sessions).
  let target = sessions.find((s) => s.id === identifier);
  // 2. Date + start-time match (card check-in on a slot that already exists).
  if (!target && identifierIsDate && normStart) {
    target = sessions.find(
      (s) => s.date === identifier && !!s.startTime && normalizeTime(s.startTime) === normStart
    );
  }
  // 3. Single session of a date, when no time was supplied.
  if (!target && identifierIsDate && !normStart) {
    const dateSessions = sessions.filter((s) => s.date === identifier);
    if (dateSessions.length === 1) target = dateSessions[0];
  }

  let nextSessions: AttendanceSession[];
  let changedSlotKey: string | null = null;
  if (target) {
    const updatedTarget: AttendanceSession = {
      ...target,
      startTime: target.startTime || normStart,
      endTime: target.endTime || normEnd,
      status,
    };
    changedSlotKey = sessionSlotKey(updatedTarget);
    nextSessions = sessions.map((s) => (s.id === target!.id ? updatedTarget : s));
  } else {
    const sessionDate = identifierIsDate ? identifier : formatYMD();
    const newSession: AttendanceSession = {
      id: identifierIsDate ? `${sessionDate}_${normStart || '00-00'}_${genId()}` : identifier,
      date: sessionDate,
      startTime: normStart,
      endTime: normEnd,
      title: options?.title || tracker.courseName || 'Class Session',
      sessionType: options?.sessionType || 'lecture',
      status,
    };
    changedSlotKey = sessionSlotKey(newSession);
    nextSessions = deduplicateSessions([...sessions, newSession]);
  }

  // Marking a slot again is an explicit "I want this session" — drop any
  // tombstone left by an earlier delete so the reconciler keeps the marking.
  const removedSessionKeys =
    status === 'unmarked' || !changedSlotKey
      ? tracker.attendance.removedSessionKeys || []
      : (tracker.attendance.removedSessionKeys || []).filter((k) => k !== changedSlotKey);

  return {
    ...data,
    trackers: commitTracker(
      data.trackers,
      canonicalKey,
      {
        ...tracker,
        attendance: { ...tracker.attendance, sessions: nextSessions, removedSessionKeys },
        updatedAt: new Date().toISOString(),
      },
      legacyKey
    ),
  };
}

/** Pure: upsert a full tracker under its canonical key (healing legacy keys). */
export function applyUpsertTracker(data: CourseTrackerData, tracker: CourseTracker): CourseTrackerData {
  const canonicalKey = resolveCourseKey({
    courseCode: tracker.courseCode,
    courseName: tracker.courseName,
    semester: tracker.semester,
  });
  const cleaned: CourseTracker = {
    ...tracker,
    courseKey: canonicalKey,
    attendance: {
      ...tracker.attendance,
      sessions: deduplicateSessions(tracker.attendance?.sessions || []),
    },
    updatedAt: new Date().toISOString(),
  };
  return {
    ...data,
    trackers: commitTracker(
      data.trackers,
      canonicalKey,
      cleaned,
      tracker.courseKey !== canonicalKey ? tracker.courseKey : undefined
    ),
  };
}

/** Get-or-create by an already-canonical key (modal's string-key operations). */
export function getTrackerOrCreateByKey(
  trackers: Record<string, CourseTracker>,
  courseKey: string
): { key: string; tracker: CourseTracker } {
  const key = courseKey || 'unknown';
  const existing = trackers[key];
  if (existing) return { key, tracker: existing };
  const created = createDefaultTracker({ courseName: key });
  created.courseKey = key;
  return { key, tracker: created };
}
