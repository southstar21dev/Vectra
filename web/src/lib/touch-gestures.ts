/**
 * Pure, testable gesture decision logic shared by the mobile bottom-nav
 * (swipe-to-cycle tabs, tap-to-open) and the schedule tab's day switcher
 * (swipe-to-change day, tap a day pill). Extracted so touch handling is
 * unit-testable and the rules live in one place:
 *
 * - A stationary touch (movement within `TAP_SLOP`) is a tap — callers act on
 *   `touchend` instead of relying on the browser's synthesized `click`,
 *   because the first click after a swipe is suppressed/mis-targeted on some
 *   Android WebViews.
 * - A horizontal drag beyond `MIN_SWIPE_DISTANCE` (and clearly more horizontal
 *   than vertical) is a swipe: a right swipe steps forward (+1, "to the
 *   right"), a left swipe steps back (-1, "to the left"), wrapping around.
 */

export interface NavGesturePoint {
  x: number;
  y: number;
}

/** A touch with less movement than this (in either axis) is a tap, not a swipe. */
export const TAP_SLOP = 20;
/** A horizontal drag must move at least this far to count as a swipe. */
export const MIN_SWIPE_DISTANCE = 50;
/** Swipes must be clearly horizontal — ignore vertical drags. */
const VERTICAL_DRAG_RATIO = 1.2;

/** True when the touch barely moved — callers should treat it as a tap. */
export function isTapGesture(start: NavGesturePoint, end: NavGesturePoint): boolean {
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  return Math.abs(dx) < TAP_SLOP && Math.abs(dy) < TAP_SLOP;
}

/**
 * Horizontal swipe direction as a ring step: a right swipe steps forward
 * (+1 — next / "to the right"), a left swipe steps back (-1 — previous /
 * "to the left"). Returns 0 for short drags and vertical drags, which are
 * not swipes.
 */
export function swipeStep(start: NavGesturePoint, end: NavGesturePoint): 1 | -1 | 0 {
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  if (Math.abs(dx) < MIN_SWIPE_DISTANCE || Math.abs(dx) < Math.abs(dy) * VERTICAL_DRAG_RATIO) return 0;
  return dx > 0 ? 1 : -1;
}

export interface NavItemLike {
  href: string;
}

export type NavGestureResult =
  | { kind: 'tap'; href: string | null }
  | { kind: 'swipe'; href: string }
  | { kind: 'none' };

/** Bottom-nav gesture resolution: taps target the tapped tab, swipes cycle the nav items. */
export function resolveNavGesture(params: {
  start: NavGesturePoint;
  end: NavGesturePoint;
  /** href of the closest nav link under the touch (null when on padding). */
  tappedHref: string | null;
  currentPath: string;
  navItems: NavItemLike[];
}): NavGestureResult {
  const { start, end, tappedHref, currentPath, navItems } = params;

  // A real tap (little movement in either axis).
  if (isTapGesture(start, end)) {
    return { kind: 'tap', href: tappedHref };
  }

  const step = swipeStep(start, end);
  if (step === 0) return { kind: 'none' };

  // Nested routes keep their parent nav item highlighted.
  const currentIndex = navItems.findIndex(
    (item) => currentPath === item.href || (item.href !== '/' && currentPath.startsWith(item.href + '/'))
  );
  if (currentIndex === -1) return { kind: 'none' };

  const nextIndex = (currentIndex + step + navItems.length) % navItems.length;
  return { kind: 'swipe', href: navItems[nextIndex].href };
}
