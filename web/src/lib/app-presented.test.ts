import { describe, it, expect, vi } from 'vitest';
import { hasAppPresented, markAppPresented, onAppPresented } from './app-presented';

// The signal is module state describing the boot, so these cases run in the one
// order a boot actually produces: wait → clear → already cleared.
describe('app presentation signal', () => {
  it('starts unpresented and holds listeners until the splash clears', () => {
    const kept = vi.fn();
    const unsubscribed = vi.fn();

    expect(hasAppPresented()).toBe(false);

    const unsubscribe = onAppPresented(unsubscribed);
    onAppPresented(kept);
    unsubscribe();

    expect(kept).not.toHaveBeenCalled();
    expect(unsubscribed).not.toHaveBeenCalled();

    markAppPresented();

    expect(hasAppPresented()).toBe(true);
    expect(kept).toHaveBeenCalledTimes(1);
    // Unsubscribing before the splash cleared means never being woken.
    expect(unsubscribed).not.toHaveBeenCalled();
  });

  it('runs a listener registered after presentation immediately, exactly once', () => {
    const late = vi.fn();

    onAppPresented(late);
    expect(late).toHaveBeenCalledTimes(1);

    // Marking again must not re-fire the already-notified listener.
    markAppPresented();
    expect(hasAppPresented()).toBe(true);
    expect(late).toHaveBeenCalledTimes(1);
  });
});
