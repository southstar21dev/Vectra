import { useSyncExternalStore } from 'react';

/**
 * True below the app's mobile/desktop boundary (xl = 1280px) — the same
 * boundary the navbar uses to switch between the bottom nav pill and the
 * desktop rail. Dialogs present full-screen exactly when the app is in its
 * mobile mode.
 */
export const COMPACT_VIEWPORT_QUERY = '(min-width: 1280px)';

let cachedMediaQuery: MediaQueryList | null = null;

function getMediaQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  if (!cachedMediaQuery) cachedMediaQuery = window.matchMedia(COMPACT_VIEWPORT_QUERY);
  return cachedMediaQuery;
}

export function readCompactViewport(mql: Pick<MediaQueryList, 'matches'> | null): boolean {
  return mql ? !mql.matches : false;
}

export function subscribeCompactViewport(
  mql: Pick<MediaQueryList, 'addEventListener' | 'removeEventListener'> | null,
  onChange: () => void
): () => void {
  if (!mql) return () => {};
  mql.addEventListener('change', onChange);
  return () => mql.removeEventListener('change', onChange);
}

function subscribe(onChange: () => void) {
  return subscribeCompactViewport(getMediaQuery(), onChange);
}

function getSnapshot(): boolean {
  return readCompactViewport(getMediaQuery());
}

function getServerSnapshot(): boolean {
  return false;
}

export function useIsCompactViewport(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}
