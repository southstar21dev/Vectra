import { beforeEach, describe, expect, it } from 'vitest';
import { consumeScrollOnNextPage, markScrollOnNextPage } from './navigation-scroll';

describe('navigation scroll intent', () => {
  beforeEach(() => {
    consumeScrollOnNextPage();
  });

  it('defaults to not scrolling', () => {
    expect(consumeScrollOnNextPage()).toBe(false);
  });

  it('returns true exactly once after a mark', () => {
    markScrollOnNextPage();
    expect(consumeScrollOnNextPage()).toBe(true);
    expect(consumeScrollOnNextPage()).toBe(false);
  });

  it('collapses repeated marks into a single scroll', () => {
    markScrollOnNextPage();
    markScrollOnNextPage();
    expect(consumeScrollOnNextPage()).toBe(true);
    expect(consumeScrollOnNextPage()).toBe(false);
  });
});
