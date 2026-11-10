import type { QueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { pb } from '@/lib/pocketbase';
import { clearAllOfflineUserData, settleKeptOfflineQueue } from '@/lib/offline-cache';
import { unsubscribeDevicePushLocally } from '@/features/notifications/use-push-subscription';
import { resetAppearanceForSignOut } from '@/hooks/use-appearance';
import { takeExplicitSignOut } from './sign-out-intent';

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
 *
 * The offline queue (schedule items, course tracker data) is the exception: it
 * holds writes with no other copy, so it is wiped only on an explicit sign-out
 * (`markExplicitSignOut` in sign-out-intent.ts). Any other sign-out keeps it
 * for that account, and it is deleted if a different account signs in on this
 * device.
 */
let detach: (() => void) | null = null;

export function installAuthStoreSync(queryClient: QueryClient): () => void {
  detach?.();
  let lastUserId: string | null = pb.authStore.record?.id ?? null;
  const unsubscribe = pb.authStore.onChange((token, record) => {
    if (token) {
      if (record?.id && record.id !== lastUserId) settleKeptOfflineQueue(record.id);
      lastUserId = record?.id ?? lastUserId;
      queryClient.invalidateQueries({ queryKey: queryKeys.auth.root });
      queryClient.invalidateQueries({ queryKey: queryKeys.profile });
      return;
    }
    const keepQueueFor = takeExplicitSignOut() ? null : lastUserId;
    lastUserId = null;
    clearAllOfflineUserData(undefined, keepQueueFor);
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
