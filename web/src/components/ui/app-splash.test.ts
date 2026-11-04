import { describe, it, expect } from 'vitest';
import { SPLASH_MIN_DURATION_MS, SPLASH_TARGET_DURATION_MS, shouldDismissSplash, splashExitDelay } from './app-splash-logic';
import { SUNRISE_EXIT_MS } from './splash-scene';

describe('splash duration', () => {
  it('keeps the splash on screen at least 1.8s and normally ends it at 2s', () => {
    expect(SPLASH_MIN_DURATION_MS).toBe(2300);
    expect(SPLASH_TARGET_DURATION_MS).toBe(2500);
  });

  it('starts the sunrise exit so it ends right on target when the app is ready', () => {
    expect(splashExitDelay(SUNRISE_EXIT_MS) + SUNRISE_EXIT_MS).toBe(2500);
  });

  it('never ends a short exit before the minimum duration', () => {
    expect(splashExitDelay(200) + 200).toBeGreaterThanOrEqual(SPLASH_MIN_DURATION_MS);
  });
});

describe('shouldDismissSplash', () => {
  it('stays visible when the minimum duration has not elapsed, even if the app is ready', () => {
    expect(shouldDismissSplash(false, true)).toBe(false);
  });

  it('stays visible when the app is not ready, even after the minimum duration', () => {
    expect(shouldDismissSplash(true, false)).toBe(false);
  });

  it('dismisses only once the minimum duration has elapsed AND the app is ready', () => {
    expect(shouldDismissSplash(true, true)).toBe(true);
  });
});