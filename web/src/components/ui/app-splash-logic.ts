/** The splash is on screen at least this long, its exit included. */
export const SPLASH_MIN_DURATION_MS = 2300;
/** ...and is normally gone by here; it stays longer only while the app is still loading. */
export const SPLASH_TARGET_DURATION_MS = 2500;

/** When the exit may start, so a splash whose app is ready ends right on target. */
export function splashExitDelay(exitMs: number): number {
  return Math.max(SPLASH_MIN_DURATION_MS, SPLASH_TARGET_DURATION_MS) - exitMs;
}

/** The splash starts its exit only when BOTH its exit delay has elapsed and
 *  the app's first content has rendered — never before either, so users never
 *  see the plain loader flash behind it. */
export function shouldDismissSplash(minElapsed: boolean, appReady: boolean): boolean {
  return minElapsed && appReady;
}

/**
 * Reports "app ready" once no `[role="status"]` element remains inside #root —
 * every PageLoader (auth session check + first lazy route chunk) has unmounted.
 * Falls back to "ready immediately" when there is no DOM or MutationObserver.
 * Returns a cleanup function.
 */
export function watchAppReady(onReady: () => void): () => void {
  const root = typeof document !== 'undefined' ? document.getElementById('root') : null;
  if (typeof MutationObserver === 'undefined' || !root) {
    onReady();
    return () => {};
  }

  const check = () => {
    if (!root.querySelector('[role="status"]')) onReady();
  };

  const observer = new MutationObserver(check);
  // `role` too: LoadingOverlay stays mounted and only drops its role="status"
  // when it hides, which a childList-only observer never hears about.
  observer.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['role'] });
  check();
  return () => observer.disconnect();
}