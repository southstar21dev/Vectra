import { describe, it, expect, vi, afterEach } from 'vitest';
import { createDialogHistory, DIALOG_HISTORY_KEY, type DialogHistoryDeps } from './dialog-history';

function createFakeBrowser() {
  let pathname = '/schedule';
  const entries: (Record<string, unknown> | null)[] = [null];
  let index = 0;
  let pushCount = 0;
  let backCount = 0;

  const deps: DialogHistoryDeps = {
    pathname: () => pathname,
    pushState: (state) => {
      entries.splice(index + 1);
      entries.push(state);
      index += 1;
      pushCount += 1;
    },
    back: () => {
      backCount += 1;
      if (index > 0) index -= 1;
    },
    state: () => entries[index] ?? null,
    schedule: (fn) => {
      const timer = setTimeout(fn, 0);
      return () => clearTimeout(timer);
    },
  };

  return {
    deps,
    setPathname: (next: string) => {
      pathname = next;
    },
    pushes: () => pushCount,
    backs: () => backCount,
    armed: () => entries[index]?.[DIALOG_HISTORY_KEY] === true,
    /** Simulates the browser popping the current entry (a back gesture). */
    consumeEntry: () => {
      if (index > 0) index -= 1;
    },
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('createDialogHistory', () => {
  it('pushes one marked entry for the first dialog and none for nested dialogs', () => {
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);

    history.register('a', () => {});
    history.register('b', () => {});

    expect(browser.pushes()).toBe(1);
    expect(browser.armed()).toBe(true);
    expect(history.size()).toBe(2);
  });

  it('closes the top dialog on back and re-arms for the next back', () => {
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);
    const closed: string[] = [];
    history.register('a', () => closed.push('a'));
    history.register('b', () => closed.push('b'));

    browser.consumeEntry();
    history.handlePopState();

    expect(closed).toEqual(['b']);
    expect(browser.pushes()).toBe(2);
    expect(browser.armed()).toBe(true);

    browser.consumeEntry();
    history.handlePopState();

    expect(closed).toEqual(['b', 'a']);
    expect(browser.pushes()).toBe(2);
    expect(browser.armed()).toBe(false);
  });

  it('removes the entry after the deferred task when the last dialog closes programmatically', () => {
    vi.useFakeTimers();
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);
    const closed: string[] = [];
    const unregister = history.register('a', () => closed.push('a'));

    unregister();
    expect(browser.backs()).toBe(0);

    vi.runAllTimers();
    expect(browser.backs()).toBe(1);

    // The browser now delivers the popstate for our own history.back(); the
    // suppression flag must swallow it so nothing else closes.
    history.handlePopState();
    expect(closed).toEqual([]);
    expect(browser.armed()).toBe(false);
  });

  it('cancels the deferred removal when a dialog re-registers first (StrictMode remount)', () => {
    vi.useFakeTimers();
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);

    const unregister = history.register('a', () => {});
    unregister();
    history.register('a', () => {});

    vi.runAllTimers();

    expect(browser.backs()).toBe(0);
    expect(browser.pushes()).toBe(1);
    expect(browser.armed()).toBe(true);
  });

  it('leaves history untouched when a non-last dialog closes', () => {
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);
    const unregisterA = history.register('a', () => {});
    history.register('b', () => {});

    unregisterA();

    expect(browser.backs()).toBe(0);
    expect(history.size()).toBe(1);
    expect(browser.armed()).toBe(true);
  });

  it('dismissTop closes the top dialog and leaves history alone while others remain', () => {
    vi.useFakeTimers();
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);
    const closed: string[] = [];
    history.register('a', () => closed.push('a'));
    history.register('b', () => closed.push('b'));

    history.dismissTop();

    expect(closed).toEqual(['b']);
    expect(history.size()).toBe(1);
    expect(browser.backs()).toBe(0);

    history.dismissTop();

    expect(closed).toEqual(['b', 'a']);
    expect(history.size()).toBe(0);
    expect(browser.backs()).toBe(0);

    // The last dismiss schedules the entry's removal.
    vi.runAllTimers();
    expect(browser.backs()).toBe(1);

    // Its popstate is suppressed and closes nothing.
    history.handlePopState();
    expect(browser.armed()).toBe(false);
  });

  it('removes the history entry after dismissTop closes the last dialog', () => {
    vi.useFakeTimers();
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);
    history.register('a', () => {});

    history.dismissTop();
    expect(history.size()).toBe(0);
    expect(browser.armed()).toBe(true); // not yet cleaned

    vi.runAllTimers();
    expect(browser.backs()).toBe(1);
    expect(browser.armed()).toBe(false);
  });

  it('ignores popstate when the pathname changed (page navigation, not a dialog back)', () => {
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);
    const closed: string[] = [];
    history.register('a', () => closed.push('a'));

    browser.setPathname('/transit');
    browser.consumeEntry();
    history.handlePopState();

    expect(closed).toEqual([]);
    expect(history.size()).toBe(0);
    expect(browser.armed()).toBe(false);
  });

  it('reset clears the stack and suppresses pending removals', () => {
    vi.useFakeTimers();
    const browser = createFakeBrowser();
    const history = createDialogHistory(browser.deps);
    history.register('a', () => {});
    history.reset();

    vi.runAllTimers();

    expect(history.size()).toBe(0);
    expect(browser.backs()).toBe(0);
  });
});
