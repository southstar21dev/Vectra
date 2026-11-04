import { useCallback, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getSessionUserId } from '@/lib/auth-helpers';
import { isOnline } from '@/lib/online';
import { queryKeys } from '@/lib/query-keys';
import { decryptField, encryptField, isCiphertext } from '@/lib/encryption';
import {
  createEmptyTrackerData,
  parseTrackerData,
  serializeTrackerData,
  sessionSlotKey,
  type AttendanceSession,
  type AttendanceStatus,
  type AssessmentItem,
  type CourseIdentity,
  type CourseTracker,
  type CourseTrackerData,
  type GradingScaleTier,
  type SessionType,
} from './tracker-types';
import { deduplicateSessions, normalizeTime, resolveCourseKey } from './attendance-calc';
import { fetchCourseTrackerData, upsertCourseTrackerData } from './course-tracker-cloud';
import {
  enqueueCloudSave,
  markCloudReadable,
  noteHeldBackWrite,
  takeHeldBackWrite,
} from './course-tracker-sync';
import { uploadTrackerData } from './course-tracker-upload';
import {
  applySetAttendanceStatus,
  applyUpsertTracker,
  commitTracker,
  findTrackerInStore,
  getCourseTrackerFromOfflineCache,
  getCourseTrackerOfflineCacheKey,
  getOrCreateTrackerFromStore,
  getTrackerOrCreateByKey,
  mergeTrackerData,
  saveCourseTrackerToOfflineCache,
} from './course-tracker-store';

export function useCourseTracker() {
  const queryClient = useQueryClient();

  const [fallbackData] = useState<CourseTrackerData>(
    () => getCourseTrackerFromOfflineCache() ?? createEmptyTrackerData()
  );

  const query = useQuery({
    queryKey: queryKeys.courseTrackers,
    queryFn: async (): Promise<CourseTrackerData> => {
      const userId = await getSessionUserId();

      const readCurrent = (): CourseTrackerData =>
        queryClient.getQueryData<CourseTrackerData>(queryKeys.courseTrackers) ??
        getCourseTrackerFromOfflineCache(userId) ??
        getCourseTrackerFromOfflineCache(null) ??
        createEmptyTrackerData();

      const cached = readCurrent();

      if (!userId || !isOnline()) {
        return cached;
      }

      try {
        const encryptedData = await fetchCourseTrackerData(userId);

        if (!encryptedData) {
          // No cloud record yet: seed it from local data if we have any.
          markCloudReadable(userId);
          takeHeldBackWrite(userId);
          if (cached && Object.keys(cached.trackers).length > 0) {
            try {
              const encrypted = (await encryptField(serializeTrackerData(cached))) as string;
              await enqueueCloudSave(userId, () => upsertCourseTrackerData(userId, encrypted));
            } catch (seedErr) {
              console.warn('Initial cloud seed failed:', seedErr);
            }
          }
          return cached;
        }

        const decrypted = await decryptField(encryptedData);
        const parsed = isCiphertext(decrypted) ? null : parseTrackerData(decrypted);
        if (!parsed) {
          // Undecryptable or unparseable (e.g. written by a newer client): keep
          // the cloud write gate closed so the local copy never replaces it.
          console.warn('Course tracker cloud data is unreadable; keeping offline cache and blocking uploads.');
          return cached;
        }

        markCloudReadable(userId);
        // Re-read the live cache at return time so an update that landed while
        // the fetch was in flight is never clobbered by the (possibly stale)
        // cloud snapshot. Keep the newest version of each tracker.
        const reconciled = mergeTrackerData(readCurrent(), parsed);
        saveCourseTrackerToOfflineCache(reconciled, userId);
        if (takeHeldBackWrite(userId)) {
          // Local edits made while uploads were blocked: push the merged copy.
          try {
            const encrypted = (await encryptField(serializeTrackerData(reconciled))) as string;
            await enqueueCloudSave(userId, () => upsertCourseTrackerData(userId, encrypted));
          } catch (pushErr) {
            noteHeldBackWrite(userId);
            console.warn('Failed to upload held-back course tracker edits:', pushErr);
          }
        }
        return reconciled;
      } catch (err) {
        console.warn('Failed to load course tracker from cloud, keeping offline cache:', err);
        return cached;
      }
    },
    staleTime: 1000 * 30, // 30s
    refetchOnWindowFocus: true,
    refetchOnMount: true,
  });

  const saveMutation = useMutation({
    mutationFn: async (next: CourseTrackerData) => {
      const userId = await getSessionUserId();

      // 1. Immediately persist to offline cache (both user and generic)
      saveCourseTrackerToOfflineCache(next, userId);

      if (!userId) return;

      // Held back until the cloud copy has been read on this device; otherwise
      // re-reads and merges the cloud copy inside the save queue, so another
      // device's newer trackers are never overwritten by this whole-document
      // upload.
      await uploadTrackerData(userId, next);
    },
    onSuccess: () => {
      // Cloud row is now fresh — a refetch can no longer revert local state.
      queryClient.invalidateQueries({ queryKey: queryKeys.courseTrackers });
    },
  });

  // The React Query cache is the single source of truth: every write goes
  // through setQueryData, so every useCourseTracker instance (card, modal,
  // dashboard) re-renders from the same data instantly.
  const data = query.data ?? fallbackData;

  const write = useCallback(
    (mutator: (prev: CourseTrackerData) => CourseTrackerData) => {
      const prev =
        queryClient.getQueryData<CourseTrackerData>(queryKeys.courseTrackers) ??
        getCourseTrackerFromOfflineCache() ??
        createEmptyTrackerData();
      const next = mutator(prev);
      queryClient.setQueryData(queryKeys.courseTrackers, next);
      // Synchronous offline write
      saveCourseTrackerToOfflineCache(next);
      // Cross-tab notification
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new Event('vectra_course_tracker_updated'));
      }
      // Asynchronous cloud sync
      void saveMutation.mutateAsync(next).catch((e) => {
        console.warn('Failed to save course tracker data to cloud:', e);
      });
    },
    [saveMutation, queryClient]
  );

  // Cross-tab sync: another tab's write lands in the offline cache; adopt it
  // without triggering a refetch (the cloud save's onSuccess handles that).
  useEffect(() => {
    const handleTrackerRefresh = async () => {
      const userId = await getSessionUserId();
      const latest = getCourseTrackerFromOfflineCache(userId) ?? getCourseTrackerFromOfflineCache(null);
      if (latest) {
        queryClient.setQueryData(queryKeys.courseTrackers, latest);
      }
    };
    // The custom event only fires in the tab that wrote; other tabs hear the
    // offline-cache write through the browser's `storage` event.
    const handleStorage = (event: StorageEvent) => {
      if (event.key !== getCourseTrackerOfflineCacheKey()) return;
      void handleTrackerRefresh();
    };
    window.addEventListener('vectra_course_tracker_updated', handleTrackerRefresh);
    window.addEventListener('vectra_neptun_schedule_updated', handleTrackerRefresh);
    window.addEventListener('vectra_neptun_sync_completed', handleTrackerRefresh);
    window.addEventListener('storage', handleStorage);
    return () => {
      window.removeEventListener('storage', handleStorage);
      window.removeEventListener('vectra_course_tracker_updated', handleTrackerRefresh);
      window.removeEventListener('vectra_neptun_schedule_updated', handleTrackerRefresh);
      window.removeEventListener('vectra_neptun_sync_completed', handleTrackerRefresh);
    };
  }, [queryClient]);

  const getTracker = useCallback(
    (identity: CourseIdentity): CourseTracker | null => {
      if (!identity?.courseCode && !identity?.courseName) return null;
      const found = findTrackerInStore(data.trackers, resolveCourseKey(identity), identity);
      if (!found.tracker) return null;
      return {
        ...found.tracker,
        attendance: {
          ...found.tracker.attendance,
          sessions: deduplicateSessions(found.tracker.attendance?.sessions || []),
        },
      };
    },
    [data]
  );

  const getOrCreateTracker = useCallback(
    (identity: CourseIdentity): CourseTracker => {
      const canonicalKey = resolveCourseKey(identity);
      const { tracker } = getOrCreateTrackerFromStore(data.trackers, canonicalKey, identity);
      return {
        ...tracker,
        courseKey: canonicalKey,
        attendance: {
          ...tracker.attendance,
          sessions: deduplicateSessions(tracker.attendance?.sessions || []),
        },
      };
    },
    [data]
  );

  const upsertTracker = useCallback(
    (tracker: CourseTracker) => {
      write((prev) => applyUpsertTracker(prev, tracker));
    },
    [write]
  );

  const setAttendanceStatus = useCallback(
    (
      identity: CourseIdentity,
      identifier: string,
      status: AttendanceStatus,
      options?: {
        startTime?: string;
        endTime?: string;
        title?: string;
        sessionType?: SessionType;
      }
    ) => {
      write((prev) => applySetAttendanceStatus(prev, identity, identifier, status, options));
    },
    [write]
  );

  const addAttendanceSession = useCallback(
    (courseKey: string, session: AttendanceSession) => {
      write((prev) => {
        const { key, tracker } = getTrackerOrCreateByKey(prev.trackers, courseKey);
        return {
          ...prev,
          trackers: {
            ...prev.trackers,
            [key]: {
              ...tracker,
              attendance: {
                ...tracker.attendance,
                sessions: deduplicateSessions([...(tracker.attendance?.sessions || []), session]),
              },
              updatedAt: new Date().toISOString(),
            },
          },
        };
      });
    },
    [write]
  );

  const removeAttendanceSession = useCallback(
    (courseKey: string, session: AttendanceSession) => {
      write((prev) => {
        const found = prev.trackers[courseKey];
        if (!found) return prev;
        // Remember the slot so the schedule reconciler does not regenerate the
        // deleted session (which would silently erase a logged absence).
        const slotKey = sessionSlotKey(session);
        return {
          ...prev,
          trackers: {
            ...prev.trackers,
            [courseKey]: {
              ...found,
              attendance: {
                ...found.attendance,
                sessions: found.attendance.sessions.filter((s) => s.id !== session.id),
                removedSessionKeys: Array.from(new Set([...(found.attendance.removedSessionKeys || []), slotKey])),
              },
              updatedAt: new Date().toISOString(),
            },
          },
        };
      });
    },
    [write]
  );

  const restoreAttendanceSession = useCallback(
    (courseKey: string, session: AttendanceSession) => {
      write((prev) => {
        const found = prev.trackers[courseKey];
        if (!found) return prev;
        const slotKey = sessionSlotKey(session);
        return {
          ...prev,
          trackers: {
            ...prev.trackers,
            [courseKey]: {
              ...found,
              attendance: {
                ...found.attendance,
                sessions: deduplicateSessions([...found.attendance.sessions, session]),
                removedSessionKeys: (found.attendance.removedSessionKeys || []).filter((k) => k !== slotKey),
              },
              updatedAt: new Date().toISOString(),
            },
          },
        };
      });
    },
    [write]
  );

  // Assessment writes resolve the tracker the way the sheet reads it (canonical
  // key, then legacy adoption), so an edit always lands on the tracker on screen.
  const writeAssessments = useCallback(
    (identity: CourseIdentity, mutate: (assessments: AssessmentItem[]) => AssessmentItem[]) => {
      write((prev) => {
        const canonicalKey = resolveCourseKey(identity);
        const { key, tracker, legacyKey } = getOrCreateTrackerFromStore(prev.trackers, canonicalKey, identity);
        return {
          ...prev,
          trackers: commitTracker(
            prev.trackers,
            key,
            {
              ...tracker,
              courseKey: key,
              points: {
                ...tracker.points,
                assessments: mutate(tracker.points.assessments || []),
              },
              updatedAt: new Date().toISOString(),
            },
            legacyKey
          ),
        };
      });
    },
    [write]
  );

  const addAssessment = useCallback(
    (identity: CourseIdentity, item: AssessmentItem) => {
      writeAssessments(identity, (assessments) => [...assessments, item]);
    },
    [writeAssessments]
  );

  const updateAssessment = useCallback(
    (identity: CourseIdentity, itemId: string, patch: Partial<AssessmentItem>) => {
      writeAssessments(identity, (assessments) => assessments.map((a) => (a.id === itemId ? { ...a, ...patch } : a)));
    },
    [writeAssessments]
  );

  const removeAssessment = useCallback(
    (identity: CourseIdentity, itemId: string) => {
      writeAssessments(identity, (assessments) => assessments.filter((a) => a.id !== itemId));
    },
    [writeAssessments]
  );

  const updateGradingScale = useCallback(
    (courseKey: string, scale: GradingScaleTier[]) => {
      write((prev) => {
        const { key, tracker } = getTrackerOrCreateByKey(prev.trackers, courseKey);
        return {
          ...prev,
          trackers: {
            ...prev.trackers,
            [key]: {
              ...tracker,
              settingsConfirmed: true,
              points: {
                ...tracker.points,
                gradingScale: scale,
              },
              updatedAt: new Date().toISOString(),
            },
          },
        };
      });
    },
    [write]
  );

  /** Marks the attendance limits + grading scale as checked against the syllabus. */
  const confirmTrackerSettings = useCallback(
    (courseKey: string) => {
      write((prev) => {
        const { key, tracker } = getTrackerOrCreateByKey(prev.trackers, courseKey);
        return {
          ...prev,
          trackers: {
            ...prev.trackers,
            [key]: {
              ...tracker,
              settingsConfirmed: true,
              updatedAt: new Date().toISOString(),
            },
          },
        };
      });
    },
    [write]
  );

  const updateLimits = useCallback(
    (
      courseKey: string,
      limits: {
        maxAbsencesAllowed: number | null;
        minAttendancePercent: number | null;
        useWeightedCategories?: boolean;
        totalPoints?: number;
      }
    ) => {
      write((prev) => {
        const { key, tracker } = getTrackerOrCreateByKey(prev.trackers, courseKey);
        return {
          ...prev,
          trackers: {
            ...prev.trackers,
            [key]: {
              ...tracker,
              settingsConfirmed: true,
              attendance: {
                ...tracker.attendance,
                maxAbsencesAllowed: limits.maxAbsencesAllowed,
                minAttendancePercent: limits.minAttendancePercent,
              },
              points: {
                ...tracker.points,
                useWeightedCategories: limits.useWeightedCategories ?? tracker.points.useWeightedCategories,
                totalPoints: limits.totalPoints ?? tracker.points.totalPoints,
              },
              updatedAt: new Date().toISOString(),
            },
          },
        };
      });
    },
    [write]
  );

  const isSessionHoliday = useCallback(
    (
      courseName: string,
      courseCode?: string | null,
      semester?: string | null,
      dateStr?: string | null,
      startTime?: string | null
    ): boolean => {
      if (!dateStr) return false;
      const tracker = getTracker({
        courseCode: courseCode ?? undefined,
        courseName,
        semester: semester ?? undefined,
      });
      if (!tracker || !tracker.attendance?.sessions) return false;
      const dateSessions = tracker.attendance.sessions.filter(
        (s) => s.date === dateStr && s.status === 'cancelled'
      );
      if (dateSessions.length === 0) return false;

      if (startTime) {
        const normStart = normalizeTime(startTime);
        if (normStart) {
          const exactMatch = dateSessions.find(
            (s) => s.startTime && normalizeTime(s.startTime) === normStart
          );
          if (exactMatch) return true;

          const otherTimed = tracker.attendance.sessions.some(
            (s) => s.date === dateStr && s.startTime && normalizeTime(s.startTime) !== normStart
          );
          if (otherTimed) {
            return dateSessions.some((s) => !s.startTime);
          }
        }
      }

      return dateSessions.length > 0;
    },
    [getTracker]
  );

  return {
    data,
    isLoading: query.isLoading,
    isSaving: saveMutation.isPending,
    update: write,
    getTracker,
    getOrCreateTracker,
    upsertTracker,
    setAttendanceStatus,
    addAttendanceSession,
    removeAttendanceSession,
    restoreAttendanceSession,
    addAssessment,
    updateAssessment,
    removeAssessment,
    updateGradingScale,
    updateLimits,
    confirmTrackerSettings,
    isSessionHoliday,
  };
}
