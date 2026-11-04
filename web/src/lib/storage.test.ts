import { describe, expect, it } from 'vitest';
import { defaultStorage, resolveStorage, type StorageLike } from './storage';

function stub(): StorageLike {
  const values = new Map<string, string>();
  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, value); },
    removeItem: (key) => { values.delete(key); },
  };
}

describe('storage', () => {
  it('resolves an injected storage verbatim', () => {
    const custom = stub();
    expect(resolveStorage(custom)).toBe(custom);
  });

  it('treats null as explicitly no storage', () => {
    expect(resolveStorage(null)).toBeNull();
  });

  it('falls back to the default browser storage when undefined', () => {
    expect(resolveStorage()).toBe(defaultStorage());
  });

  it('returns null for the default storage in the node test environment', () => {
    expect(defaultStorage()).toBeNull();
  });
});
