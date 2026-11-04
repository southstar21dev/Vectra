import { pb } from './pocketbase';

/**
 * Get the current auth store or throw if not authenticated.
 */
export function requireSession() {
  if (!pb.authStore.isValid || !pb.authStore.record) {
    throw new Error('Not authenticated');
  }
  return pb.authStore;
}

/** Current user id, or null. Synchronous — PocketBase keeps auth in memory. */
export function getSessionUserId(): string | null {
  return pb.authStore.record?.id ?? null;
}

/** Same as getSessionUserId; kept for the offline-cache call sites. */
export function getStoredUserId(): string | null {
  return pb.authStore.record?.id ?? null;
}
