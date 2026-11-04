import { useState, useCallback, useEffect } from 'react';
import { toast } from 'sonner';
import { fetchNeptunICS, parseICSContentAsync } from './ics-parser';
import { getScheduleFromOfflineCache } from './use-schedule';
import { newestStoredSemester, selectSyncSemester, type SemesterTaggedEvent } from './semester-selection';
import { useAuth } from '@/features/auth/use-auth';
import { pb } from '@/lib/pocketbase';
import { requireSession, getSessionUserId } from '@/lib/auth-helpers';
import { encryptField } from '@/lib/encryption';
import { getErrorMessage } from '@/lib/error-utils';

export const SYNC_ERROR_KEY = 'vectra_neptun_sync_error';
export const RATE_LIMIT_STORAGE_KEY = 'vectra_neptun_sync_timestamps';
export const MAX_SYNCS_PER_HOUR = 3;
export const ONE_HOUR_MS = 60 * 60 * 1000;

export type SyncStatus = 'idle' | 'syncing' | 'success' | 'error';

/** One clean, DB-shaped Neptun event (sensitive fields still plaintext here). */
export interface NeptunSyncEvent {
  course_name: string;
  course_code: string;
  instructor: string;
  location: string;
  day_of_week: number;
  start_time: string;
  end_time: string;
  color: string;
  semester: string;
  semester_start: string | null;
  semester_end: string | null;
  event_type: string;
}

/**
 * Persist a full Neptun sync result. The synced set is authoritative, so it
 * REPLACES the user's previous rows (delete + insert). Sensitive fields are
 * encrypted before insert (batched into a single crypto round-trip).
 */
export async function persistNeptunEvents(events: NeptunSyncEvent[]): Promise<void> {
  requireSession();

  const encrypted = await Promise.all(
    events.map(async (evt) => ({
      ...evt,
      course_name: (await encryptField(evt.course_name)) as string,
      course_code: (await encryptField(evt.course_code)) as string,
      instructor: (await encryptField(evt.instructor)) as string,
      location: (await encryptField(evt.location)) as string,
      day_of_week: (await encryptField(String(evt.day_of_week))) as string,
      start_time: (await encryptField(evt.start_time)) as string,
      end_time: (await encryptField(evt.end_time)) as string,
      semester: (await encryptField(evt.semester)) as string,
      semester_start: (await encryptField(evt.semester_start)) as string | null,
      semester_end: (await encryptField(evt.semester_end)) as string | null,
      event_type: (await encryptField(evt.event_type)) as string,
    }))
  );

  // Atomic replace in a single transaction (server route) — a mid-sync
  // failure can never wipe the calendar, and concurrent syncs can't duplicate.
  await pb.send('/api/vectra/neptun/replace', { method: 'POST', body: { events: encrypted } });
}

/** Remove all Neptun-synced events for the current user (URL cleared / logout flow). */
export async function clearNeptunEvents(): Promise<void> {
  const userId = getSessionUserId();
  if (!userId) return;
  const mine = await pb.collection('neptun_schedule_events').getFullList({
    filter: pb.filter('user = {:u}', { u: userId }),
  });
  await Promise.all(mine.map((row) => pb.collection('neptun_schedule_events').delete(row.id)));
}

/**
 * The semester a sync (background or wizard) imports: see selectSyncSemester.
 * The stored semester comes from this account's cached schedule, so a sync can
 * never swap a newer stored semester for an older one.
 */
export function pickNeptunSyncSemester(events: SemesterTaggedEvent[], at: Date = new Date()): string | null {
  const stored = newestStoredSemester(getScheduleFromOfflineCache(getSessionUserId()) ?? []);
  return selectSyncSemester(events, { at, storedSemester: stored });
}

export function checkSyncRateLimit(): { allowed: boolean; remainingMinutes: number } {
  try {
    const raw = localStorage.getItem(RATE_LIMIT_STORAGE_KEY);
    const timestamps: number[] = raw ? JSON.parse(raw) : [];
    const now = Date.now();
    const valid = timestamps.filter((t) => now - t < ONE_HOUR_MS);

    if (valid.length >= MAX_SYNCS_PER_HOUR) {
      const oldest = Math.min(...valid);
      const remainingMinutes = Math.ceil((ONE_HOUR_MS - (now - oldest)) / 60000);
      return { allowed: false, remainingMinutes };
    }

    return { allowed: true, remainingMinutes: 0 };
  } catch {
    return { allowed: true, remainingMinutes: 0 };
  }
}

/** Stored sync error; null when none or storage is unavailable. */
function readStoredSyncError(): string | null {
  try {
    return localStorage.getItem(SYNC_ERROR_KEY);
  } catch {
    return null;
  }
}

function writeStoredSyncError(message: string | null): void {
  try {
    if (message) localStorage.setItem(SYNC_ERROR_KEY, message);
    else localStorage.removeItem(SYNC_ERROR_KEY);
  } catch {
    // Storage blocked: the in-memory state still reflects the error.
  }
}

export function useNeptunSync() {
  const { updateProfile } = useAuth();

  const [syncStatus, setSyncStatus] = useState<SyncStatus>(() => {
    return readStoredSyncError() ? 'error' : 'idle';
  });

  const [syncError, setSyncError] = useState<string | null>(readStoredSyncError);

  // Keep state in sync across tabs or custom events
  useEffect(() => {
    const handleStorageChange = (e: StorageEvent) => {
      if (e.key === SYNC_ERROR_KEY) {
        if (e.newValue) {
          setSyncStatus('error');
          setSyncError(e.newValue);
        } else {
          setSyncStatus((prev) => (prev === 'error' ? 'idle' : prev));
          setSyncError(null);
        }
      }
    };

    // We also use a custom event for in-tab reactivity without needing storage events
    // A cleared error only ends the error state: runSync clears it right as
    // it sets 'syncing' / 'success', and forcing 'idle' here hid both.
    const handleLocalSyncError = () => {
      const err = readStoredSyncError();
      if (err) {
        setSyncStatus('error');
        setSyncError(err);
      } else {
        setSyncStatus((prev) => (prev === 'error' ? 'idle' : prev));
        setSyncError(null);
      }
    };

    window.addEventListener('storage', handleStorageChange);
    window.addEventListener('vectra_sync_error_updated', handleLocalSyncError);
    return () => {
      window.removeEventListener('storage', handleStorageChange);
      window.removeEventListener('vectra_sync_error_updated', handleLocalSyncError);
    };
  }, []);

  const runSync = useCallback(async (url: string, isAutoSync: boolean = false) => {
    if (!url) return;

    setSyncStatus('syncing');
    setSyncError(null);
    writeStoredSyncError(null);
    window.dispatchEvent(new Event('vectra_sync_error_updated'));

    try {
      const icsText = await fetchNeptunICS(url);
      const result = await parseICSContentAsync(icsText);

      if (result.valid.length === 0) {
        throw new Error('No valid class events found in calendar feed.');
      }

      // The feed carries every past semester. The synced set is authoritative
      // (the replace route deletes + re-inserts), so exactly one semester is
      // imported — the same pick the import wizard makes.
      const semester = pickNeptunSyncSemester(result.valid);
      if (!semester) {
        throw new Error('No class events found for the current or upcoming semester.');
      }
      const currentSemesterEvents = result.valid.filter((evt) => evt.semester === semester);

      const cleanEvents: NeptunSyncEvent[] = currentSemesterEvents.map((evt) => ({
        course_name: String(evt.course_name || ''),
        course_code: String(evt.course_code || ''),
        instructor: String(evt.instructor || ''),
        location: String(evt.location || ''),
        day_of_week: Number(evt.day_of_week),
        start_time: String(evt.start_time),
        end_time: String(evt.end_time),
        color: String(evt.color || '#3B82F6'),
        semester: String(evt.semester || ''),
        semester_start: evt.semester_start ? String(evt.semester_start) : null,
        semester_end: evt.semester_end ? String(evt.semester_end) : null,
        event_type: String(evt.event_type || 'class'),
      }));

      // Upload the synced calendar, encrypted at rest, so every device sees it.
      await persistNeptunEvents(cleanEvents);

      // Automatically autofill course tracker sessions for all classes in this semester.
      // Imported on demand: the tracker module (and its zod schemas) is otherwise
      // pulled into the startup bundle, yet this runs only after a sync finishes.
      try {
        const { syncCourseTrackersFromScheduleEvents } = await import(
          '@/features/course-tracker/schedule-reconciler'
        );
        await syncCourseTrackersFromScheduleEvents(cleanEvents);
      } catch (trackerErr) {
        console.warn('Auto-populating course trackers during Neptun sync failed:', trackerErr);
      }

      const nowMs = Date.now();

      // Record manual sync timestamp for rate limiting AFTER success
      // so failed syncs don't consume quota
      if (!isAutoSync) {
        try {
          const raw = localStorage.getItem(RATE_LIMIT_STORAGE_KEY);
          const timestamps: number[] = raw ? JSON.parse(raw) : [];
          const valid = timestamps.filter((t) => nowMs - t < ONE_HOUR_MS);
          valid.push(nowMs);
          localStorage.setItem(RATE_LIMIT_STORAGE_KEY, JSON.stringify(valid));
        } catch (e) {
          console.error(e);
        }
      }

      try {
        await updateProfile({ neptun_last_sync: new Date(nowMs).toISOString() });
      } catch (err) {
        console.warn('Failed to update sync timestamp in profile:', err);
      }

      setSyncStatus('success');
      setSyncError(null);
      // A manual "Sync now" otherwise finishes silently; background syncs stay quiet.
      if (!isAutoSync) {
        toast.success('Neptun schedule synced', { description: `Imported ${semester}.` });
      }
      writeStoredSyncError(null);
      window.dispatchEvent(new Event('vectra_sync_error_updated'));

      // Trigger UI updates for schedules
      window.dispatchEvent(new Event('vectra_neptun_sync_completed'));
      window.dispatchEvent(new Event('vectra_neptun_schedule_updated'));

      // Auto-reset success state after a few seconds
      setTimeout(() => {
        setSyncStatus(prev => prev === 'success' ? 'idle' : prev);
      }, 3000);

    } catch (err) {
      console.error('Neptun sync failed:', err);
      const msg = getErrorMessage(err, 'Failed to sync with Neptun calendar.');
      setSyncStatus('error');
      setSyncError(msg);
      writeStoredSyncError(msg);
      window.dispatchEvent(new Event('vectra_sync_error_updated'));
    }
  }, [updateProfile]);

  return {
    syncStatus,
    syncError,
    runSync
  };
}
