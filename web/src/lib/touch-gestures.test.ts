import { describe, it, expect } from 'vitest';
import { isTapGesture, swipeStep, resolveNavGesture } from './touch-gestures';

const NAV_ITEMS = [
  { href: '/' },
  { href: '/schedule' },
  { href: '/transit' },
];

const gesture = (overrides: Partial<Parameters<typeof resolveNavGesture>[0]> = {}) =>
  resolveNavGesture({
    start: { x: 100, y: 50 },
    end: { x: 100, y: 50 },
    tappedHref: '/transit',
    currentPath: '/',
    navItems: NAV_ITEMS,
    ...overrides,
  });

describe('isTapGesture', () => {
  it('returns true for a stationary touch', () => {
    expect(isTapGesture({ x: 100, y: 50 }, { x: 102, y: 51 })).toBe(true);
  });

  it('returns false once movement exceeds the tap slop', () => {
    expect(isTapGesture({ x: 100, y: 50 }, { x: 130, y: 55 })).toBe(false);
  });
});

describe('swipeStep', () => {
  it('maps a right swipe to +1 (next / to the right)', () => {
    // Regression: the schedule day switcher used the opposite mapping (left = next);
    // both navbars now agree that a right swipe moves to the right.
    expect(swipeStep({ x: 100, y: 50 }, { x: 200, y: 52 })).toBe(1);
  });

  it('maps a left swipe to -1 (previous / to the left)', () => {
    expect(swipeStep({ x: 200, y: 50 }, { x: 100, y: 50 })).toBe(-1);
  });

  it('returns 0 for short drags', () => {
    expect(swipeStep({ x: 100, y: 50 }, { x: 130, y: 55 })).toBe(0);
  });

  it('returns 0 for vertical drags', () => {
    expect(swipeStep({ x: 100, y: 50 }, { x: 200, y: 170 })).toBe(0);
  });
});

describe('resolveNavGesture', () => {
  it('classifies a stationary touch on a tab as a tap that navigates to the tab', () => {
    // Regression: taps must produce a navigation decision on touchend —
    // navigation must not depend on the browser synthesizing a click after
    // the touch (the first tap after a swipe is suppressed on some devices).
    expect(gesture()).toEqual({ kind: 'tap', href: '/transit' });
  });

  it('still treats small finger jitter (within tap slop) as a tap', () => {
    expect(
      gesture({ end: { x: 115, y: 55 } }) // 15px < 20px slop
    ).toEqual({ kind: 'tap', href: '/transit' });
  });

  it('returns a tap with a null href when the touch is on the nav padding', () => {
    expect(gesture({ tappedHref: null })).toEqual({ kind: 'tap', href: null });
  });

  it('maps a right swipe to the next tab', () => {
    expect(
      gesture({ end: { x: 200, y: 52 }, tappedHref: null }) // dx=100 > 50
    ).toEqual({ kind: 'swipe', href: '/schedule' });
  });

  it('maps a left swipe to the previous tab', () => {
    expect(
      gesture({
        start: { x: 200, y: 50 },
        end: { x: 100, y: 50 },
        tappedHref: null,
        currentPath: '/transit',
      })
    ).toEqual({ kind: 'swipe', href: '/schedule' });
  });

  it('wraps around: a left swipe from the first tab goes to the last', () => {
    expect(gesture({ end: { x: 20, y: 50 }, tappedHref: null })).toEqual({
      kind: 'swipe',
      href: '/transit',
    });
  });

  it('keeps parent tabs highlighted on sub-routes when swiping', () => {
    expect(
      gesture({ end: { x: 200, y: 50 }, tappedHref: null, currentPath: '/schedule/2026-09-01' })
    ).toEqual({ kind: 'swipe', href: '/transit' });
  });

  it('ignores short drags and vertical drags', () => {
    expect(gesture({ end: { x: 130, y: 55 }, tappedHref: null })).toEqual({ kind: 'none' });
    expect(gesture({ end: { x: 200, y: 170 }, tappedHref: null })).toEqual({ kind: 'none' });
  });
});
