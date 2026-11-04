/**
 * Session watchdog — keeps the PWA's auth session alive and prevents the
 * "zombie session" state where the PocketBase auth token expires, the
 * automatic refresh fails (a failed refresh while the PWA was backgrounded),
 * and every authed call silently 401s until the user logs out and back in.
 *
 * - Refreshes explicitly at known moments: app start, return to foreground
 *   (visibilitychange), reconnect (online), plus a 5-minute backstop.
 * - On a DEFINITIVE refresh failure (invalid/revoked token: HTTP 401/403) it
 *   signs out cleanly with a toast, so the auth guard routes to login instead
 *   of a stuck page. Network errors are ignored and retried at the next
 *   trigger — flaky mobile data can't lock the user out.
 *
 * Mount once, e.g. in App: `useSessionWatchdog()`.
 */
import { useEffect } from 'react';
import { toast } from 'sonner';
import { ClientResponseError } from 'pocketbase';
import { pb } from '@/lib/pocketbase';

/** Backstop for apps that stay foregrounded past token expiry. */
const BACKSTOP_INTERVAL_MS = 5 * 60_000;

/** True when the token is genuinely dead, not just a network hiccup. */
function isDefinitiveAuthError(err: unknown): boolean {
  // PocketBase wraps every failure (including network errors, status 0) in a
  // ClientResponseError; only a real auth rejection is definitive.
  return err instanceof ClientResponseError && (err.status === 401 || err.status === 403);
}

export function useSessionWatchdog(): void {
  useEffect(() => {
    let backstopId: number | undefined;

    const expireSession = () => {
      // Flag for the login page hint, then toast — the Toaster lives in
      // AppShell, which unmounts once sign-out routes to /login.
      try {
        sessionStorage.setItem('vectra_session_expired', '1');
      } catch {
        // storage unavailable — the toast still explains it
      }
      toast.error('Session expired — please sign in again');
      pb.authStore.clear();
    };

    const refreshIfNeeded = async () => {
      // Signed out — nothing to keep alive.
      if (!pb.authStore.token) return;
      // A token that is still stored but already past expiry can't be
      // refreshed; leaving it in place is exactly the zombie session.
      if (!pb.authStore.isValid) {
        expireSession();
        return;
      }

      try {
        // Not `authRefresh()`: the SDK resolves a 200 whose body could not be
        // read (page reload/navigation mid-request) as `{}` and then saves that,
        // wiping the token — a silent sign-out. Save only a real token.
        const res = await pb.send<{ token?: string; record?: Record<string, unknown> }>(
          '/api/collections/users/auth-refresh',
          { method: 'POST' }
        );
        if (res?.token && res.record) {
          pb.authStore.save(res.token, res.record as unknown as typeof pb.authStore.record);
        }
      } catch (err) {
        if (!isDefinitiveAuthError(err)) return; // retryable — keep the session
        expireSession();
      }
    };

    void refreshIfNeeded();

    const onVisibility = () => {
      if (document.visibilityState === 'visible') void refreshIfNeeded();
    };
    const onOnline = () => void refreshIfNeeded();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('online', onOnline);
    backstopId = window.setInterval(() => void refreshIfNeeded(), BACKSTOP_INTERVAL_MS);

    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('online', onOnline);
      if (backstopId !== undefined) window.clearInterval(backstopId);
    };
  }, []);
}
