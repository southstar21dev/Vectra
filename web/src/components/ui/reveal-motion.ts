/**
 * Timing for the app-wide card entrance — the one place to tune the reveal.
 *
 * Every page's cards enter with the same fade-and-rise, staggered in reading
 * order, so a screen's entrance reads the same whichever page it is. Everything
 * here animates opacity + transform only: a filter, shadow or size animation on
 * these cards forces the Android WebView to re-raster the whole composited
 * layer every frame.
 */

import { EASE_DRAWER } from './motion-tokens';

/** Delay added per card, so a stack reads top-to-bottom instead of popping. */
export const REVEAL_STAGGER_S = 0.05;

/** How far a card rises into place. Small: this is a settle, not a slide. */
export const REVEAL_RISE_PX = 14;

/**
 * After this many cards the stagger stops growing, so a page with many cards
 * never leaves the last one waiting on an animation nobody is watching.
 */
export const REVEAL_MAX_STAGGER_STEPS = 5;

/**
 * Tween for the rise: a duration + cubic-bezier, never a spring on `y`. Paired
 * with a full `transform` string (not Motion's independent `x`/`y`), this lets
 * Motion hand the animation to WAAPI so it runs on the compositor and keeps
 * playing while the main thread is busy mounting the new page.
 */
export const REVEAL_TRANSITION = {
  duration: 0.3,
  ease: EASE_DRAWER,
} as const;

/** Entrance delay for the card at `index` (0-based, reading order). */
export function revealDelay(index: number): number {
  const step = Math.min(Math.max(index, 0), REVEAL_MAX_STAGGER_STEPS);
  return step * REVEAL_STAGGER_S;
}
