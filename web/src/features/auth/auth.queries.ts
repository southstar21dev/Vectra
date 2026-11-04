import { queryOptions } from '@tanstack/react-query';
import { ClientResponseError } from 'pocketbase';
import { queryKeys } from '@/lib/query-keys';
import { pb } from '@/lib/pocketbase';
import type { UsersRecord } from '@/types/pocketbase-types';

/**
 * The user profile is the PocketBase `users` auth record itself (the old
 * `profiles` table is gone). Text fields the app clears with `null`
 * (`full_name`, `avatar_url`, `neptun_ics_url`, `neptun_last_sync`) keep their
 * nullable type so existing `updateProfile` call sites are unchanged.
 */
export type Profile = Omit<
  UsersRecord,
  'full_name' | 'avatar_url' | 'neptun_ics_url' | 'neptun_last_sync'
> & {
  full_name?: string | null;
  avatar_url?: string | null;
  neptun_ics_url?: string | null;
  neptun_last_sync?: string | null;
};

export const sessionQueryOptions = queryOptions({
  queryKey: queryKeys.auth.session,
  queryFn: () => (pb.authStore.isValid ? pb.authStore : null),
  retry: false,
  staleTime: 1000 * 60 * 5,
});

export const profileQueryOptions = queryOptions({
  queryKey: queryKeys.profile,
  queryFn: async (): Promise<Profile | null> => {
    const id = pb.authStore.record?.id;
    if (!id) return null;
    try {
      return (await pb.collection('users').getOne(id)) as unknown as Profile;
    } catch (err) {
      // Only a missing record means "no profile". Anything else (offline, 5xx,
      // a 401 the watchdog will act on) must surface as a query error: a
      // `null` profile would read as "no saved settings" and clobber the
      // user's appearance/Neptun state with defaults.
      if (err instanceof ClientResponseError && err.status === 404) {
        console.warn('Profile fetch warning:', err);
        return null;
      }
      throw err;
    }
  },
  retry: false,
});
