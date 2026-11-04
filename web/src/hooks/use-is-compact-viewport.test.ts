import { describe, it, expect, vi } from 'vitest';
import { readCompactViewport, subscribeCompactViewport } from './use-is-compact-viewport';

describe('readCompactViewport', () => {
  it('treats a viewport below xl as compact', () => {
    expect(readCompactViewport({ matches: false })).toBe(true);
  });

  it('treats xl and wider as not compact', () => {
    expect(readCompactViewport({ matches: true })).toBe(false);
  });

  it('defaults to desktop when matchMedia is unavailable', () => {
    expect(readCompactViewport(null)).toBe(false);
  });
});

describe('subscribeCompactViewport', () => {
  it('subscribes and unsubscribes the change listener', () => {
    const onChange = () => {};
    const mql = { addEventListener: vi.fn(), removeEventListener: vi.fn() };
    const unsubscribe = subscribeCompactViewport(mql, onChange);

    expect(mql.addEventListener).toHaveBeenCalledWith('change', onChange);

    unsubscribe();

    expect(mql.removeEventListener).toHaveBeenCalledWith('change', onChange);
  });

  it('is a no-op when matchMedia is unavailable', () => {
    expect(() => subscribeCompactViewport(null, () => {})()).not.toThrow();
  });
});
