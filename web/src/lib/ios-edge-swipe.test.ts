import { describe, it, expect } from 'vitest';
import {
  isIosStandaloneApp,
  isEdgeBackSwipe,
  shouldDismissAfterGrace,
  shouldClaimEdgeTouch,
  scrimOpacityForDrag,
  estimateScreenCornerRadius,
  EDGE_START_MAX_X,
} from './ios-edge-swipe';

describe('isIosStandaloneApp', () => {
  it('is true only for the iOS home-screen flag', () => {
    expect(isIosStandaloneApp(true)).toBe(true);
    expect(isIosStandaloneApp(false)).toBe(false);
    expect(isIosStandaloneApp(undefined)).toBe(false);
  });
});

describe('isEdgeBackSwipe', () => {
  it('accepts a right swipe that starts at the left edge', () => {
    expect(isEdgeBackSwipe({ x: 5, y: 300 }, { x: 120, y: 305 })).toBe(true);
  });

  it('rejects swipes that do not start at the edge', () => {
    expect(isEdgeBackSwipe({ x: 100, y: 300 }, { x: 300, y: 300 })).toBe(false);
  });

  it('rejects short right swipes', () => {
    expect(isEdgeBackSwipe({ x: 5, y: 300 }, { x: 40, y: 300 })).toBe(false);
  });

  it('rejects leftward and vertical drags', () => {
    expect(isEdgeBackSwipe({ x: 5, y: 300 }, { x: -50, y: 300 })).toBe(false);
    expect(isEdgeBackSwipe({ x: 5, y: 300 }, { x: 120, y: 420 })).toBe(false);
  });
});

describe('shouldDismissAfterGrace', () => {
  it('acts when dialogs were open and none closed during the grace period', () => {
    expect(shouldDismissAfterGrace(2, 2)).toBe(true);
  });

  it('does nothing when the native gesture already closed a dialog', () => {
    expect(shouldDismissAfterGrace(2, 1)).toBe(false);
  });

  it('does nothing when no dialog is open', () => {
    expect(shouldDismissAfterGrace(0, 0)).toBe(false);
  });
});

describe('shouldClaimEdgeTouch', () => {
  it('claims edge touches only while a dialog is open', () => {
    expect(shouldClaimEdgeTouch(1, 5)).toBe(true);
    expect(shouldClaimEdgeTouch(1, EDGE_START_MAX_X)).toBe(true);
    expect(shouldClaimEdgeTouch(0, 5)).toBe(false);
  });

  it('does not claim touches away from the edge', () => {
    expect(shouldClaimEdgeTouch(2, EDGE_START_MAX_X + 1)).toBe(false);
    expect(shouldClaimEdgeTouch(2, 200)).toBe(false);
  });
});

describe('scrimOpacityForDrag', () => {
  it('starts fully opaque at rest and reaches zero at one viewport width', () => {
    expect(scrimOpacityForDrag(0, 400)).toBe(1);
    expect(scrimOpacityForDrag(200, 400)).toBe(0.5);
    expect(scrimOpacityForDrag(400, 400)).toBe(0);
  });

  it('clamps beyond the viewport and for negative offsets', () => {
    expect(scrimOpacityForDrag(600, 400)).toBe(0);
    expect(scrimOpacityForDrag(-40, 400)).toBe(1);
  });

  it('falls back to opaque when the viewport width is unusable', () => {
    expect(scrimOpacityForDrag(50, 0)).toBe(1);
  });
});

describe('estimateScreenCornerRadius', () => {
  it('maps known iPhone sizes', () => {
    expect(estimateScreenCornerRadius(393, 852)).toBe(55);
    expect(estimateScreenCornerRadius(402, 874)).toBe(62);
    expect(estimateScreenCornerRadius(440, 956)).toBe(62);
    expect(estimateScreenCornerRadius(390, 844)).toBe(47.33);
  });

  it('accepts landscape by normalizing width/height', () => {
    expect(estimateScreenCornerRadius(852, 393)).toBe(55);
  });

  it('maps iPads and square-corner phones', () => {
    expect(estimateScreenCornerRadius(1024, 1366)).toBe(18);
    expect(estimateScreenCornerRadius(375, 667)).toBe(0);
  });

  it('returns 0 for unknown sizes', () => {
    expect(estimateScreenCornerRadius(320, 480)).toBe(0);
  });
});
