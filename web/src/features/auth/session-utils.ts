import { pb } from '@/lib/pocketbase';

/**
 * Bounds for auth operations that can otherwise hang forever.
 *
 * These helpers let the app fail fast: bound an auth call, and if the stored
 * session's token is genuinely dead, drop it so this open AND future opens
 * route straight to /login instead of hanging again.
 */

/** How long to wait for the initial session check before giving up. */
export const SESSION_CHECK_TIMEOUT_MS = 10_000;

/** Sign-out gets a bit less time — it usually succeeds without a network hop. */
export const SIGN_OUT_TIMEOUT_MS = 5_000;

/** Reject `promise` if it hasn't settled within `ms`. */
export function withTimeout<T>(promise: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const id = setTimeout(() => reject(new Error(message)), ms);
    promise.then(
      (v) => {
        clearTimeout(id);
        resolve(v);
      },
      (e) => {
        clearTimeout(id);
        reject(e);
      }
    );
  });
}

/** PocketBase stores auth under a single localStorage key. */
const PB_AUTH_KEY = 'pocketbase_auth';

/** Remove PocketBase's stored session from localStorage and clear the auth store. */
export function clearStoredSession(storage?: Storage): void {
  const s = storage ?? (typeof window !== 'undefined' ? window.localStorage : undefined);
  s?.removeItem(PB_AUTH_KEY);
  pb.authStore.clear();
}

/** True when the stored session's token is missing or genuinely expired. */
export function isStoredSessionExpired(): boolean {
  const token = pb.authStore.token;
  if (!token) return true;
  return !pb.authStore.isValid;
}

/**
 * Drop the stored session IF a token is present but invalid. Returns true when
 * something was cleared.
 */
export function clearStoredSessionIfExpired(): boolean {
  if (pb.authStore.token && !pb.authStore.isValid) {
    clearStoredSession();
    return true;
  }
  return false;
}
