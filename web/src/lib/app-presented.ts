/**
 * Cold-boot presentation signal.
 *
 * On a cold start the app renders its first screen behind the boot splash for
 * at least `SPLASH_MIN_DURATION_MS` (normally `SPLASH_TARGET_DURATION_MS`). Anything that wants to animate "when the
 * app opens" would otherwise play to an empty room — the reveal would be spent
 * while the splash still covers the screen, and the user would open the app to
 * a page that had already settled.
 *
 * So the splash marks presentation the moment it starts clearing, and the
 * dashboard's card entrance waits here instead of guessing at a timeout. The
 * reveal therefore plays *during* the splash's fade-out and lands as the first
 * thing the user sees.
 *
 * Module state, like `navigation-scroll.ts`: it describes one boot, not one
 * component, and every listener that asks has the same answer.
 */

/**
 * Hard ceiling on how long a reveal may wait for the splash. The splash always
 * is normally gone by ~2.5s, so a screen still waiting well past that means
 * the splash is stuck or was never mounted — and an invisible dashboard is a far
 * worse failure than a reveal that starts without it.
 */
export const APP_PRESENTED_FALLBACK_MS = 3500;

let presented = false;
const listeners = new Set<() => void>();

/**
 * Marks the first screen as presented. Called by the boot splash as it begins
 * clearing. Idempotent — later boots of the splash (there is only one) or
 * repeated calls never re-fire listeners.
 */
export function markAppPresented(): void {
  if (presented) return;
  presented = true;
  const pending = [...listeners];
  listeners.clear();
  for (const listener of pending) listener();
}

/** True once the boot splash has started clearing. */
export function hasAppPresented(): boolean {
  return presented;
}

/**
 * Runs `listener` immediately when the app is already presented, otherwise as
 * soon as it is. Returns an unsubscribe.
 */
export function onAppPresented(listener: () => void): () => void {
  if (presented) {
    listener();
    return () => {};
  }

  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
