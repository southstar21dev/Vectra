import { pb } from '@/lib/pocketbase';

/**
 * Cloud access for the user's course tracker.
 *
 * `course_tracker_data` is a singleton row per user: `user` (relation), `data`
 * (the encrypted `vc2:` blob) and `updated_at`. PocketBase has no upsert, so
 * the writer below finds the row first and updates it, or creates it when the
 * user has none yet. A unique index on `user` (migration 1700000001) makes the
 * singleton guarantee structural.
 */

/** PocketBase signals a missing record with HTTP 404. */
function isNotFound(err: unknown): boolean {
  return (err as { status?: number } | null)?.status === 404;
}

function findRow(userId: string): Promise<{ id: string; data?: string }> {
  return pb
    .collection('course_tracker_data')
    .getFirstListItem<{ id: string; data?: string }>(pb.filter('user = {:u}', { u: userId }));
}

/**
 * Reads the encrypted tracker blob for `userId`. Returns `null` when the user
 * has no row yet (404); any other failure is rethrown so callers can keep their
 * offline cache without seeding the cloud from a possibly-stale local copy.
 */
export async function fetchCourseTrackerData(userId: string): Promise<string | null> {
  try {
    const row = await findRow(userId);
    return row?.data ?? null;
  } catch (err) {
    if (isNotFound(err)) return null;
    throw err;
  }
}

/**
 * Persists the encrypted tracker blob, updating the singleton row or creating
 * it. Only a 404 from the lookup is treated as "no row"; any other read error
 * is rethrown so a transient failure cannot fall through to `create` and
 * produce a duplicate row.
 */
export async function upsertCourseTrackerData(
  userId: string,
  encryptedData: string
): Promise<void> {
  let existing: { id: string } | null = null;
  try {
    existing = await findRow(userId);
  } catch (err) {
    if (!isNotFound(err)) throw err;
  }

  const payload = { data: encryptedData, updated_at: new Date().toISOString(), user: userId };
  if (existing) {
    await pb.collection('course_tracker_data').update(existing.id, payload);
    return;
  }

  try {
    await pb.collection('course_tracker_data').create(payload);
  } catch (createErr) {
    // Race: another writer created the singleton row between our lookup and
    // create (the unique index rejects the duplicate). Adopt the existing row
    // and update it rather than surfacing a constraint error.
    let raced: { id: string } | null = null;
    try {
      raced = await findRow(userId);
    } catch {
      raced = null;
    }
    if (!raced) throw createErr;
    await pb.collection('course_tracker_data').update(raced.id, payload);
  }
}
