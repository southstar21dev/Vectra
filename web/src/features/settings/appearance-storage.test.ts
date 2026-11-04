import { describe, expect, it } from 'vitest';
import {
  APPEARANCE_STORAGE_KEY,
  readStoredAppearance,
  writeStoredAppearance,
} from './appearance-storage';
import type { StorageLike } from '@/lib/storage';
import { DEFAULT_APPEARANCE, type AppearancePrefs } from './appearance-prefs';

function stubStorage(initial: Record<string, string> = {}): NonNullable<StorageLike> {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key) => map.get(key) ?? null,
    setItem: (key, value) => {
      map.set(key, value);
    },
  };
}

const prefs: AppearancePrefs = { theme: 'light', accent: 'teal', background: 'aurora', timeFormat: '24h' };

describe('readStoredAppearance', () => {
  it('returns defaults when nothing is stored or storage is missing', () => {
    expect(readStoredAppearance(stubStorage())).toEqual(DEFAULT_APPEARANCE);
    expect(readStoredAppearance(null)).toEqual(DEFAULT_APPEARANCE);
  });

  it('round-trips what writeStoredAppearance stored', () => {
    const s = stubStorage();
    writeStoredAppearance(s, prefs);
    expect(readStoredAppearance(s)).toEqual(prefs);
  });

  it('returns defaults for corrupt JSON or unknown keys', () => {
    expect(readStoredAppearance(stubStorage({ [APPEARANCE_STORAGE_KEY]: '{not json' }))).toEqual(DEFAULT_APPEARANCE);
    expect(readStoredAppearance(stubStorage({ [APPEARANCE_STORAGE_KEY]: '{"accent":"neon"}' }))).toEqual(DEFAULT_APPEARANCE);
  });
});

describe('writeStoredAppearance', () => {
  it('writes a JSON value under the mirror key', () => {
    const s = stubStorage();
    writeStoredAppearance(s, prefs);
    expect(s.getItem(APPEARANCE_STORAGE_KEY)).toBe(JSON.stringify(prefs));
  });

  it('does not throw when storage is null', () => {
    expect(() => writeStoredAppearance(null, prefs)).not.toThrow();
  });
});
