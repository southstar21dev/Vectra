import type { QueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { pb } from '@/lib/pocketbase';
import { clearAllOfflineUserData } from '@/lib/offline-cache';
import { unsubscribeDevicePushLocally } from '@/features/notifications/use-push-subscription';
import { resetAppearanceForSignOut } from '@/hooks/use-appearance';

/**
 * The ONE `pb.authStore.onChange` subscription that keeps the query cache in
 * step with auth. It used to live inside `useAuth()`, which ~15 components
 * call — so every save fired ~15 identical invalidations (one theme change =
 * 16 GETs of the users record). Installed once from main.tsx.
 *
 * The store fires on every save/clear: a non-empty token means a sign-in,
 * token refresh, or an auth-record update — refresh the session/profile
 * caches. An empty token means a sign-out from ANY path (explicit logout,
 * watchdog expiry, a dead token after an email/password change, account
 * deletion, another tab) — drop all cached user data and kill this device's
 * push endpoint, so a signed-out device never keeps receiving the previous
 * account's pushes.
 */
let detach: (() => void) | null = null;

export function installAuthStoreSync(queryClient: QueryClient): () => void {
  detach?.();
  const unsubscribe = pb.authStore.onChange((token) => {
    if (token) {
      queryClient.invalidateQueries({ queryKey: queryKeys.auth.root });
      queryClient.invalidateQueries({ queryKey: queryKeys.profile });
      return;
    }
    clearAllOfflineUserData();
    resetAppearanceForSignOut();
    // The token is already gone, so the server row can't be deleted here
    // (explicit logout does that first); unsubscribing locally makes the
    // push service reject the endpoint, and the server prunes the row on its
    // next 404/410.
    void unsubscribeDevicePushLocally().catch(() => {});
    // Publish the signed-out session to mounted observers first (the auth
    // guard reads it to route to login); `clear()` alone drops the cache
    // without re-rendering them.
    queryClient.setQueryData(queryKeys.auth.session, null);
    queryClient.clear();
  });
  const off = () => {
    unsubscribe();
    if (detach === off) detach = null;
  };
  detach = off;
  return off;
}
