import { decryptField, encryptField, isCiphertext } from '@/lib/encryption';
import { parseTrackerData, serializeTrackerData, type CourseTracker, type CourseTrackerData } from './tracker-types';
import { fetchCourseTrackerData, upsertCourseTrackerData } from './course-tracker-cloud';
import {
  enqueueCloudSave,
  isCloudReadable,
  markCloudReadable,
  noteHeldBackWrite,
  takeHeldBackWrite,
} from './course-tracker-sync';
import { mergeTrackerData, saveCourseTrackerToOfflineCache } from './course-tracker-store';

/** Thrown when the cloud row exists but cannot be decrypted or parsed. */
export class UnreadableCloudTrackerError extends Error {
  constructor() {
    super('Course tracker cloud data is unreadable');
    this.name = 'UnreadableCloudTrackerError';
  }
}

/**
 * Reads and decrypts the user's cloud tracker document. Resolves `null` when the
 * user has no row yet; throws `UnreadableCloudTrackerError` when the row cannot
 * be decrypted/parsed (e.g. written by a newer client), and rethrows network
 * errors, so callers never mistake a failed read for an empty document.
 */
export async function readCloudTrackerData(userId: string): Promise<CourseTrackerData | null> {
  const encrypted = await fetchCourseTrackerData(userId);
  if (!encrypted) return null;
  const decrypted = await decryptField(encrypted);
  const parsed = isCiphertext(decrypted) ? null : parseTrackerData(decrypted);
  if (!parsed) throw new UnreadableCloudTrackerError();
  return parsed;
}

export type TrackerUploadResult = 'uploaded' | 'held-back';

/**
 * The single cloud write path for the tracker blob. The row is one whole
 * document, so a blind upload is last-writer-wins across devices; instead each
 * upload re-reads the cloud copy inside the serialised save queue and merges it
 * (newest `updatedAt` per tracker) before writing. While the cloud copy has not
 * been read on this device the write is held back for the next successful read.
 *
 * `dropKeys` names trackers the caller deliberately removed; they are dropped
 * after the merge so the cloud copy cannot resurrect them.
 */
export async function uploadTrackerData(
  userId: string,
  local: CourseTrackerData,
  options?: { dropKeys?: string[] }
): Promise<TrackerUploadResult> {
  if (!isCloudReadable(userId)) {
    noteHeldBackWrite(userId);
    return 'held-back';
  }
  await enqueueCloudSave(
    userId,
    async (dropKeys) => {
      const remote = await readCloudTrackerData(userId);
      let merged = remote ? mergeTrackerData(local, remote) : local;
      // Keys carried over from a collapsed upload: never drop one this snapshot holds.
      const drop = dropKeys.filter((key) => !(key in local.trackers));
      if (drop.length) {
        const trackers = { ...merged.trackers };
        for (const key of drop) delete trackers[key];
        merged = { ...merged, trackers };
      }
      const encrypted = (await encryptField(serializeTrackerData(merged))) as string;
      await upsertCourseTrackerData(userId, encrypted);
    },
    options?.dropKeys
  );
  return 'uploaded';
}

/** JSON with object keys sorted, so two trackers compare by content, not key order. */
function canonicalJson(value: unknown): string {
  return JSON.stringify(value, (_key, v: unknown) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)))
      : v
  );
}

function sameTracker(a: CourseTracker | undefined, b: CourseTracker | undefined): boolean {
  return a === b || (!!a && !!b && canonicalJson(a) === canonicalJson(b));
}

/** As it reads back after an upload: parsing fills defaults and orders sessions. */
function normalized(data: CourseTrackerData): CourseTrackerData {
  return parseTrackerData(serializeTrackerData(data)) ?? data;
}

/** Whether `local` holds anything the cloud copy lacks or has differently. */
export function trackerDataDiffers(local: CourseTrackerData, cloud: CourseTrackerData): boolean {
  const a = normalized(local).trackers;
  const b = normalized(cloud).trackers;
  return [...new Set([...Object.keys(a), ...Object.keys(b)])].some((key) => !sameTracker(a[key], b[key]));
}

/**
 * The query's online path: read the cloud copy, merge it into the live data
 * (`readCurrent`, re-read at return time so an edit made during the fetch is
 * kept) and upload whenever the merged copy differs from the cloud's. That
 * retries an edit whose upload failed or was held back, even after a reload
 * lost the in-memory "held back" flag. A failed or unreadable read keeps the
 * local copy and the write gate as they are.
 */
export async function syncTrackerDataWithCloud(
  userId: string,
  readCurrent: () => CourseTrackerData,
  saveCache: (data: CourseTrackerData) => void = (data) => saveCourseTrackerToOfflineCache(data, userId)
): Promise<CourseTrackerData> {
  const cached = readCurrent();
  let cloud: CourseTrackerData | null;
  try {
    cloud = await readCloudTrackerData(userId);
  } catch (err) {
    // Offline, or unreadable (e.g. written by a newer client): the gate stays closed
    // for the latter, so the local copy never replaces it.
    console.warn('Failed to load course tracker from cloud, keeping offline cache:', err);
    return cached;
  }
  markCloudReadable(userId);
  const reconciled = cloud ? mergeTrackerData(readCurrent(), cloud) : readCurrent();
  if (cloud) saveCache(reconciled);
  const heldBack = takeHeldBackWrite(userId);
  if (heldBack || trackerDataDiffers(reconciled, cloud ?? { version: 1, trackers: {} })) {
    try {
      await uploadTrackerData(userId, reconciled);
    } catch (err) {
      noteHeldBackWrite(userId);
      console.warn('Failed to upload local course tracker edits:', err);
    }
  }
  return reconciled;
}
