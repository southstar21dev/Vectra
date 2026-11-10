/**
 * Cloud-write gate for the course tracker blob.
 *
 * The cloud row is one whole-document blob, so an upload replaces everything
 * in it. Uploading before the cloud copy has been read (and decrypted) at
 * least once on this device would overwrite other devices' data with a
 * possibly empty local cache. The gate keeps uploads blocked until a cloud
 * read succeeds, remembers that a write was held back, and serialises uploads
 * so a slow older save can never land after a newer one.
 */

const readableUsers = new Set<string>();
const heldBackUsers = new Set<string>();

type PendingSave = { userId: string; run: (dropKeys: string[]) => Promise<void>; dropKeys: string[] };

let chain: Promise<void> = Promise.resolve();
let latestPending: PendingSave | null = null;

export function markCloudReadable(userId: string): void {
  readableUsers.add(userId);
}

export function isCloudReadable(userId: string): boolean {
  return readableUsers.has(userId);
}

/** Records that a local write could not be uploaded because the gate was closed. */
export function noteHeldBackWrite(userId: string): void {
  heldBackUsers.add(userId);
}

/** Returns (and clears) whether a write was held back for `userId`. */
export function takeHeldBackWrite(userId: string): boolean {
  const had = heldBackUsers.has(userId);
  heldBackUsers.delete(userId);
  return had;
}

/**
 * Runs `run` after every previously queued upload. Uploads queued while one is
 * in flight collapse to the newest, since each one carries the full document.
 * The `dropKeys` of every collapsed upload carry over to the one that runs
 * (it gets their union): a plain save that supersedes a Neptun sync's upload
 * must not let the trackers that sync removed come back from the cloud copy.
 */
export function enqueueCloudSave(
  userId: string,
  run: (dropKeys: string[]) => Promise<void>,
  dropKeys: string[] = []
): Promise<void> {
  const carried = latestPending?.userId === userId ? latestPending.dropKeys : [];
  latestPending = { userId, run, dropKeys: Array.from(new Set([...carried, ...dropKeys])) };
  const next = chain.then(async () => {
    const pending = latestPending;
    if (!pending) return;
    latestPending = null;
    await pending.run(pending.dropKeys);
  });
  // Keep the chain alive after a failed upload; the caller still sees the error.
  chain = next.catch(() => undefined);
  return next;
}

/** Test-only reset. */
export function resetCourseTrackerSyncForTests(): void {
  readableUsers.clear();
  heldBackUsers.clear();
  chain = Promise.resolve();
  latestPending = null;
}
