import { describe, expect, it } from 'vitest';
import { ABOVE_HOME_KEY, ensureHomeBeneath, navigateInApp, type HistoryLike, isKnownAppPath } from './app-navigation';
import { DIALOG_HISTORY_KEY } from './dialog-history';

/** Minimal in-memory session history. */
function fakeHistory(startUrl: string, startState: unknown = null) {
  const entries: { url: string; state: unknown }[] = [{ url: startUrl, state: startState }];
  let index = 0;
  const history: HistoryLike & { entries: typeof entries; index: () => number; path: () => string } = {
    get state() {
      return entries[index].state;
    },
    pushState(state, _u, url) {
      entries.splice(index + 1);
      entries.push({ url, state });
      index += 1;
    },
    replaceState(state, _u, url) {
      entries[index] = { url, state };
    },
    back() {
      if (index > 0) index -= 1;
    },
    entries,
    index: () => index,
    path: () => entries[index].url,
  };
  return history;
}

describe('navigateInApp', () => {
  it('pushes one page above Home', () => {
    const h = fakeHistory('/');
    expect(navigateInApp('/', '/schedule', h)).toBe(true);
    expect(h.entries.map((e) => e.url)).toEqual(['/', '/schedule']);
    expect(h.state).toEqual({ [ABOVE_HOME_KEY]: true });
  });

  it('replaces instead of stacking between non-home pages', () => {
    const h = fakeHistory('/');
    navigateInApp('/', '/schedule', h);
    navigateInApp('/schedule', '/transit', h);
    navigateInApp('/transit', '/settings', h);
    expect(h.entries.map((e) => e.url)).toEqual(['/', '/settings']);
    expect(h.state).toEqual({ [ABOVE_HOME_KEY]: true });
  });

  it('goes back to the existing Home entry instead of pushing a new one', () => {
    const h = fakeHistory('/');
    navigateInApp('/', '/transit', h);
    expect(navigateInApp('/transit', '/', h)).toBe(false);
    expect(h.index()).toBe(0);
    expect(h.path()).toBe('/');
  });

  it('replaces to Home when there is no Home entry beneath', () => {
    const h = fakeHistory('/schedule');
    expect(navigateInApp('/schedule', '/', h)).toBe(true);
    expect(h.entries.map((e) => e.url)).toEqual(['/']);
  });

  it('does not go back through an open dialog trap entry', () => {
    const h = fakeHistory('/');
    navigateInApp('/', '/transit', h);
    h.pushState({ [ABOVE_HOME_KEY]: true, [DIALOG_HISTORY_KEY]: true }, '', '/transit');
    expect(navigateInApp('/transit', '/', h)).toBe(true);
    expect(h.path()).toBe('/');
  });
});

describe('ensureHomeBeneath', () => {
  it('inserts Home under a deep-linked page', () => {
    const h = fakeHistory('/transit?stop=1');
    ensureHomeBeneath('/transit', '/transit?stop=1', h);
    expect(h.entries.map((e) => e.url)).toEqual(['/', '/transit?stop=1']);
    expect(h.state).toEqual({ [ABOVE_HOME_KEY]: true });
  });

  it('is a no-op on Home, public pages, and a reload that is already above Home', () => {
    for (const path of ['/', '/login', '/signup']) {
      const h = fakeHistory(path);
      ensureHomeBeneath(path, path, h);
      expect(h.entries).toHaveLength(1);
    }
    const h = fakeHistory('/schedule', { [ABOVE_HOME_KEY]: true });
    ensureHomeBeneath('/schedule', '/schedule', h);
    expect(h.entries).toHaveLength(1);
  });
});

describe('isKnownAppPath', () => {
  it('accepts home, the public pages and each section with sub-paths', () => {
    for (const path of ['/', '/login', '/signup', '/schedule', '/transit/stop/1', '/settings']) {
      expect(isKnownAppPath(path)).toBe(true);
    }
  });

  it('rejects unknown paths and look-alike prefixes', () => {
    for (const path of ['/nope', '/schedulex', '/icons/icon-192.png', '/api/health', '/login/extra']) {
      expect(isKnownAppPath(path)).toBe(false);
    }
  });
});
