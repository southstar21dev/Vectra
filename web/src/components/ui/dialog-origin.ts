/**
 * Remembers where the user last tapped, so a dialog can expand *out of the
 * control that opened it* — the native "grow from the tap" feel — instead of
 * always animating from the screen centre.
 *
 * The tap point is captured on `pointerdown` (which fires before the click that
 * opens the dialog). Keyboard opens have no pointer, so they fall back to the
 * focused control's centre.
 */
interface DialogOrigin {
  x: number;
  y: number;
  t: number;
}

interface PointerTarget {
  el: Element;
  t: number;
}

/** A tap older than this is treated as unrelated (e.g. a programmatic open). */
const FRESH_MS = 1000;

let last: DialogOrigin | null = null;
let lastTarget: PointerTarget | null = null;
let tracking = false;

function onPointerDown(event: PointerEvent): void {
  const t = performance.now();
  last = { x: event.clientX, y: event.clientY, t };
  lastTarget = event.target instanceof Element ? { el: event.target, t } : null;
}

/** Attach the capture listener once. */
export function ensureDialogOriginTracking(): void {
  if (tracking || typeof window === 'undefined') return;
  tracking = true;
  window.addEventListener('pointerdown', onPointerDown, { capture: true, passive: true });
}

/**
 * Where the dialog should expand from, or `null` to fall back to the centre.
 */
export function resolveDialogOrigin(): DialogOrigin | null {
  if (last && performance.now() - last.t <= FRESH_MS) return last;

  const el = typeof document !== 'undefined' ? document.activeElement : null;
  if (el instanceof HTMLElement) {
    const r = el.getBoundingClientRect();
    if (r.width || r.height) {
      return { x: r.left + r.width / 2, y: r.top + r.height / 2, t: performance.now() };
    }
  }
  return null;
}

/**
 * The element the user just pressed, or `null` when the last press is stale.
 * Focus restore uses it when a click left no focused element behind: a
 * non-focusable opener (a clickable card `div`) or a browser that doesn't
 * focus buttons on click (Safari).
 */
export function resolveRecentPointerTarget(): Element | null {
  if (lastTarget && performance.now() - lastTarget.t <= FRESH_MS) return lastTarget.el;
  return null;
}
