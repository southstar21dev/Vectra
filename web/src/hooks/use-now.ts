import { useEffect, useState } from 'react';

/**
 * Ticking clock.
 *
 * Every consumer of a given interval shares ONE `setInterval`, and all of them
 * are notified inside the same timer callback — so React batches their state
 * updates into a single commit. Previously each `useNow(1000)` call span its own
 * timer, so a transit page with N cards ran N separate 1 Hz timers and committed
 * N times a second for no reason.
 *
 * The timer for an interval stops as soon as its last consumer unmounts.
 */
interface Clock {
  value: number;
  timer: number | null;
  listeners: Set<(now: number) => void>;
}

const clocks = new Map<number, Clock>();

function acquireClock(intervalMs: number): Clock {
  let clock = clocks.get(intervalMs);
  if (!clock) {
    clock = { value: Date.now(), timer: null, listeners: new Set() };
    clocks.set(intervalMs, clock);
  }
  return clock;
}

function addListener(clock: Clock, intervalMs: number, listener: (now: number) => void): void {
  clock.listeners.add(listener);
  if (clock.timer == null) {
    clock.timer = window.setInterval(() => {
      clock.value = Date.now();
      for (const l of clock.listeners) l(clock.value);
    }, intervalMs);
  }
}

function removeListener(clock: Clock, listener: (now: number) => void): void {
  clock.listeners.delete(listener);
  if (clock.listeners.size === 0 && clock.timer != null) {
    window.clearInterval(clock.timer);
    clock.timer = null;
  }
}

/** Ticking clock hook — returns Date.now() updated every `intervalMs`. */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const clock = acquireClock(intervalMs);
    const listener = (value: number) => setNow(value);
    addListener(clock, intervalMs, listener);
    return () => removeListener(clock, listener);
  }, [intervalMs]);
  return now;
}
