import { useCallback, useEffect, useMemo, useSyncExternalStore } from 'react';
import { useAuth } from '@/features/auth/use-auth';
import {
  DEFAULT_APPEARANCE,
  effectiveTheme,
  normalizeAppearance,
  type AppearancePrefs,
} from '@/features/settings/appearance-prefs';
import {
  readStoredAppearance,
  writeStoredAppearance,
} from '@/features/settings/appearance-storage';
import { applyAppearanceToDom } from '@/features/settings/appearance-dom';

// One module-level store shared by every `useAppearance()` caller, so the
// settings card, AppearanceManager and feature components can never drift
// apart (a stale copy used to re-apply its old theme on OS scheme changes).
let currentPrefs: AppearancePrefs | null = null;
// Profile id whose stored appearance has already hydrated the store.
let hydratedForUserId: string | null = null;
const listeners = new Set<() => void>();
let detachSchemeListener: (() => void) | null = null;

function getPrefs(): AppearancePrefs {
  if (currentPrefs === null) {
    currentPrefs =
      typeof window !== 'undefined' ? readStoredAppearance(window.localStorage) : DEFAULT_APPEARANCE;
  }
  return currentPrefs;
}

function getServerPrefs(): AppearancePrefs {
  return DEFAULT_APPEARANCE;
}

function setPrefs(next: AppearancePrefs): void {
  currentPrefs = next;
  if (typeof window !== 'undefined') writeStoredAppearance(window.localStorage, next);
  applyAppearanceToDom(next);
  listeners.forEach((listener) => listener());
}

/**
 * Sign-out: forget whose appearance the store holds and fall back to the
 * defaults (store, localStorage mirror and DOM), so the next account on this
 * device never inherits the previous one's theme. Called from the auth-store
 * sync (auth-store-sync.ts).
 */
export function resetAppearanceForSignOut(): void {
  hydratedForUserId = null;
  setPrefs(DEFAULT_APPEARANCE);
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  // Live re-apply when the OS scheme flips while in system mode. A single
  // listener reads the store at event time, so it never applies stale prefs.
  if (!detachSchemeListener && typeof window !== 'undefined' && window.matchMedia) {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => {
      const prefs = getPrefs();
      if (prefs.theme === 'system') applyAppearanceToDom(prefs);
    };
    mq.addEventListener('change', onChange);
    detachSchemeListener = () => mq.removeEventListener('change', onChange);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      detachSchemeListener?.();
      detachSchemeListener = null;
    }
  };
}

export function useAppearance() {
  const { profile, updateProfile } = useAuth();
  const prefs = useSyncExternalStore(subscribe, getPrefs, getServerPrefs);

  // Profile is the source of truth — hydrate once per signed-in user when it
  // loads (and write the mirror so the next first paint matches). A profile
  // with no saved appearance hydrates to the defaults rather than keeping
  // whatever the device last showed (possibly another account's). Subsequent
  // profile refetches (e.g. our own updates) must not clobber the user's local
  // selection.
  useEffect(() => {
    if (profile?.id && hydratedForUserId !== profile.id) {
      hydratedForUserId = profile.id;
      setPrefs(profile.appearance != null ? normalizeAppearance(profile.appearance) : DEFAULT_APPEARANCE);
    }
  }, [profile]);

  // Apply to the DOM whenever prefs change (idempotent across callers).
  useEffect(() => {
    applyAppearanceToDom(prefs);
  }, [prefs]);

  const setAppearance = useCallback(
    (patch: Partial<AppearancePrefs>) => {
      const next = { ...getPrefs(), ...patch };
      setPrefs(next);
      if (profile?.id) {
        updateProfile({ appearance: next }).catch((err) => {
          console.warn('Appearance sync failed:', err);
        });
      }
    },
    [profile?.id, updateProfile]
  );

  const effective = useMemo(() => {
    const systemPrefersDark =
      typeof window !== 'undefined' && window.matchMedia
        ? window.matchMedia('(prefers-color-scheme: dark)').matches
        : false;
    return effectiveTheme(prefs.theme, systemPrefersDark);
  }, [prefs.theme]);

  return { prefs, setAppearance, effective };
}
