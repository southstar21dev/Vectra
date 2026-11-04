import { decryptField, encryptField, isCiphertext } from '@/lib/encryption';
import { parseTrackerData, serializeTrackerData, type CourseTrackerData } from './tracker-types';
import { fetchCourseTrackerData, upsertCourseTrackerData } from './course-tracker-cloud';
import { enqueueCloudSave, isCloudReadable, noteHeldBackWrite } from './course-tracker-sync';
import { mergeTrackerData } from './course-tracker-store';

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
  await enqueueCloudSave(userId, async () => {
    const remote = await readCloudTrackerData(userId);
    let merged = remote ? mergeTrackerData(local, remote) : local;
    if (options?.dropKeys?.length) {
      const trackers = { ...merged.trackers };
      for (const key of options.dropKeys) delete trackers[key];
      merged = { ...merged, trackers };
    }
    const encrypted = (await encryptField(serializeTrackerData(merged))) as string;
    await upsertCourseTrackerData(userId, encrypted);
  });
  return 'uploaded';
}
