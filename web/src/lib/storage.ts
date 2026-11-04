/** Minimal storage surface so pure helpers can be unit-tested in the node
 *  environment (there is no window.localStorage there). `removeItem` is
 *  optional because read/write-only consumers (appearance mirror, weather
 *  snapshot) legitimately never remove keys. */
export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem?(key: string): void;
}

export function defaultStorage(): StorageLike | null {
  try {
    if (typeof window !== 'undefined' && window.localStorage) return window.localStorage;
  } catch {
    /* privacy modes / SSR */
  }
  return null;
}

/** Resolve an injected storage override: `undefined` = default browser storage,
 *  `null` = explicitly no storage (tests / injected stubs). */
export function resolveStorage(custom?: StorageLike | null): StorageLike | null {
  if (custom !== undefined) return custom;
  return defaultStorage();
}
