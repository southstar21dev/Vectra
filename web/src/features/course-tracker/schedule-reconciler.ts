import { getSessionUserId } from '@/lib/auth-helpers';
import { type StorageLike } from '@/lib/storage';
import {
  createEmptyTrackerData,
  createDefaultTracker,
  type CourseTracker,
  type CourseTrackerData,
} from './tracker-types';
import { generateSessionsFromScheduleEvents, resolveCourseKey } from './attendance-calc';
import { isExamEvent, isExamSummary } from '@/features/schedule/exam-detection';
import { markCloudReadable } from './course-tracker-sync';
import { readCloudTrackerData, uploadTrackerData } from './course-tracker-upload';
import {
  commitTracker,
  findTrackerInStore,
  getCourseTrackerFromOfflineCache,
  mergeTrackerData,
  saveCourseTrackerToOfflineCache,
} from './course-tracker-store';

/**
 * Automatically populates course attendance sessions for all courses
 * found in a synced schedule feed (e.g. Neptun ICS sync or CSV upload).
 * Preserves existing marked attendance records.
 */
/**
 * True once the student has put real work into a tracker: a marked attendance
 * status, a session they added or deleted by hand, a confirmed syllabus, or
 * anything on the points tab. Exam trackers seeded by an old sync have none of
 * that — but a tracker the student created themselves for a course whose NAME
 * merely contains "exam" ("Exam Skills", say) does, so the exam cleanup below
 * must never delete one.
 */
export function trackerHasUserWork(tracker?: CourseTracker | null): boolean {
  if (!tracker) return false;
  if (tracker.settingsConfirmed) return true;
  if ((tracker.attendance?.removedSessionKeys?.length || 0) > 0) return true;
  if (
    (tracker.attendance?.sessions || []).some(
      (session) => session && (session.isCustom || (session.status && session.status !== 'unmarked'))
    )
  ) {
    return true;
  }
  if ((tracker.points?.assessments?.length || 0) > 0) return true;
  if (tracker.points?.useWeightedCategories) return true;
  return false;
}

export async function syncCourseTrackersFromScheduleEvents(
  events: Array<{
    course_name: string;
    attendance_type?: 'lecture' | 'seminar' | 'lab' | 'unknown' | null;
    course_code?: string;
    instructor?: string;
    location?: string;
    day_of_week: number;
    start_time: string;
    end_time: string;
    color?: string;
    semester?: string;
    semester_start?: string | null;
    semester_end?: string | null;
    event_type?: string;
    csv_metadata?: string | null;
  }>,
  userId?: string | null,
  storage?: StorageLike | null
): Promise<CourseTrackerData> {
  let effectiveUserId = userId;
  if (effectiveUserId === undefined) {
    try {
      effectiveUserId = await getSessionUserId();
    } catch {
      effectiveUserId = null;
    }
  }

  // 1. Load this account's tracker state. Never seed from the anonymous cache:
  // that could belong to whoever used the device signed-out, and a signed-in
  // sync would then upload it into this account.
  const localData = getCourseTrackerFromOfflineCache(effectiveUserId ?? null, storage) ?? createEmptyTrackerData();
  let existingData = localData;

  // Merge the cloud copy first so the reconciled document starts from other
  // devices' newest trackers instead of this device's possibly stale cache.
  if (effectiveUserId) {
    try {
      const cloudData = await readCloudTrackerData(effectiveUserId);
      markCloudReadable(effectiveUserId);
      if (cloudData) existingData = mergeTrackerData(localData, cloudData);
    } catch (fetchErr) {
      console.warn('Could not fetch cloud course tracker data during schedule sync:', fetchErr);
    }
  }

  // Exams are not attendance-tracked. isExamEvent also catches exams imported
  // before the importer recognised English titles (see exam-detection.ts): their
  // rows still say event_type 'class', which is what used to seed a tracker with
  // generated "lecture" sessions for every exam.
  const classEvents = (events || []).filter((event) => !isExamEvent(event));

  // Trackers an earlier sync created for exams are stale data, not user work:
  // filtering exams out below stops new ones, but the old ones linger in the
  // store (and in anything that lists trackers) unless they are dropped.
  let nextTrackers = { ...existingData.trackers };
  for (const [key, tracker] of Object.entries(nextTrackers)) {
    if (isExamSummary(tracker?.courseName || '') && !trackerHasUserWork(tracker)) {
      delete nextTrackers[key];
    }
  }

  const prunedExamTrackers =
    Object.keys(nextTrackers).length !== Object.keys(existingData.trackers).length;

  // Nothing to do for an exams-only feed unless the cleanup above changed
  // something — in that case the call still has to persist it.
  if (classEvents.length === 0 && !prunedExamTrackers) {
    return existingData;
  }

  // 2. Identify distinct courses
  const courseMap = new Map<
    string,
    {
      courseName: string;
      courseCode?: string;
      semester?: string;
      instructor?: string;
      location?: string;
      color?: string;
    }
  >();

  for (const evt of classEvents) {
    if (!evt.course_name) continue;
    const key = resolveCourseKey({
      courseCode: evt.course_code,
      courseName: evt.course_name,
      semester: evt.semester,
    });
    if (!courseMap.has(key)) {
      courseMap.set(key, {
        courseName: evt.course_name,
        courseCode: evt.course_code,
        semester: evt.semester,
        instructor: evt.instructor,
        location: evt.location,
        color: evt.color,
      });
    }
  }

  // 3. For each course, generate sessions and preserve all existing marked attendance & assessments
  courseMap.forEach((courseInfo, key) => {
    const found = findTrackerInStore(nextTrackers, key, courseInfo);
    const existingTracker = found.tracker;
    const existingSessions = existingTracker?.attendance?.sessions || [];

    const generatedSessions = generateSessionsFromScheduleEvents({
      courseName: courseInfo.courseName,
      courseCode: courseInfo.courseCode,
      semester: courseInfo.semester,
      scheduleEvents: classEvents as any,
      existingSessions,
      removedSessionKeys: existingTracker?.attendance?.removedSessionKeys || [],
    });

    if (existingTracker) {
      const merged: CourseTracker = {
        ...existingTracker,
        courseKey: key,
        instructor: existingTracker.instructor || courseInfo.instructor,
        location: existingTracker.location || courseInfo.location,
        color: existingTracker.color || courseInfo.color,
        attendance: {
          ...existingTracker.attendance,
          sessions: generatedSessions,
        },
      };
      // Only a tracker whose content actually changed gets a fresh updatedAt:
      // bumping every tracker on every sync would make this device's copy win
      // the per-tracker merge against edits made on another device.
      const changed =
        !!found.legacyKey ||
        existingTracker.courseKey !== key ||
        merged.instructor !== existingTracker.instructor ||
        merged.location !== existingTracker.location ||
        merged.color !== existingTracker.color ||
        JSON.stringify(generatedSessions) !== JSON.stringify(existingSessions);
      if (changed) {
        nextTrackers = commitTracker(
          nextTrackers,
          key,
          { ...merged, updatedAt: new Date().toISOString() },
          found.legacyKey
        );
      }
    } else {
      const newTracker = createDefaultTracker({
        courseName: courseInfo.courseName,
        courseCode: courseInfo.courseCode,
        semester: courseInfo.semester,
        instructor: courseInfo.instructor,
        location: courseInfo.location,
        color: courseInfo.color,
      });
      newTracker.attendance.sessions = generatedSessions;
      // Keep the tracker's own courseKey consistent with its map key so
      // lookups (card getTracker / modal) resolve it without fuzzy matching.
      newTracker.courseKey = key;
      nextTrackers[key] = newTracker;
    }
  });

  const nextData: CourseTrackerData = {
    version: 1,
    trackers: nextTrackers,
  };

  // Trackers this sync deliberately removed (stale exam trackers, legacy keys
  // rekeyed above) must not come back from the cloud copy during the upload.
  const dropKeys = Object.keys(existingData.trackers).filter((key) => !(key in nextTrackers));

  // 4. Save to offline cache
  saveCourseTrackerToOfflineCache(nextData, effectiveUserId ?? null, storage);

  // 5. Cloud sync through the shared save queue (gated on a successful cloud
  // read, merged with the latest cloud copy at upload time). A sync that
  // changed nothing does not write at all.
  const changedAny =
    dropKeys.length > 0 || Object.keys(nextTrackers).some((key) => nextTrackers[key] !== existingData.trackers[key]);
  if (effectiveUserId && changedAny) {
    try {
      await uploadTrackerData(effectiveUserId, nextData, { dropKeys });
    } catch (err) {
      console.warn('Failed to sync course trackers to cloud during schedule sync:', err);
    }
  }

  // 6. Notify active tabs
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new Event('vectra_course_tracker_updated'));
  }

  return nextData;
}
