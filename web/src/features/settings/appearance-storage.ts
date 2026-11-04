import { normalizeAppearance, type AppearancePrefs } from './appearance-prefs';
import type { StorageLike } from '@/lib/storage';

export const APPEARANCE_STORAGE_KEY = 'vectra.appearance';

/** Read + validate the first-paint mirror; anything missing/corrupt → defaults. */
export function readStoredAppearance(storage: StorageLike | null): AppearancePrefs {
  if (!storage) return normalizeAppearance(null);
  try {
    const raw = storage.getItem(APPEARANCE_STORAGE_KEY);
    if (!raw) return normalizeAppearance(null);
    return normalizeAppearance(JSON.parse(raw));
  } catch {
    return normalizeAppearance(null);
  }
}

/** Mirror write; the mirror is a cache, so quota errors are ignored. */
export function writeStoredAppearance(storage: StorageLike | null, prefs: AppearancePrefs): void {
  if (!storage) return;
  try {
    storage.setItem(APPEARANCE_STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // storage full/unavailable — nothing to do
  }
}
